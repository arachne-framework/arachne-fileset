(ns arachne.fileset-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [arachne.fileset :as fs]
            [arachne.fileset.specs :as fss]
            [arachne.fileset.util :as fsutil])
  (:import [org.apache.commons.io FileUtils]
           [java.nio.file Files Paths]))

(comment

  (def fs (fs/fileset))
  (def f1 (fs/add fs (io/file "test/test-assets")))
  (def f1 nil)
  (System/gc)

  )

(deftest test-basic-add-update-commit
  (let [fs (fs/fileset)
        fs (fs/add fs (io/file "test/test-assets"))
        working (fs/tmpdir!)
        commit-dir (fs/tmpdir!)
        fs (fs/commit! fs commit-dir)]
    (FileUtils/copyDirectory commit-dir working)
    (spit (io/file working "file1.md") "NEW CONTENT")
    (spit (io/file working "dir1/file4.md") "NEW FILE")
    (let [fs (fs/add fs working)
          dest (fs/tmpdir!)
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
        dest (fs/tmpdir!)
        fs (fs/commit! fs dest)
        files (->> (file-seq dest)
                (filter #(.isFile %)))]
    (is (= #{"file1.md" "file2.md"}
          (set (map #(.getName %) files))))))

(deftest test-diffs
  (let [fs (fs/fileset)
        fs (fs/add fs (io/file "test/test-assets"))
        commit-dir (fs/tmpdir!)
        working-dir (fs/tmpdir!)
        fs (fs/commit! fs commit-dir)]
    (FileUtils/copyDirectory commit-dir working-dir)
    (spit (io/file working-dir "file1.md") "NEW CONTENT")
    (spit (io/file working-dir "dir1/file4.md") "NEW FILE")
    (.delete (io/file working-dir "file2.md"))
    (let [fs2 (fs/add fs working-dir)
          fs2 (fs/remove fs2 "dir1/file3.md")]
      (is (= #{"file1.md" "dir1/file4.md"}
            (set (fs/ls (fs/diff fs fs2)))))
      (is (= #{"dir1/file4.md"}
            (set (fs/ls (fs/added fs fs2)))))
      (is (= #{"dir1/file3.md"}
            (set (fs/ls (fs/removed fs fs2)))))
      (is (= #{"file1.md"}
            (set (fs/ls (fs/changed fs fs2))))))))

(deftest test-filtering-and-meta
  (let [fs (fs/fileset)
        fs (fs/add fs (io/file "test/test-assets") :meta {:input true})
        working (fs/tmpdir!)
        fs (fs/commit! fs working)]
    (.mkdirs (io/file working "out"))
    (spit (io/file working "out/file1.out") "OUTPUT1")
    (spit (io/file working "out/file2.out") "OUTPUT2")
    (let [fs (fs/add fs working :include [#"\.out$"] :meta {:output true})
          dest (fs/tmpdir!)
          out-fs (fs/filter-by-meta fs :output)
          out-fs (fs/commit! out-fs dest)
          files (->> (file-seq dest)
                  (filter #(.isFile %)))]
      (is (= #{"file1.out" "file2.out"}
            (set (map #(.getName %) files)))))))

(deftest test-file-access
  (let [fs (fs/fileset)
        fs (fs/add fs (io/file "test/test-assets"))]
    (let [f (io/file "test/test-assets/file1.md")]
      ;; Files/getLastModifiedTime returns a higher resolution value
      ;; on linux than File/lastModified.
      (is (= (.toMillis (Files/getLastModifiedTime (.toPath f)
                          arachne.fileset.impl/link-opts))
            (fs/timestamp fs "file1.md")))
      (is (= (fsutil/md5 f) (fs/hash fs "file1.md")))
      (is (= (slurp f) (slurp (fs/content fs "file1.md")))))))

(deftest test-checksums
  (let [original-fs (fs/add (fs/fileset) (io/file "test/test-assets"))
        commit-dir (fs/tmpdir!)
        working-dir (fs/tmpdir!)
        _ (fs/commit! original-fs commit-dir)
        _ (FileUtils/copyDirectory commit-dir working-dir false)
        fs (fs/add (fs/fileset) (io/file working-dir))
        fs' (fs/add (fs/fileset) (io/file working-dir))
        _ (.setLastModified (io/file working-dir "file1.md") 0)
        fs'' (fs/add (fs/fileset) (io/file working-dir))
        _ (spit (io/file working-dir "file1.md") "boo")
        fs''' (fs/add (fs/fileset) (io/file working-dir))]
    (testing "checksums not including timestamps"
      (is (= (fs/checksum fs false)
             (fs/checksum fs' false)
             (fs/checksum fs'' false)))
      (is (not= (fs/checksum fs'' false)
                (fs/checksum fs''' false))))
    (testing "checksums including timestamps"
      (is (= (fs/checksum fs true)
             (fs/checksum fs' true)))
      (is (not= (fs/checksum fs true)
                (fs/checksum fs'' true))))))

(deftest test-date-preservation
  (let [fs (fs/add (fs/fileset) (io/file "test/test-assets"))
        tmpdir (fs/tmpdir!)]
    (fs/commit! fs tmpdir)
    (is (= (.lastModified (io/file tmpdir "file1.md"))
           (.lastModified (io/file "test/test-assets/file1.md"))))))

(deftest test-content
  (let [fs (fs/add (fs/fileset) (io/file "test/test-assets"))]
    (is (= "this is a file" (slurp (fs/content fs "file1.md"))))
    (is (nil? (fs/content fs "no-such-file.md")))))

(deftest test-file
  (let [fs (fs/add (fs/fileset) (io/file "test/test-assets"))]
    (is (= "this is a file" (slurp (fs/file fs "file1.md"))))
    (is (nil? (fs/file fs "no-such-file.md")))))

(deftest test-nio-paths
  (let [fs (fs/add (fs/fileset) (Paths/get "test/test-assets" (into-array String [])))]
    (is (= "this is a file" (slurp (fs/file fs "file1.md"))))
    (is (nil? (fs/file fs "no-such-file.md")))))

(deftest test-deletion-recovery
  ;; Handles a regression where temp files could be deleted out from
  ;; under long-running processes
  (let [fs (fs/add (fs/fileset) (io/file "test/test-assets"))]
    (is (= "this is a file" (slurp (fs/content fs "file1.md"))))
    (let [f (fs/file fs "file1.md")]
      (.delete f)
      (is (not (.exists f))))
    (is (.exists (fs/file fs "file1.md")))
    (is (= "this is a file" (slurp (fs/content fs "file1.md"))))))

(comment
  (def fs (fs/fileset))

  (def fs1 (fs/add fs (io/file "test/test-assets")))
  (def fs1 nil)

  (System/gc)


  )
