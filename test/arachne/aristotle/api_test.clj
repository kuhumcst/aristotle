(ns arachne.aristotle.api-test
  "The db of a Model or Dataset with its prefixes, transact and read, on an
  in-memory Model and on TDB2."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [arachne.aristotle.api :as api]
            [arachne.aristotle.impl.convert :as convert]
            [arachne.aristotle.txn :as txn]
            [arachne.aristotle.term :as term])
  (:import [clojure.lang ExceptionInfo]
           [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.apache.jena.graph Graph GraphMemFactory Node]
           [org.apache.jena.query Dataset]
           [org.apache.jena.rdf.model Model ModelFactory]
           [org.apache.jena.reasoner ReasonerRegistry]
           [org.apache.jena.riot RDFDataMgr]
           [org.apache.jena.tdb2 TDB2Factory]
           [org.apache.jena.tdb2.sys TDBInternal]))

(def prefixes
  "The default prefixes and one for the examples."
  (assoc term/default-prefixes :ex "<http://example.com/>"))

(defn graph-size
  "The number of triples in the default graph of `db`, or in the graph named
  by the IRI `iri`."
  ([db]
   (graph-size db nil))
  ([{:keys [^Model model ^Dataset dataset]} iri]
   (txn/calculate-read
     (or dataset model)
     #(.size (.getGraph (cond
                          model model
                          iri   (.getNamedModel dataset ^String iri)
                          :else (.getDefaultModel dataset)))))))

(defn temp-file
  "A temporary file with the `suffix` and the `content`."
  ^File [suffix content]
  (doto (File/createTempFile "aristotle" suffix)
    (.deleteOnExit)
    (spit content)))

(deftest db-prefixes
  (let [m (doto (ModelFactory/createDefaultModel)
            (.setNsPrefix "old" "http://old.example/"))]
    (api/db m)
    (is (= {"rdf"  "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            "rdfs" "http://www.w3.org/2000/01/rdf-schema#"
            "xsd"  "http://www.w3.org/2001/XMLSchema#"
            "owl"  "http://www.w3.org/2002/07/owl#"}
           (into {} (.getNsPrefixMap m)))
        "the default prefixes replace the old ones")
    (api/db m {:prefixes prefixes})
    (is (= "http://example.com/" (.getNsPrefixURI m "ex"))))
  (is (thrown-with-msg? ExceptionInfo #"Not a namespace IRI"
                        (api/db (ModelFactory/createDefaultModel)
                                {:prefixes {:ex "http://example.com/"}})))
  (is (thrown-with-msg? ExceptionInfo #"Not a Model, Dataset or Graph"
                        (api/db {})))
  (is (= "#object[arachne.aristotle.api.Db \"a Model with 4 prefixes\"]"
         (pr-str (api/db (ModelFactory/createDefaultModel))))
      "a db doesn't print the triples of its Model"))

(deftest tdb2-prefixes
  (let [dir     (str (Files/createTempDirectory "aristotle"
                                               (make-array FileAttribute 0)))
        release #(TDBInternal/expel (.asDatasetGraph ^Dataset %))
        stored  (fn [^Dataset ds prefix]
                  (txn/calculate-read
                    ds
                    #(.getNsPrefixURI (.getPrefixMapping ds) prefix)))]
    (try
      (let [ds (TDB2Factory/connectDataset ^String dir)]
        (api/db ds {:prefixes prefixes})
        (release ds))
      (let [ds (TDB2Factory/connectDataset ^String dir)]
        (is (= "http://example.com/" (stored ds "ex")) "written to disk")
        (api/db ds)
        (is (nil? (stored ds "ex")) "the prefixes passed in win")
        (release ds))
      (finally
        (doseq [f (reverse (file-seq (io/file dir)))]
          (io/delete-file f true))))))

(deftest inference-model-prefixes
  (let [ds  (TDB2Factory/createDataset)
        _   (api/db ds {:prefixes prefixes})
        inf (ModelFactory/createInfModel (ReasonerRegistry/getRDFSReasoner)
                                         (.getUnionModel ds))]
    (is (some? (api/db inf {:prefixes prefixes}))
        "an inference model on a TDB2 union model, as DanNet has")))

(deftest transact-model
  (let [m  (ModelFactory/createDefaultModel)
        db (api/db m {:prefixes prefixes})]
    (is (identical? db (api/transact
                         db
                         [[:ex/s :ex/p "o"]
                          [:ex/s :rdfs/label (term/->LangStr "hund" "da")]])))
    (is (= 2 (.size m)))
    (is (.contains (.getGraph m)
                   (convert/node :ex/s prefixes)
                   (convert/node :ex/p prefixes)
                   (convert/node "o" prefixes)))
    (is (thrown-with-msg? ExceptionInfo #"no named graphs"
                          (api/transact db [[:ex/s :ex/p "o"]] :ex/g)))))

(deftest transact-dataset
  (let [db (api/db (TDB2Factory/createDataset) {:prefixes prefixes})]
    (api/transact db [[:ex/s :ex/p "o"]])
    (api/transact db [[:ex/s :ex/p "o"] [:ex/s :ex/p "p"]] :ex/g)
    (is (= 1 (graph-size db)))
    (is (= 2 (graph-size db "http://example.com/g")))
    (testing "an error in the data leaves the db as it was"
      (is (thrown? ExceptionInfo
                   (api/transact db [[:ex/s :ex/p "q"]
                                     [:ex/s :ex/p (term/->LangStr "x" "en_US")]]
                                 :ex/g)))
      (is (= 2 (graph-size db "http://example.com/g"))))
    (is (thrown-with-msg? ExceptionInfo #"Not a triple"
                          (api/transact db [[:ex/s :ex/p]])))
    (is (thrown-with-msg? ExceptionInfo #"Not a graph name"
                          (api/transact db [[:ex/s :ex/p "o"]] "g")))))

(deftest graph-and-dataset-graph
  (let [g  (GraphMemFactory/createDefaultGraph)
        db (api/db g {:prefixes prefixes})]
    (api/transact db [[:ex/s :ex/p "o"]])
    (is (= 1 (.size g)) "a Graph, through a Model of it")
    (is (= "http://example.com/" (.getNsPrefixURI (.getPrefixMapping g) "ex"))))
  (let [ds (TDB2Factory/createDataset)
        g  (.getGraph (.getDefaultModel ds))]
    (api/transact (api/db g {:prefixes prefixes}) [[:ex/s :ex/p "o"]])
    (is (= 1 (graph-size (api/db ds {:prefixes prefixes})))
        "a graph of a TDB2 dataset, in a transaction of the dataset"))
  (let [ds (TDB2Factory/createDataset)
        db (api/db (.asDatasetGraph ds) {:prefixes prefixes})]
    (api/transact db [[:ex/s :ex/p "o"]] :ex/g)
    (is (= 1 (graph-size db "http://example.com/g"))
        "a DatasetGraph, through a Dataset of it")))

(deftest transact-blank-nodes
  (let [m  (ModelFactory/createDefaultModel)
        db (api/db m {:prefixes prefixes})
        node #(convert/node % prefixes)]
    (api/transact db [[:ex/s :ex/p '_b1] ['_b1 :ex/q "x"]])
    (let [^Graph g (.getGraph m)
          b        (.getObject (.next (.find g (node :ex/s) (node :ex/p)
                                             Node/ANY)))]
      (is (.isBlank ^Node b))
      (is (.contains g b (node :ex/q) (node "x"))
          "one label is one blank node across the triples"))))

(deftest read-files
  (let [db  (api/db (TDB2Factory/createDataset) {:prefixes prefixes})
        n3  (.size (RDFDataMgr/loadGraph "test/TheFirm.n3"))]
    (api/read db (io/file "test/foaf.rdf") :ex/foaf)
    (api/read db "test/TheFirm.n3" :ex/firm)
    (api/read db (io/resource "TheFirm.n3") :ex/firm-resource)
    (is (pos? (graph-size db "http://example.com/foaf")) "RDF/XML")
    (is (= n3 (graph-size db "http://example.com/firm")) "N3 from a path")
    (is (= n3 (graph-size db "http://example.com/firm-resource"))
        "N3 from a resource, which may be in a jar")
    (is (zero? (graph-size db)))
    (is (thrown-with-msg? ExceptionInfo #"No RDF syntax"
                          (api/read db (temp-file ".txt" ""))))
    (is (thrown-with-msg? ExceptionInfo #"Not a syntax for one graph"
                          (api/read db (temp-file ".nq" ""))))
    (testing "a syntax error leaves the db as it was"
      (let [ttl (temp-file ".ttl" (str "<http://example.com/s> "
                                       "<http://example.com/p> \"o\" .\n"
                                       "<http://example.com/s> ."))]
        (is (thrown? Exception (api/read db ttl :ex/broken))))
      (is (zero? (graph-size db "http://example.com/broken"))))))
