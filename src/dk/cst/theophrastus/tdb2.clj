(ns dk.cst.theophrastus.tdb2
  "TDB2 databases on disk: connecting and compacting."
  (:require [dk.cst.theophrastus :as theo])
  (:import [org.apache.jena.query Dataset]
           [org.apache.jena.tdb2 DatabaseMgr TDB2Factory]))

(defn connect
  "The db of the TDB2 database in the directory `dir`, which TDB2 creates
  when it doesn't exist, with the `opts` of theophrastus/db."
  ([dir]
   (connect dir nil))
  ([dir opts]
   (theo/db (TDB2Factory/connectDataset (str dir)) opts)))

(defn compact!
  "Compact the TDB2 database of `x`, a db or a Dataset, and delete the
  generation it replaces. TDB2 only frees the space of deleted and changed
  data when it compacts."
  [x]
  (let [^Dataset ds (if (instance? Dataset x) x (:dataset x))]
    (DatabaseMgr/compact (.asDatasetGraph ds) true)))
