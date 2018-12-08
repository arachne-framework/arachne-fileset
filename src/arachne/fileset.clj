(ns arachne.fileset
  (:refer-clojure :exclude [remove filter empty merge hash])
  (:require [arachne.fileset.specs]
            [arachne.fileset.impl :as impl]
            [arachne.fileset.util :as futil]
            [arachne.fileset.tmpdir :as tmpdir]
            [clojure.java.io :as io]))

(def fileset impl/fileset)

(defn commit!
  "Persist the immutable fileset to a concrete directory. The emitted
  files are hard links to the fileset's internal blob storage, and therefore
  immutable.

  Note that `commit!` assumes that it is the only process modifying the
  destination directory. If another privileged process deletes any of the
  contents of the commit dir, they might not be re-created on subsequent
  commits."
  [fs dir]
  (impl/-commit! fs dir))

(defn add
  "Return a Fileset with all the files in the given directory added.

   The directory may be a java.io.File or java.nio.Path object.

   Options are as follows:

  :include - only add files that match regexes in this collection
  :exclude - do not add files that match regexes in this collection (takes
             priority over :include)
  :meta - map of metadata that will be added each file
  :mergers - a map of regex patterns to merge functions. When a file to be added
            already exists in the fileset, and its name matches a key in the
            mergers map, uses the specified merge function to determine the
            resulting contents of the file.

            The default behavior (with no merge function) is to replace the file.

            Merge functions take three arguments: an InputStream of the contents
            of the existing file, an InputStream of the contents of the new
            file, and an OutputStream that will contain the contents of the
            resulting file. The streams will be closed after the merge function
            returns (meaning that it should do all its processing eagerly.)"
  [fileset dir & {:keys [include exclude mergers meta] :as opts}]
  (impl/-add fileset dir opts))

(declare filter)
(defn remove
  "Return a Fileset with the specified paths removed."
  [fileset & paths]
  (let [paths (set paths)]
    (filter fileset #(not (paths (impl/-path %))))))

(defn diff
  "Return a Fileset containing only the files that are different in `added` and
  `before` or not present in `before`"
  [before after]
  (let [{:keys [added changed]}
        (impl/diff* before after nil)]
    (update-in added [:tree] clojure.core/merge (:tree changed))))

(defn removed
  "Return a Fileset containing only the files present in `before` and not in
  `after`"
  [before after]
  (:removed (impl/diff* before after nil)))

(defn added
  "Return a Fileset containing only the files that are present in `after` but
  not `before`"
  [before after]
  (:added (impl/diff* before after nil)))

(defn changed
  "Return a Fileset containing only the files that are different in `after` and
  `before`"
  [before after]
  (:changed (impl/diff* before after nil)))

(defn filter
  "Return a fileset containing only files for which the predicate returns true
  when applied to the TempFile"
  [fileset pred]
  (assoc fileset :tree (reduce-kv (fn [xs k v]
                                    (if (pred v)
                                      (assoc xs k v)
                                      xs))
                         {} (:tree fileset))))

(defn filter-by-meta
  "Return a fileset containing only files for which the predicate returns true
  when applied to the metadata of a TempFile"
  [fileset pred]
  (filter fileset (comp pred :meta)))

(defn ls
  "Return a collection of the paths present in the fileset"
  [fileset]
  (map impl/-path (impl/-ls fileset)))

(defn hash
  "Return the MD5 hash of the content of the file at the specified path in the
  fileset"
  [fileset path]
  (impl/-hash (get-in fileset [:tree path])))

(defn timestamp
  "Return the 'last modified' timestamp of the file (as a long) at the specified
   path in the fileset"
  [fileset path]
  (impl/-time (get-in fileset [:tree path])))

(defn file
  "Returns a java.io.File of the underlying file at the given path. Note that the given file MUST NOT be
   modified, at the risk of corrupting the fileset.

   Returns nil if the path does not exist in the fileset."
  [fileset path]
  (when-let [tmpf (get-in fileset [:tree path])]
    (impl/-file tmpf)))

(defn content
  "Opens and returns a java.io.InputStream of the contents of the file at the given path, or nil
   if the path does not exist."
  [fileset path]
  (when-let [f (file fileset path)]
    (io/input-stream f)))

(defn- merge-tempfile
  "Merge two tempfiles, logging a warning if one would overwrite the other"
  [a b]
  (let [[winner loser] (if (< (impl/-time a) (impl/-time b)) [b a] [a b])]
    (when-not (and (= (impl/-hash a) (impl/-hash b))
                   (= (impl/-meta a) (impl/-meta b)))
      (futil/warn "File at path %s was overwritten while merging filesets. Using the file timestamped %s, which is newer than %s"
        (impl/-path winner) (impl/-time winner) (impl/-time loser)))
    (update winner :meta #(clojure.core/merge %1 (impl/-meta loser)))))

(defn merge
  "Merge multiple filesets. If a path exists in more than one fileset, with
  different content, the most recent one is used and a warning is logged."
  ([fs] fs)
  ([a b]
   (assoc a :tree (merge-with merge-tempfile (:tree a) (:tree b))))
  ([a b & more]
   (reduce merge a (cons b more))))

(defn tmpdir!
  "Return a new temporary directory as a java.io.File. The directory will be in
  the system temporary directory, and tracked for deletion when the JVM
  terminates (using a JVM shutdown hook.)"
  []
  (tmpdir/tmpdir!))

(defn checksum
  "Return the MD5 checksum of the fileset itself. Two filesets with identical contents will have the same hash.

  If timestamps? is true, will incorporate the file's \"last modified\" date into the hash
  function, otherwise will hash based only on file names and contents"
  [fs timestamps?]
  (impl/-checksum fs timestamps?))
