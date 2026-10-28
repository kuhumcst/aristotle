(ns dk.cst.theophrastus.tdb2
  "TDB2 databases on disk: connecting and compacting."
  (:require [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.txn :as txn])
  (:import [org.apache.jena.query Dataset]
           [org.apache.jena.sparql.core DatasetGraph]
           [org.apache.jena.tdb2 DatabaseMgr TDB2Factory]))

(defn connect
  "The db of the TDB2 database in the directory `dir`, which TDB2 creates
  when it doesn't exist, with the `opts` of theophrastus/db."
  ([dir]
   (connect dir nil))
  ([dir opts]
   (theo/db (TDB2Factory/connectDataset (str dir)) opts)))

(defn compact!
  "Compact the TDB2 database of `db`, which may be a view of one of its
  graphs, and delete the generation it replaces. TDB2 only frees the space
  of deleted and changed data when it compacts."
  [{:keys [model dataset] :as db}]
  (let [t   (txn/transactional (or dataset model))
        dsg (if (instance? Dataset t) (.asDatasetGraph ^Dataset t) t)]
    (if (and dsg (DatabaseMgr/isTDB2 ^DatasetGraph dsg))
      (DatabaseMgr/compact dsg true)
      (throw (ex-info "Not a db of a TDB2 database" {:db db})))))
