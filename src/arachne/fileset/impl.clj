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
   [java.nio.file Path Files SimpleFileVisitor LinkOption StandardCopyOption
                  StandardOpenOption FileVisitResult]
   [java.nio.file.attribute FileAttribute]
   [java.nio.channels Channels FileChannel]
   [org.apache.commons.io FileUtils]))

;; These can be truly proces  global, because they only contain immmutable
;; content-addressed hard links or one-off subdirectories.
(def global-blob-dir (memoize tmpdir/tmpdir!))
(def global-scratch-dir (memoize tmpdir/tmpdir!))
(def default-cache-dir (memoize tmpdir/tmpdir!))

(def CACHE_VERSION "1.0.0")
(def mem-cache (atom {}))
(def prev-fs (atom {}))

(def link-opts    (into-array LinkOption []))
(def tmp-attrs    (into-array FileAttribute []))
(def move-opts    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
(def copy-opts    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING
                                                  StandardCopyOption/COPY_ATTRIBUTES]))
(def continue     FileVisitResult/CONTINUE)

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
  (-add-cached     [this cache-key cache-fn opts])
  (-mv             [this from-path to-path])
  (-cp             [this src-file dest-tmpfile])
  (-checksum       [this timestamps?]))

(defrecord TmpFile [bdir path id hash time meta channel]
  ITmpFile
  (-path [this] path)
  (-meta [this] meta)
  (-hash [this] hash)
  (-time [this] time)
  (-channel [this] channel)
  (-file [this]
    (let [f (io/file bdir id)]
      (when-not (.exists f)
        (locking channel
          (.position channel 0)
          (FileUtils/copyToFile (Channels/newInputStream channel) f)))
      f)))

(declare ->TmpFileSet)
(defn fileset
  "Create a new, empty fileset. Optionally, takes a directory to use as a
  persistent cache, otherwise caches in a process-wide temporary directory."
  ([] (fileset (default-cache-dir)))
  ([cache-dir]
   (->TmpFileSet {} (global-blob-dir) (global-scratch-dir) cache-dir)))

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

(defn- add-blob!
  [^File blob ^Path src ^String id link]
  (let [blob (.toPath blob)
        out  (.resolve blob id)]
    (when-not (Files/exists out link-opts)
      (if link
        (Files/createLink out src)
        (let [name (str (.getName out (dec (.getNameCount out))))
              tmp  (Files/createTempFile blob name nil tmp-attrs)]
          (Files/copy src tmp copy-opts)
          (Files/move tmp out move-opts)
          (.setReadOnly (.toFile out)))))))

(defn- channel
  "Return an open READ channel to the given path. A reference to this object may be
   maintained to keep the tmpfile from being deleted."
  [path]
  (FileChannel/open path (into-array [StandardOpenOption/READ])))

(defn- mkvisitor
  [^Path root ^File blob tree link]
  (let [m {:bdir blob}]
    (proxy [SimpleFileVisitor] []
      (visitFile [^Path path attr]
        (with-let [_ continue]
          (let [p (str (.relativize root path))]
            (try (let [h (util/md5 path)
                       t (.toMillis (Files/getLastModifiedTime path link-opts))
                       i (str h "." t)
                       ch (channel path)]
                   (add-blob! blob path i link)
                   (swap! tree assoc p (map->TmpFile
                                         (assoc m :path p :id i :hash h
                                                  :time t :channel ch))))
                 (catch java.nio.file.NoSuchFileException _
                   (debug "Tmpdir: file not found: %s\n" (.toString p))))) )))))

(defn- dir->tree!
  [dir ^File blob]
  (locking dir->tree!
    (let [root (if (instance? java.io.File dir)
                 (.toPath dir)
                 dir)]
      @(with-let [tree (atom {})]
         (util/walk-file-tree root (mkvisitor root blob tree *hard-link*))))))

(defn- ^File cache-dir
  [^File cache-location cache-key]
  (-> cache-location
      (io/file "fileset")
      (io/file CACHE_VERSION cache-key)))

(defn- ^File manifest-file
  [cache-location cache-key]
  (io/file (cache-dir cache-location cache-key) "manifest.properties"))

(defn- read-manifest
  [^File manifile ^File bdir]
  (with-open [r (io/input-stream manifile)]
    (let [p (doto (Properties.) (.load r))]
      (-> #(let [id   (.getProperty p %2)
                 hash (subs id 0 32)
                 time (Long/parseLong (subs id 33))
                 ch (channel (.toPath manifile))
                 m    {:id id :path %2 :hash hash :time time :bdir bdir :channel ch}]
             (->> m map->TmpFile (assoc %1 %2)))
          (reduce {} (enumeration-seq (.propertyNames p)))))))

(defn- write-manifest!
  [^File manifile manifest]
  (with-open [w (io/output-stream manifile)]
    (let [p (Properties.)]
      (doseq [[path {:keys [id]}] manifest]
        (.setProperty p path id))
      (.store p w nil))))

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

(defn- get-cached!
  [cache-location cache-key seedfn scratch]
  (debug "Adding cached fileset %s...\n" cache-key)
  (or (get-in @mem-cache [(.getCanonicalPath cache-location) cache-key])
      (let [cache-dir (cache-dir cache-location cache-key)
            manifile  (manifest-file cache-location cache-key)
            store!    #(with-let [m %]
                         (swap! mem-cache assoc-in
                           [(.getCanonicalPath cache-location) cache-key] m))]
        (or (and (.exists manifile)
                 (store! (read-manifest manifile cache-dir)))
            (let [tmp-dir (scratch-dir! scratch)]
              (debug "Not found in cache: %s...\n" cache-key)
              (.mkdirs cache-dir)
              (seedfn tmp-dir)
              (binding [*hard-link* true]
                (let [m (dir->tree! tmp-dir cache-dir)]
                  (write-manifest! manifile m)
                  (store! (read-manifest manifile cache-dir)))))))))

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

(defn- prev-fileset
  "Determine the fileset previously associated with the given directory (either from the cache, or
   from inspecting the dir)"
  [^File dir]
  (let [path (.getCanonicalPath dir)
        [cached-fs cached-ts] (get @prev-fs path)]
    (if (and cached-fs (<= (.lastModified dir) cached-ts))
      cached-fs
      (when (seq (.list dir))
        ;; Dir already exists and is not empty, but is newer than cache (or cache doesn't exist)
        (current-fileset dir)))))

(defrecord TmpFileSet [tree blob scratch cache]
  ITmpFileSet

  (-ls [this]
    (set (vals tree)))

  ;; detect the badness via changed timestamp on the output dir: if it differs from wha we thought we had cached, invalidate the cache and rebuild
  (-commit! [this dir]
    (let [prev (prev-fileset dir)
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
    (let [{:keys [tree blob scratch]} this
          {:keys [mergers include exclude meta]} opts
          ->tree #(dir->tree! % blob)
          new-tree (-> (->tree src-dir)
                       (filter-tree include exclude)
                       (add-tree-meta meta))
          mrg-tree (when mergers
                     (->tree (merge-trees! tree new-tree mergers scratch)))]
      (assoc this :tree (merge-with merge-tempfiles tree new-tree mrg-tree))))

  (-add-cached [this cache-key cache-fn opts]
    (let [{:keys [tree blob scratch]} this
          {:keys [mergers include exclude meta]} opts
          new-tree (let [cached (get-cached! cache cache-key cache-fn scratch)]
                     (-> (filter-tree cached include exclude)
                         (add-tree-meta meta)))
          mrg-tree (when mergers
                     (let [merged (merge-trees! tree new-tree mergers scratch)]
                       (dir->tree! merged blob)))]
      (assoc this :tree (merge tree new-tree mrg-tree))))

  (-mv [this from-path to-path]
    (if (= from-path to-path)
      this
      (if-let [from (get-in this [:tree from-path])]
        (update-in this [:tree] #(-> % (assoc to-path (assoc from :path to-path))
                                     (dissoc from-path)))
        (throw (Exception. (format "not in fileset (%s)" from-path))))))

  (-cp [this src-file dest-tmpfile]
    (let [hash (util/md5 src-file)
          p'   (-path dest-tmpfile)]
      (add-blob! blob src-file hash *hard-link*)
      (assoc this :tree (merge tree {p' (assoc dest-tmpfile :id hash)}))))

  (-checksum [this timestamps?]
    (let [basis (set (map (fn [tmpfile]
                            (select-keys tmpfile [:path :hash (when timestamps? :time)]))
                       (vals (:tree this))))]
      (vh/md5-str basis))))
