;; Derived from boot.fileset, many thanks to Alan and Micha for their code and help.
;; This file remains copyright Alan Dipert and Micha Niskin, and is re-used here
;; under the terms of the Eclipse Public License.
(ns arachne.fileset.impl
  (:require
    [arachne.fileset.util :as util :refer [with-let debug warn]]
    [arachne.fileset.tmpdir :as tmpdir]
    [clojure.java.io        :as io]
    [clojure.set            :as set]
    [clojure.data           :as data]
    [valuehash.api          :as vh])
  (:import
   [java.io File]
   [java.util Properties]
   [java.nio.file Path Paths Files SimpleFileVisitor LinkOption StandardCopyOption
                  StandardOpenOption FileVisitResult]
   [java.nio.file.attribute FileAttribute]
   [java.nio.channels Channels FileChannel]
   [org.apache.commons.io FileUtils]))

;; Plan: manage lifecycle & cleanup via reference counting
;; Plan: clean up api by removing caches and linking options

;; These can be truly process global, because they only contain immmutable
;; content-addressed hard links or one-off subdirectories.

(def global-scratch-dir (memoize tmpdir/tmpdir!))

(def prev-fs (atom {}))

(def link-opts    (into-array LinkOption []))
(def tmp-attrs    (into-array FileAttribute []))
(def move-opts    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
(def copy-opts    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING
                                                  StandardCopyOption/COPY_ATTRIBUTES]))
(def continue     FileVisitResult/CONTINUE)

(defprotocol BlobStore
  "Manager for the lifecycle of immutable binary files."
  (-blob-add [this path source]
    "Add the given source file to blob store at the given path,
    returning a new TmpFile instance.")
  (-blob-get [this id] "Return a File object in the blob store. The
  File is guaranteed to exist and to be readonly. If the given ID does
  not exist in the blobstore, returns nil.")
  (-blob-release [this id] "Release the blob with the given ID."))

(declare map->TmpFile)
(declare channel)
(declare -time)

(defrecord TmpDirBlobStore [tmpdir references]
  BlobStore
  (-blob-add [this path source]
    (let [hash (util/md5 source)
          ts (.toMillis (Files/getLastModifiedTime source link-opts))
          id (str hash "." ts)
          tf (map->TmpFile {:path path :id id :hash hash :time ts :blobstore this})]
      (locking references
        (if (@references id)
          (swap! references update-in [id :refs] inc)
          (let [blob (.resolve (.toPath tmpdir) id)]
            (when-not (.exists (.toFile blob))
              (Files/copy source blob copy-opts)
              (.setReadOnly (.toFile blob)))
            (swap! references assoc id {:refs 1
                                        :ch (channel blob)}))))
      tf))
  (-blob-get [this tmpfile]
    (if-let [ref (@references (:id tmpfile))]
      (let [file (io/file tmpdir (:id tmpfile))]
        (when-not (.exists file)
          (locking (:ch ref)
            (.position (:ch ref) 0)
            (FileUtils/copyToFile
              (Channels/newInputStream (:ch ref))
              file)
            (.setLastModified file (:time tmpfile))))
        file)))
  (-blob-release [this id]
    (locking references
      (swap! references (fn [all-refs]
                          (if-let [ref (get all-refs id)]
                            (if (<= (:refs ref) 1)
                              (do
                                (when (:ch ref) (.close (:ch ref)))
                                (Files/delete (.resolve (.toPath tmpdir) id))
                                (dissoc all-refs id))
                              (update-in all-refs [id :refs] dec))
                            all-refs))))))

(defn new-blobstore
  "Construct a new tmpdir-based Blobstore"
  []
  (->TmpDirBlobStore (tmpdir/tmpdir!) (atom {})))

(def global-blobstore (memoize new-blobstore))

(defprotocol ITmpFile
  (-path [this])
  (-meta [this])
  (-hash [this])
  (-time [this])
  (-channel [this])
  (-file [this]))

(defprotocol ITmpFileSet
  (-ls             [this])
  (-commit!        [this dir])
  (-rm             [this paths])
  (-add            [this src-dir opts])
  (-mv             [this from-path to-path])
  (-checksum       [this timestamps?]))

(defrecord TmpFile [path id hash time meta blobstore]
  ITmpFile
  (-path [this] path)
  (-meta [this] meta)
  (-hash [this] hash)
  (-time [this] time)
  (-channel [this] channel)
  (-file [this]
    (-blob-get blobstore this))
  Object
  (finalize [_] (-blob-release blobstore id)))

(declare ->TmpFileSet)
(defn fileset
  "Create a new, empty fileset."
  []
  (->TmpFileSet {} (global-blobstore) (global-scratch-dir)))

(defn- file
  [^File dir tmpfile]
  (io/file dir (-path tmpfile)))

(defn- file-stat
  [^File f]
  (let [h (util/md5 f)
        t (.lastModified f)]
    {:id (str h "." t) :hash h :time t}))

(defn- scratch-dir!
  [^File scratch]
  (.toFile (Files/createTempDirectory (.toPath scratch)
              "boot-scratch" (into-array FileAttribute []))))

(def ^:dynamic *hard-link* nil)

(defn- channel
  "Return an open READ channel to the given path. A reference to this object may be
   maintained to keep the tmpfile from being deleted."
  [path]
  (FileChannel/open path (into-array [StandardOpenOption/READ])))

(defn- mkvisitor
  [^Path root blobstore tree]
  (proxy [SimpleFileVisitor] []
    (visitFile [^Path path attr]
      (with-let [_ continue]
        (let [relpath (str (.relativize root path))
              tmpfile (-blob-add blobstore relpath path)]
          (swap! tree assoc relpath tmpfile))))))

(defn- dir->tree!
  [dir blobstore]
  (locking dir->tree!
    (let [root (if (instance? java.io.File dir)
                 (.toPath dir)
                 dir)]
      @(with-let [tree (atom {})]
         (util/walk-file-tree root (mkvisitor root blobstore tree))))))

(defn- apply-mergers!
  [mergers ^File old-file path ^File new-file ^File merged-file]
  (when-let [merger (some (fn [[re v]] (when (re-find re path) v)) mergers)]
    (debug "Merging duplicate entry (%s)\n" path)
    (let [out-file (File/createTempFile (.getName merged-file) nil
                                        (.getParentFile merged-file))]
      (with-open [curr-stream (io/input-stream old-file)
                  new-stream  (io/input-stream new-file)
                  out-stream  (io/output-stream out-file)]
        (merger curr-stream new-stream out-stream))
      (util/move out-file merged-file))))

(defn- merge-trees!
  [old new mergers scratch]
  (with-let [tmp (scratch-dir! scratch)]
    (doseq [[path newtmp] new]
      (when-let [oldtmp (get old path)]
        (debug "Merging %s...\n" path)
        (let [newf   (-file newtmp)
              oldf   (-file oldtmp)
              mergef (doto (io/file tmp path) io/make-parents)]
          (apply-mergers! mergers oldf path newf mergef))))))

(defn- comp-res
  [regexes]
  (when-let [res (seq regexes)]
    (->> (map #(partial re-find %) res) (apply some-fn))))

(defn- filter-tree
  [tree include exclude]
  (let [ex  (comp-res exclude)
        in  (when-let [in (comp-res include)] (complement in))
        rm? (or (and in ex #(or (in %) (ex %))) in ex)]
    (if-not rm? tree (reduce-kv #(if (rm? %2) %1 (assoc %1 %2 %3)) {} tree))))

(defn- index
  [key tree]
  (reduce-kv #(assoc %1 (get %3 key) %3) {} tree))

(defn- diff-tree
  [tree props]
  (let [->map #(select-keys % props)]
    (reduce-kv #(assoc %1 %2 (->map %3)) {} tree)))

(defn diff*
  [{t1 :tree :as before} {t2 :tree :as after} props]
  (if-not before
    {:added   after
     :removed (assoc after :tree {})
     :changed (assoc after :tree {})}
    (let [props   (or (seq props) [:id])
          d1      (diff-tree t1 props)
          d2      (diff-tree t2 props)
          [x y _] (map (comp set keys) (data/diff d1 d2))]
      {:added   (->> (set/difference   y x) (select-keys t2) (assoc after :tree))
       :removed (->> (set/difference   x y) (select-keys t1) (assoc after :tree))
       :changed (->> (set/intersection x y) (select-keys t2) (assoc after :tree))})))

(defn- fatal-conflict?
  [^File dest]
  (if (.isDirectory dest)
    (let [tree (->> dest file-seq reverse)]
      (or (not (every? #(.isDirectory ^File %) tree))
          (doseq [^File f tree] (.delete f))))
    (not (let [d (.getParentFile dest)]
           (or (.isDirectory d) (.mkdirs d))))))

(defn- add-tree-meta
  [tree meta]
  (if (empty? meta)
    tree
    (reduce-kv (fn [tree k tmpfile]
                 (assoc tree k (update tmpfile :meta #(merge %1 meta))))
      {} tree)))

(defn- merge-tempfiles
  "Merge two TmpFile records"
  [a b]
  (assoc (merge a b) :meta (merge (:meta a) (:meta b))))

(defn- current-fileset
  "Return a new fileset representing the current state of a directory"
  [^File dir]
  (let [fs (fileset)]
    (-add fs dir {})))

(defrecord TmpFileSet [tree blobstore scratch]
  ITmpFileSet
  (-ls [this]
    (set (vals tree)))
  (-commit! [this dir]
    (let [prev (current-fileset dir)
          {:keys [added removed changed]} (diff* prev this [:id])]
      (debug "Committing fileset...\n")
      (doseq [tmpf (set/union (-ls removed) (-ls changed))
              :let [prev (get-in prev [:tree (-path tmpf)])
                    exists? (.exists ^File (file dir prev))
                    op (if exists? "removing" "no-op")]]
        (when exists? (io/delete-file (file dir prev))))
      (let [this (loop [this this
                        [tmpf & tmpfs]
                        (->> (set/union (-ls added) (-ls changed))
                             (sort-by (comp count -path) >))]
                   (or (and (not tmpf) this)
                       (let [p    (-path tmpf)
                             dst  (file dir tmpf)
                             src  (-file tmpf)
                             err? (fatal-conflict? dst)
                             this (or (and (not err?) this)
                                      (update-in this [:tree] dissoc p))]
                         (if err?
                           (warn "Merge conflict: not adding %s\n" p)
                           (util/hard-link src dst))
                         (recur this tmpfs))))]
        (with-let [_ this]
          (swap! prev-fs assoc (.getCanonicalPath ^File dir) [this (.lastModified ^File dir)])
          (debug "Commit complete.\n")))))
  (-rm [this tmpfiles]
    (let [{:keys [tree]} this
          treefiles (set (vals tree))
          remove?   (->> tmpfiles set (set/difference treefiles) complement)]
      (assoc this :tree (reduce-kv #(if (remove? %3) %1 (assoc %1 %2 %3)) {} tree))))
  (-add [this src-dir opts]
    (let [{:keys [tree blobstore scratch]} this
          {:keys [mergers include exclude meta]} opts
          ->tree #(dir->tree! % blobstore)
          new-tree (-> (->tree src-dir)
                       (filter-tree include exclude)
                       (add-tree-meta meta))
          mrg-tree (when mergers
                     (->tree (merge-trees! tree new-tree mergers scratch)))]
      (assoc this :tree (merge-with merge-tempfiles tree new-tree mrg-tree))))
  (-mv [this from-path to-path]
    (if (= from-path to-path)
      this
      (if-let [from (get-in this [:tree from-path])]
        (update-in this [:tree] #(-> % (assoc to-path (assoc from :path to-path))
                                     (dissoc from-path)))
        (throw (Exception. (format "not in fileset (%s)" from-path))))))
  (-checksum [this timestamps?]
    (let [basis (set (map (fn [tmpfile]
                            (select-keys tmpfile [:path :hash (when timestamps? :time)]))
                       (vals (:tree this))))]
      (vh/md5-str basis))))
