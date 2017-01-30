(ns arachne.fileset.util
  (:require [clojure.tools.logging :as log])
  (:import
    [java.io File FileInputStream]
    [java.nio.file Files StandardCopyOption FileVisitOption OpenOption]
    [java.security MessageDigest]))

(defmacro with-let
  "Binds resource to binding and evaluates body. Then, returns resource. It's
  a cross between doto and with-open."
  [[binding resource] & body]
  `(let [ret# ~resource ~binding ret#] ~@body ret#))

(defn delete!
  "Recursively delete a directory and its contents"
  [f]
  (when (.isDirectory f)
    (doseq [child (seq (.listFiles f))] (delete! child)))
  (.delete f))

(defn- signature
  "Get signature (string) of a digest."
  [^MessageDigest algorithm]
  (let [size (* 2 (.getDigestLength algorithm))
        sig (.toString (BigInteger. 1 (.digest algorithm)) 16)
        padding (apply str (repeat (- size (count sig)) "0"))]
    (str padding sig)))

(defn md5
  "Get the MD5 hash of a file"
  [file]
  (with-open [fis (if (instance? File file)
                    (FileInputStream. file)
                    (Files/newInputStream file (into-array java.nio.file.OpenOption [])))]
    (let [md (MessageDigest/getInstance "MD5")
          buf (byte-array 1024)]
      (loop [n (.read fis buf)]
        (if (= -1 n)
          (signature md)
          (do
            (.update md buf 0 n)
            (recur (.read fis buf))))))))

(defn move
  [^File src ^File dest & {:keys [atomic replace]
                           :or {atomic  StandardCopyOption/ATOMIC_MOVE
                                replace StandardCopyOption/REPLACE_EXISTING}}]
  (let [opts (filter identity [atomic replace])
        opts-array (into-array StandardCopyOption opts)]
    (Files/move (.toPath src) (.toPath dest) opts-array)))

(defn hard-link
  [^File existing-file ^File link-file]
  (Files/deleteIfExists (.toPath link-file))
  (Files/createLink (.toPath link-file) (.toPath existing-file)))

(defn walk-file-tree
  "Wrap java.nio.Files/walkFileTree to easily toggle symlink-following behavior."
  [root visitor & {:keys [follow-symlinks]
                   :or   {follow-symlinks true}}]
  (let [walk-opts (if follow-symlinks #{FileVisitOption/FOLLOW_LINKS} #{})]
    (Files/walkFileTree root walk-opts Integer/MAX_VALUE visitor)))

(defn debug
  "Log debug using a formatted message"
  [msg & args]
  (log/debug (apply format msg args)))

(defn warn
  "Log a warning using a formatted message"
  [msg & args]
  (log/warn (apply format msg args)))