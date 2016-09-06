(ns arachne.fileset-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.spec :as s]
            [clojure.spec.test :as stest]
            [arachne.fileset :as fs]
            [arachne.fileset.tmpdir :as tmpdir]
            [arachne.fileset.specs :as fss])
  (:import [org.apache.commons.io FileUtils]))


(stest/instrument)

(deftest test-basic-add-update-commit
  (let [fs (fs/fileset)
        fs (fs/add fs (io/file "test/test-assets"))
        working (tmpdir/tmpdir!)
        commit-dir (tmpdir/tmpdir!)
        fs (fs/commit! fs commit-dir)]
    (FileUtils/copyDirectory commit-dir working)
    (spit (io/file working "file1.md") "NEW CONTENT")
    (spit (io/file working "dir1/file4.md") "NEW FILE")
    (let [fs (fs/add fs working)
          dest (tmpdir/tmpdir!)
          fs (fs/commit! fs dest)
          files (->> (file-seq dest)
                  (filter #(.isFile %)))]
      (is (= "NEW CONTENT" (slurp (io/file dest "file1.md"))))
      (is (= #{"file1.md" "file2.md" "file3.md" "file4.md"}
             (set (map #(.getName %) files)))))))

(deftest test-remove-test
  (let [fs (fs/fileset)
        fs (fs/add fs (io/file "test/test-assets"))
        fs (fs/remove fs "dir1/file3.md")
        dest (tmpdir/tmpdir!)
        fs (fs/commit! fs dest)
        files (->> (file-seq dest)
                (filter #(.isFile %)))]
    (is (= #{"file1.md" "file2.md"}
          (set (map #(.getName %) files))))))

(deftest test-diffs
  (let [fs (fs/fileset)
        fs (fs/add fs (io/file "test/test-assets"))
        commit-dir (tmpdir/tmpdir!)
        working-dir (tmpdir/tmpdir!)
        fs (fs/commit! fs commit-dir)]
    (FileUtils/copyDirectory commit-dir working-dir)
    (spit (io/file working-dir "file1.md") "NEW CONTENT")
    (spit (io/file working-dir "dir1/file4.md") "NEW FILE")
    (.delete (io/file working-dir "file2.md"))
    (let [fs2 (fs/add fs working-dir)
          fs2 (fs/remove fs2 "dir1/file3.md")]
      (is (= #{"file1.md" "dir1/file4.md"}
            (set (map :path (fs/ls (fs/diff fs fs2))))))
      (is (= #{"dir1/file4.md"}
            (set (map :path (fs/ls (fs/added fs fs2))))))
      (is (= #{"dir1/file3.md"}
            (set (map :path (fs/ls (fs/removed fs fs2))))))
      (is (= #{"file1.md"}
            (set (map :path (fs/ls (fs/changed fs fs2)))))))))

(deftest test-filtering-and-meta
  (let [fs (fs/fileset)
        fs (fs/add fs (io/file "test/test-assets") :meta {:input true})
        working (tmpdir/tmpdir!)
        fs (fs/commit! fs working)]
    (.mkdirs (io/file working "out"))
    (spit (io/file working "out/file1.out") "OUTPUT1")
    (spit (io/file working "out/file2.out") "OUTPUT2")
    (let [fs (fs/add fs working :include [#"\.out$"] :meta {:output true})
          dest (tmpdir/tmpdir!)
          out-fs (fs/filter-by-meta fs :output)
          out-fs (fs/commit! out-fs dest)
          files (->> (file-seq dest)
                  (filter #(.isFile %)))]
      (is (= #{"file1.out" "file2.out"}
            (set (map #(.getName %) files)))))))

(deftest test-caching
  (let [cache (tmpdir/tmpdir!)
        fs-a (fs/fileset cache)
        fs-b (fs/fileset cache)
        invocations (atom 0)
        cachefn (fn [dir]
                  (swap! invocations inc)
                  (spit (io/file dir "file.out") "OUTPUT"))
        fs-a (fs/add-cached fs-a "aaa" cachefn)
        fs-b (fs/add-cached fs-b "aaa" cachefn)
        dest-a (tmpdir/tmpdir!)
        dest-b (tmpdir/tmpdir!)]
    (fs/commit! fs-a dest-a)
    (fs/commit! fs-b dest-b)
    (is (= "OUTPUT" (slurp (io/file dest-a "file.out"))))
    (is (= "OUTPUT" (slurp (io/file dest-b "file.out"))))
    (is (= 1 @invocations))))