(ns arachne.fileset.tmpdir
  "Tools for creating and managing temporary directories"
  (:require [arachne.fileset.util :as util])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private tmp-registry (atom #{}))

(.addShutdownHook (Runtime/getRuntime)
  (Thread. (fn []
             (util/debug "cleaning up temp directories")
             (dorun (map util/delete! @tmp-registry)))))

(defn tmpdir!
  "Return a new temporary directory as a java.io.File. The directory will be in
  the system temporary directory, and tracked for deletion when the JVM
  terminates (using a JVM shutdown hook.)"
  []
  (let [f (.toFile (Files/createTempDirectory "arachne-fs"
                     (make-array FileAttribute 0)))]
    (util/debug "Creating temp directory at " (.getPath f))
    (swap! tmp-registry conj f)
    f))
