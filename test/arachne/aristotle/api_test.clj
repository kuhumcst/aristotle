(ns arachne.aristotle.api-test
  "The db of a Model or Dataset with its prefixes, transact and read, on an
  in-memory Model and on TDB2."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [arachne.aristotle.api :as api]
            [arachne.aristotle.impl.convert :as convert]
            [arachne.aristotle.txn :as txn]
            [arachne.aristotle.term :as term])
  (:import [arachne.aristotle.api Db]
           [clojure.lang ExceptionInfo]
           [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.apache.jena.graph Graph GraphMemFactory Node]
           [org.apache.jena.query Dataset Query QueryCancelledException
            QueryParseException]
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

(def label-data
  "The triples of the pinned query tests, which DanNet's queries resemble."
  [[:ex/a :rdfs/label (term/->LangStr "hund" "da")]
   [:ex/a :ex/count 2]
   [:ex/a :ex/part '_part]
   ['_part :rdfs/label "del"]
   [:ex/b :rdfs/label (term/->LangStr "dog" "en")]
   [:ex/c :rdfs/label "kat"]])

(defn label-dbs
  "A db of an in-memory Model and one of TDB2, both with the label data."
  []
  [(api/transact (api/db (ModelFactory/createDefaultModel) {:prefixes prefixes})
                 label-data)
   (api/transact (api/db (TDB2Factory/createDataset) {:prefixes prefixes})
                 label-data)])

(deftest query
  (is (instance? Query (api/query "SELECT * WHERE { ?s rdfs:label ?l }"))
      "the default prefixes need no PREFIX line")
  (is (instance? Query (api/query "SELECT * WHERE { ?s ex:p ?o }" prefixes)))
  (is (thrown? QueryParseException
               (api/query "SELECT * WHERE { ?s ex:p ?o }"))
      "ex isn't one of the default prefixes"))

(deftest select
  (doseq [db (label-dbs)]
    (is (= #{{'?s :ex/a '?l (term/->LangStr "hund" "da")}
             {'?s :ex/b '?l (term/->LangStr "dog" "en")}
             {'?s :ex/c '?l "kat"}
             {'?s '_part '?l "del"}}
           (set (api/q "SELECT * WHERE { ?s rdfs:label ?l }" db))))
    (is (= [{'?s :ex/a}] (api/q (api/query "SELECT ?s WHERE { ?s ex:count 2 }"
                                           prefixes)
                                db))
        "a Query, and an integer in SPARQL matching a Clojure integer")))

(deftest bindings
  (doseq [db (label-dbs)
          :let [labels #(api/q "SELECT * WHERE { ?s rdfs:label ?l }" db %)]]
    (is (= [{'?s :ex/b '?l (term/->LangStr "dog" "en")}] (labels {'?s :ex/b}))
        "one value, kept in the rows")
    (is (= [{'?s :ex/a '?l (term/->LangStr "hund" "da")}]
           (labels {'?l (term/->LangStr "hund" "da")})))
    (is (= 2 (count (labels {'?s [:ex/a :ex/b]}))) "several values")
    (is (= [{'?s :ex/b '?l (term/->LangStr "dog" "en")}]
           (labels {'?s [:ex/a :ex/b]
                    '?l ["kat" (term/->LangStr "dog" "en")]}))
        "several variables with several values each")
    (is (= #{:ex/a :ex/c} (set (map '?s (labels {'[?s ?l] [[:ex/a nil]
                                                           [nil "kat"]]}))))
        "rows of values, with nil for unbound")
    (is (= [{'?l "kat"}]
           (api/q "SELECT ?l WHERE { ?s rdfs:label ?l }" db {'?s :ex/c}))
        "the SELECT decides which variables come back")
    (is (= [{'?n 2}]
           (api/q "SELECT (COUNT(*) AS ?n) WHERE { ?s rdfs:label ?l }"
                  db {'?s [:ex/a :ex/c]}))
        "the values limit what the count counts")
    (let [b (get (first (api/q "SELECT ?b WHERE { ex:a ex:part ?b }" db)) '?b)]
      (is (= [{'?b b '?l "del"}]
             (api/q "SELECT * WHERE { ?b rdfs:label ?l }" db {'?b b}))
          "a blank node from one result finds its triples in the next"))
    (is (thrown-with-msg? ExceptionInfo #"Not a variable"
                          (labels {'s :ex/a})))))

(deftest values-as-nodes
  (let [db   (api/db (TDB2Factory/createDataset) {:prefixes prefixes})
        evil "x\" } ; DROP ALL ; SELECT * { ?s ?p \""]
    (api/transact db [[:ex/s :ex/p evil]])
    (is (= [{'?s :ex/s}] (api/q "SELECT ?s WHERE { ?s ex:p ?o }" db {'?o evil}))
        "a value goes in as a node, never as SPARQL text")))

(deftest ask-construct-describe
  (doseq [db (label-dbs)]
    (is (true? (api/q "ASK { ex:a ex:count 2 }" db)))
    (is (false? (api/q "ASK { ex:a ex:count 3 }" db)))
    (is (true? (api/q "ASK { ?s ex:count ?n }" db {'?s :ex/a})))
    (let [result (api/q "CONSTRUCT { ?s ex:name ?l } WHERE { ?s rdfs:label ?l }"
                        db)]
      (is (instance? Db result))
      (is (= (:prefixes db) (:prefixes result)))
      (is (= #{"kat" "del"
               (term/->LangStr "hund" "da") (term/->LangStr "dog" "en")}
             (set (map '?l (api/q "SELECT ?l WHERE { ?s ex:name ?l }" result))))
          "a CONSTRUCT returns a db that can be queried"))
    (is (= 4 (.size ^Model (:model (api/q "DESCRIBE ex:a" db))))
        "Jena describes ex:a with the triples of its blank node too")))

(deftest timeout
  (let [db (api/db (ModelFactory/createDefaultModel) {:prefixes prefixes})]
    (api/transact db (for [i (range 100)] [:ex/s :ex/p i]))
    (is (= 100 (count (api/q "SELECT * WHERE { ?s ?p ?o }" db nil
                             {:timeout 10000}))))
    (is (thrown? QueryCancelledException
                 (api/q "SELECT * WHERE { ?a ?p ?w . ?b ?p ?x . ?c ?p ?y }"
                        db nil {:timeout 50}))
        "a million rows take longer than 50 ms")))
