(ns dk.cst.theophrastus.tdb2-test
  "Connecting to a TDB2 database on disk, and compacting it."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.tdb2 :as tdb2]
            [dk.cst.theophrastus.term :as term])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.apache.jena.query Dataset]
           [org.apache.jena.tdb2.sys TDBInternal]))

(def prefixes
  "The default prefixes and one for the examples."
  (assoc term/default-prefixes :ex "<http://example.com/>"))

(defn release
  "Release the TDB2 database of `db`, so that it can be opened again."
  [db]
  (TDBInternal/expel (.asDatasetGraph ^Dataset (:dataset db))))

(deftest connect-and-compact
  (let [dir (str (Files/createTempDirectory "theophrastus"
                                            (make-array FileAttribute 0)))]
    (try
      (let [db (tdb2/connect dir {:prefixes prefixes})]
        (theo/transact! db [[:ex/s :ex/p "o"]])
        (release db))
      (let [db (tdb2/connect dir {:prefixes prefixes})]
        (is (= {:ex/p "o"} (theo/entity db :ex/s)) "the data is on disk")
        (theo/retract! db ['_ '_ '_])
        (theo/transact! db [[:ex/s :ex/p "new"]])
        (tdb2/compact! db)
        (is (= {:ex/p "new"} (theo/entity db :ex/s))
            "the data survives compacting")
        (is (.exists (io/file dir "Data-0002")))
        (is (not (.exists (io/file dir "Data-0001")))
            "the generation it replaces is deleted")
        (tdb2/compact! (:dataset db))
        (is (.exists (io/file dir "Data-0003")) "a Dataset works too")
        (release db))
      (finally
        (doseq [f (reverse (file-seq (io/file dir)))]
          (io/delete-file f true))))))
