(ns dk.cst.theophrastus.tdb2
  "TDB2 databases on disk: connecting, compacting and backing up."
  (:require [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.txn :as txn])
  (:import [org.apache.jena.query Dataset]
           [org.apache.jena.sparql.core DatasetGraph]
           [org.apache.jena.tdb2 DatabaseMgr TDB2Factory]))

(defn connect
  "A db of the TDB2 database in the directory `dir`, with the `opts` of
  theo/db. TDB2 creates the database when the directory doesn't exist."
  ([dir]
   (connect dir nil))
  ([dir opts]
   (theo/db (TDB2Factory/connectDataset (str dir)) opts)))

(defn dataset-graph
  "The TDB2 DatasetGraph of `db` (which may be a view of one of its graphs),
  e.g. for Jena's DatabaseMgr. It throws when `db` isn't a db of TDB2."
  ^DatasetGraph [db]
  (let [t   (txn/transactional db)
        dsg (if (instance? Dataset t) (.asDatasetGraph ^Dataset t) t)]
    (if (and dsg (DatabaseMgr/isTDB2 ^DatasetGraph dsg))
      dsg
      (throw (ex-info "Not a db of a TDB2 database" {:db db})))))

(defn compact!
  "Compact the TDB2 database of `db` (which may be a view of one of its
  graphs), and delete the generation it replaces. TDB2 only frees the space
  of deleted and changed data when it compacts."
  [db]
  (DatabaseMgr/compact (dataset-graph db) true))

(defn backup
  "Back up the TDB2 database of `db` (which may be a view of one of its
  graphs) as gzipped N-Quads in its Backups directory, and return the path
  of the file."
  [db]
  (DatabaseMgr/backup (dataset-graph db)))
