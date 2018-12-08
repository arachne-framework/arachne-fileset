(ns arachne.fileset.specs
  (:require [clojure.spec.alpha :as s]
            [arachne.fileset.impl :as impl])
  (:import [java.io File]))

;; Need to override specs here so it doesn't try to gen when I instrument
(s/def ::any-fn (partial instance? clojure.lang.IFn))

(s/def ::directory (fn [f]
                     (and (instance? File f)
                          (.isDirectory ^File f))))

(s/def ::fileset (partial satisfies? impl/ITmpFileSet))

(s/fdef arachne.fileset/fileset
  :ret ::fileset )

(s/fdef arachne.fileset/commit!
  :args (s/cat :fileset ::fileset :output-directory ::directory)
  :ret ::fileset
  :fn (fn [{[fs _] :args ret :ret}]
        (= fs ret)))

(s/def ::regex (partial instance? java.util.regex.Pattern))

(s/def ::include (s/coll-of ::regex :min-count 1))
(s/def ::exclude (s/coll-of ::regex :min-count 1))

#_(s/def ::merge-fn
  (s/fspec :args (s/cat :old (partial instance? java.io.InputStream)
                        :new (partial instance? java.io.InputStream)
                        :out (partial instance? java.io.OutputStream))
           :ret nil?))

(s/def ::merge-fn ::any-fn)

(s/def ::mergers (s/map-of ::regex ::merge-fn :min-count 1))

(s/def ::meta (s/map-of keyword? any? :min-count 1))

(s/fdef arachne.fileset/add
  :args (s/cat :fileset ::fileset
               :directory ::directory
               :options (s/keys* :opt-un [::include ::exclude ::mergers ::meta]))
  :ret ::fileset)

(s/def ::path string?)
(s/def ::tmpfile (partial satisfies? impl/ITmpFile))

(s/fdef arachne.fileset/remove
  :args (s/cat :fileset ::fileset
               :paths (s/+ ::path))
  :ret ::fileset)

(s/fdef arachne.fileset/diff
  :args (s/cat :before ::fileset
               :after ::fileset)
  :ret ::fileset)

(s/fdef arachne.fileset/removed
  :args (s/cat :before ::fileset
               :after ::fileset)
  :ret ::fileset)

(s/fdef arachne.fileset/added
  :args (s/cat :before ::fileset
               :after ::fileset)
  :ret ::fileset)

(s/fdef arachne.fileset/changed
  :args (s/cat :before ::fileset
               :after ::fileset)
  :ret ::fileset)

#_(s/def ::filter-pred
  (s/fspec :args (s/cat :tmpfile ::tmpfile)
           :ret any?))

(s/def ::filter-pred ::any-fn)

(s/fdef arachne.fileset/filter
  :args (s/cat :fileset ::fileset
               :pred ::filter-pred)
  :ret ::fileset)

(s/fdef arachne.fileset/filter-by-meta
  :args (s/cat :fileset ::fileset
               :pred ::filter-pred)
  :ret ::fileset)

(s/fdef arachne.fileset/ls
  :args (s/cat :fileset ::fileset)
  :ret (s/coll-of ::path))

(s/fdef arachne.fileset/hash
  :args (s/cat :fileset ::fileset
               :path ::path)
  :ret string?)

(s/fdef arachne.fileset/timestamp
  :args (s/cat :fileset ::fileset
               :path ::path)
  :ret integer?)

(s/fdef arachne.fileset/content
  :args (s/cat :fileset ::fileset
               :path ::path)
  :ret (partial instance? java.io.InputStream))

(s/fdef arachne.fileset/empty
  :args (s/cat :fileset ::fileset)
  :ret ::fileset)

(s/fdef arachne.fileset/merge
  :args (s/cat :filesets (s/+ ::fileset))
  :ret ::fileset)
