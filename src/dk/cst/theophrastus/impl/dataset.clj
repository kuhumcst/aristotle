(ns dk.cst.theophrastus.impl.dataset
  "The default graph of a db, which its :default-graph picks, and the
  DatasetGraph that a query or update on the db runs on."
  (:import [java.util Collection]
           [org.apache.jena.graph Graph NodeFactory]
           [org.apache.jena.query Dataset]
           [org.apache.jena.rdf.model Model]
           [org.apache.jena.sparql.core DatasetGraph DatasetGraphFactory]
           [org.apache.jena.sparql.graph GraphUnionRead]
           [org.apache.jena.tdb2 DatabaseMgr]))

(defn default-graph
  "The default Graph of `db`: the graph of its Model, or the graph of its
  Dataset that its :default-graph picks. The union of several graphs can
  only be read."
  ^Graph [{:keys [^Model model ^Dataset dataset] :as db}]
  (if model
    (.getGraph model)
    (let [dsg (.asDatasetGraph dataset)
          x   (:default-graph db)]
      (cond
        (nil? x)     (.getDefaultGraph dsg)
        (= :union x) (.getUnionGraph dsg)
        (set? x)     (GraphUnionRead.
                       dsg
                       ^Collection (mapv #(NodeFactory/createURI ^String %) x))
        :else        (.getGraph dsg (NodeFactory/createURI ^String x))))))

;; TDB2's own union default graph runs a query on its indexes, which is
;; faster than a query on a view of the union
(defn tdb2-union?
  "Whether a query on `db` reads the union of its named graphs with TDB2's
  own union default graph."
  [{:keys [^Dataset dataset default-graph]}]
  (and (= :union default-graph)
       (DatabaseMgr/isTDB2 (.asDatasetGraph dataset))))

(defn dataset-graph
  "The DatasetGraph that a query or update on `db` runs on: one of the graph
  of its Model, its Dataset, or a view of the Dataset whose default graph is
  the one that :default-graph picks.

  The view links the named graphs that the Dataset has when it's made, so
  make it in the transaction that runs the query or update."
  ^DatasetGraph [{:keys [^Model model ^Dataset dataset] :as db}]
  (cond
    model
    (DatasetGraphFactory/wrap (.getGraph model))

    (nil? (:default-graph db))
    (.asDatasetGraph dataset)

    :else
    (let [dsg  (.asDatasetGraph dataset)
          view (DatasetGraphFactory/createGeneral (default-graph db))]
      (doseq [n (iterator-seq (.listGraphNodes dsg))]
        (.addGraph view n (.getGraph dsg n)))
      view)))
