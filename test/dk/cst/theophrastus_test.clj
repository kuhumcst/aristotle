(ns dk.cst.theophrastus-test
  "The db of a Model or Dataset with its prefixes, transact! and read!, on an
  in-memory Model and on TDB2."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.convert :as convert]
            [dk.cst.theophrastus.txn :as txn]
            [dk.cst.theophrastus.term :as term])
  (:import [dk.cst.theophrastus Db]
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

(defn without-blank-labels
  "The entity `e` without the :rdf/about of its blank nodes, whose labels
  differ from store to store."
  [e]
  (walk/postwalk #(if (and (map? %) (term/blank-node? (:rdf/about %)))
                    (dissoc % :rdf/about)
                    %)
                 e))

(defn temp-file
  "A temporary file with the `suffix` and the `content`."
  ^File [suffix content]
  (doto (File/createTempFile "theophrastus" suffix)
    (.deleteOnExit)
    (spit content)))

(deftest db-prefixes
  (let [m (doto (ModelFactory/createDefaultModel)
            (.setNsPrefix "old" "http://old.example/"))]
    (theo/db m)
    (is (= {"rdf"  "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            "rdfs" "http://www.w3.org/2000/01/rdf-schema#"
            "xsd"  "http://www.w3.org/2001/XMLSchema#"
            "owl"  "http://www.w3.org/2002/07/owl#"}
           (into {} (.getNsPrefixMap m)))
        "the default prefixes replace the old ones")
    (theo/db m {:prefixes prefixes})
    (is (= "http://example.com/" (.getNsPrefixURI m "ex"))))
  (is (thrown-with-msg? ExceptionInfo #"Not a namespace IRI"
                        (theo/db (ModelFactory/createDefaultModel)
                                {:prefixes {:ex "http://example.com/"}})))
  (is (thrown-with-msg? ExceptionInfo #"Not a Model, Dataset or Graph"
                        (theo/db (theo/db)))
      "a db is a map, but not one of options")
  (is (= "#object[dk.cst.theophrastus.Db \"a Model with 4 prefixes\"]"
         (pr-str (theo/db (ModelFactory/createDefaultModel))))
      "a db doesn't print the triples of its Model"))

(deftest in-memory-db
  (is (= term/default-prefixes (:prefixes (theo/db))))
  (let [db (theo/db {:prefixes prefixes})]
    (is (instance? Dataset (:dataset db)))
    (theo/transact! db [[:ex/s :ex/p "o"]])
    (is (thrown? ExceptionInfo
                 (theo/transact! db [[:ex/s :ex/p "q"]
                                   [:ex/s :ex/p (term/->LangString "x" "en_US")]])))
    (is (= 1 (graph-size db)) "a failed write is undone")))

(deftest tdb2-prefixes
  (let [dir     (str (Files/createTempDirectory "theophrastus"
                                               (make-array FileAttribute 0)))
        release #(TDBInternal/expel (.asDatasetGraph ^Dataset %))
        stored  (fn [^Dataset ds prefix]
                  (txn/calculate-read
                    ds
                    #(.getNsPrefixURI (.getPrefixMapping ds) prefix)))]
    (try
      (let [ds (TDB2Factory/connectDataset ^String dir)]
        (theo/db ds {:prefixes prefixes})
        (release ds))
      (let [ds (TDB2Factory/connectDataset ^String dir)]
        (is (= "http://example.com/" (stored ds "ex")) "written to disk")
        (theo/db ds)
        (is (nil? (stored ds "ex")) "the prefixes passed in win")
        (release ds))
      (finally
        (doseq [f (reverse (file-seq (io/file dir)))]
          (io/delete-file f true))))))

(deftest inference-model-prefixes
  (let [ds  (TDB2Factory/createDataset)
        _   (theo/db ds {:prefixes prefixes})
        inf (ModelFactory/createInfModel (ReasonerRegistry/getRDFSReasoner)
                                         (.getUnionModel ds))]
    (is (some? (theo/db inf {:prefixes prefixes}))
        "an inference model on the union model of a TDB2 dataset")))

(deftest transact-model
  (let [m  (ModelFactory/createDefaultModel)
        db (theo/db m {:prefixes prefixes})]
    (is (identical? db (theo/transact!
                         db
                         [[:ex/s :ex/p "o"]
                          [:ex/s :rdfs/label (term/->LangString "hund" "da")]])))
    (is (= 2 (.size m)))
    (is (.contains (.getGraph m)
                   (convert/node :ex/s prefixes)
                   (convert/node :ex/p prefixes)
                   (convert/node "o" prefixes)))))

(deftest transact-dataset
  (let [db (theo/db (TDB2Factory/createDataset) {:prefixes prefixes})
        g  (theo/named-graph db :ex/g)]
    (theo/transact! db [[:ex/s :ex/p "o"]])
    (theo/transact! g [[:ex/s :ex/p "o"] [:ex/s :ex/p "p"]])
    (is (= 1 (graph-size db)))
    (is (= 2 (graph-size db "http://example.com/g")))
    (testing "an error in the data leaves the db as it was"
      (is (thrown? ExceptionInfo
                   (theo/transact! g [[:ex/s :ex/p "q"]
                                      [:ex/s :ex/p
                                       (term/->LangString "x" "en_US")]])))
      (is (= 2 (graph-size db "http://example.com/g"))))
    (is (thrown-with-msg? ExceptionInfo #"Not a triple"
                          (theo/transact! db [[:ex/s :ex/p]])))))

(deftest graph-views
  (doseq [db [(theo/db (TDB2Factory/createDataset) {:prefixes prefixes})
              (theo/db {:prefixes prefixes})]
          :let [g     (theo/named-graph db :ex/g)
                h     (theo/named-graph db "<http://example.com/h>")
                union (theo/union-graph db)]]
    (theo/transact! g [[:ex/s :ex/p "o"]])
    (theo/transact! (theo/default-graph db) [[:ex/s :ex/p "d"]])
    (is (= 1 (graph-size db "http://example.com/g"))
        "the first write adds the graph")
    (is (= 1 (graph-size db)) "the default graph")
    (is (= {:rdf/about :ex/s :ex/p "o"} (theo/entity union :ex/s))
        "the union of the named graphs, without the default graph")
    (is (= prefixes (:prefixes g) (:prefixes union))
        "a view keeps the prefixes")
    (is (thrown? Exception (theo/transact! union [[:ex/s :ex/p "x"]]))
        "the union can only be read")
    (txn/with-write-transaction (:dataset db)
      (theo/transact! g [[:ex/t :ex/p "o"]])
      (theo/transact! h [[:ex/t :ex/p "p"]]))
    (is (= 2 (count (theo/q union "SELECT * WHERE { ex:t ?p ?o }")))
        "views share the transactions of the Dataset"))
  (let [db (theo/db (ModelFactory/createDefaultModel) {:prefixes prefixes})]
    (is (identical? db (theo/default-graph db)))
    (is (thrown-with-msg? ExceptionInfo #"no named graphs"
                          (theo/named-graph db :ex/g)))
    (is (thrown-with-msg? ExceptionInfo #"no named graphs"
                          (theo/union-graph db))))
  (is (thrown-with-msg? ExceptionInfo #"Not a graph name"
                        (theo/named-graph (theo/db) "g"))))

(deftest graph-and-dataset-graph
  (let [g  (GraphMemFactory/createDefaultGraph)
        db (theo/db g {:prefixes prefixes})]
    (theo/transact! db [[:ex/s :ex/p "o"]])
    (is (= 1 (.size g)) "a Graph, through a Model of it")
    (is (= "http://example.com/" (.getNsPrefixURI (.getPrefixMapping g) "ex"))))
  (let [ds (TDB2Factory/createDataset)
        g  (.getGraph (.getDefaultModel ds))]
    (theo/transact! (theo/db g {:prefixes prefixes}) [[:ex/s :ex/p "o"]])
    (is (= 1 (graph-size (theo/db ds {:prefixes prefixes})))
        "a graph of a TDB2 dataset, in a transaction of the dataset"))
  (let [ds (TDB2Factory/createDataset)
        db (theo/db (.asDatasetGraph ds) {:prefixes prefixes})]
    (theo/transact! (theo/named-graph db :ex/g) [[:ex/s :ex/p "o"]])
    (is (= 1 (graph-size db "http://example.com/g"))
        "a DatasetGraph, through a Dataset of it")))

(deftest transact-blank-nodes
  (let [m  (ModelFactory/createDefaultModel)
        db (theo/db m {:prefixes prefixes})
        node #(convert/node % prefixes)]
    (theo/transact! db [[:ex/s :ex/p '_b1] ['_b1 :ex/q "x"]])
    (let [^Graph g (.getGraph m)
          b        (.getObject (.next (.find g (node :ex/s) (node :ex/p)
                                             Node/ANY)))]
      (is (.isBlank ^Node b))
      (is (.contains g b (node :ex/q) (node "x"))
          "one label is one blank node across the triples"))))

(deftest read-files
  (let [db  (theo/db (TDB2Factory/createDataset) {:prefixes prefixes})
        in  #(theo/named-graph db %)
        n3  (.size (RDFDataMgr/loadGraph "test/TheFirm.n3"))]
    (theo/read! (in :ex/foaf) (io/file "test/foaf.rdf"))
    (theo/read! (in :ex/firm) "test/TheFirm.n3")
    (theo/read! (in :ex/firm-resource) (io/resource "TheFirm.n3"))
    (is (pos? (graph-size db "http://example.com/foaf")) "RDF/XML")
    (is (= n3 (graph-size db "http://example.com/firm")) "N3 from a path")
    (is (= n3 (graph-size db "http://example.com/firm-resource"))
        "N3 from a resource, which may be in a jar")
    (is (zero? (graph-size db)))
    (theo/read! (in :ex/both) [(io/file "test/foaf.rdf") "test/TheFirm.n3"])
    (is (= (+ n3 (graph-size db "http://example.com/foaf"))
           (graph-size db "http://example.com/both"))
        "several files")
    (is (thrown? Exception
                 (theo/read! (in :ex/half)
                             ["test/TheFirm.n3"
                              (temp-file ".ttl" "<http://example.com/s> .")])))
    (is (zero? (graph-size db "http://example.com/half"))
        "a syntax error in one file adds none of them")
    (is (thrown-with-msg? ExceptionInfo #"No RDF syntax"
                          (theo/read! db (temp-file ".txt" ""))))
    (is (thrown-with-msg? ExceptionInfo #"Not a syntax for one graph"
                          (theo/read! db (temp-file ".nq" ""))))
    (testing "a syntax error leaves the db as it was"
      (let [ttl (temp-file ".ttl" (str "<http://example.com/s> "
                                       "<http://example.com/p> \"o\" .\n"
                                       "<http://example.com/s> ."))]
        (is (thrown? Exception (theo/read! (in :ex/broken) ttl))))
      (is (zero? (graph-size db "http://example.com/broken"))))))

(def label-data
  "The triples of the pinned query tests."
  [[:ex/a :rdfs/label (term/->LangString "hund" "da")]
   [:ex/a :ex/count 2]
   [:ex/a :ex/part '_part]
   ['_part :rdfs/label "del"]
   [:ex/b :rdfs/label (term/->LangString "dog" "en")]
   [:ex/c :rdfs/label "kat"]])

(defn label-dbs
  "A db of an in-memory Model and one of TDB2, both with the label data."
  []
  [(theo/transact! (theo/db (ModelFactory/createDefaultModel)
                          {:prefixes prefixes})
                 label-data)
   (theo/transact! (theo/db (TDB2Factory/createDataset) {:prefixes prefixes})
                 label-data)])

(deftest query
  (is (instance? Query (theo/query "SELECT * WHERE { ?s rdfs:label ?l }"))
      "the default prefixes need no PREFIX line")
  (is (instance? Query (theo/query "SELECT * WHERE { ?s ex:p ?o }" prefixes)))
  (is (thrown? QueryParseException
               (theo/query "SELECT * WHERE { ?s ex:p ?o }"))
      "ex isn't one of the default prefixes"))

(deftest select
  (doseq [db (label-dbs)]
    (is (= #{{'?s :ex/a '?l (term/->LangString "hund" "da")}
             {'?s :ex/b '?l (term/->LangString "dog" "en")}
             {'?s :ex/c '?l "kat"}
             {'?s '_part '?l "del"}}
           (set (theo/q db "SELECT * WHERE { ?s rdfs:label ?l }"))))
    (is (= [{'?s :ex/a}]
           (theo/q db (theo/query "SELECT ?s WHERE { ?s ex:count 2 }"
                                  prefixes)))
        "a Query, and an integer in SPARQL matching a Clojure integer")))

(deftest bindings
  (doseq [db (label-dbs)
          :let [labels #(theo/q db "SELECT * WHERE { ?s rdfs:label ?l }" %)]]
    (is (= [{'?s :ex/b '?l (term/->LangString "dog" "en")}] (labels {'?s :ex/b}))
        "one value, kept in the rows")
    (is (= [{'?s :ex/a '?l (term/->LangString "hund" "da")}]
           (labels {'?l (term/->LangString "hund" "da")})))
    (is (= 2 (count (labels {'?s [:ex/a :ex/b]}))) "several values")
    (is (= [{'?s :ex/b '?l (term/->LangString "dog" "en")}]
           (labels {'?s [:ex/a :ex/b]
                    '?l ["kat" (term/->LangString "dog" "en")]}))
        "several variables with several values each")
    (is (= #{:ex/a :ex/c} (set (map '?s (labels {'[?s ?l] [[:ex/a nil]
                                                           [nil "kat"]]}))))
        "rows of values, with nil for unbound")
    (is (= [{'?l "kat"}]
           (theo/q db "SELECT ?l WHERE { ?s rdfs:label ?l }" {'?s :ex/c}))
        "the SELECT decides which variables come back")
    (is (= [{'?n 2}]
           (theo/q db "SELECT (COUNT(*) AS ?n) WHERE { ?s rdfs:label ?l }"
                   {'?s [:ex/a :ex/c]}))
        "the values limit what the count counts")
    (let [b (get (first (theo/q db "SELECT ?b WHERE { ex:a ex:part ?b }")) '?b)]
      (is (= [{'?b b '?l "del"}]
             (theo/q db "SELECT * WHERE { ?b rdfs:label ?l }" {'?b b}))
          "a blank node from one result finds its triples in the next"))
    (is (thrown-with-msg? ExceptionInfo #"Not a variable"
                          (labels {'s :ex/a})))))

(deftest reducible
  (doseq [db (label-dbs)
          :let [labels "SELECT * WHERE { ?s rdfs:label ?l }"
                store  (or (:dataset db) (:model db))]]
    (is (= (set (theo/q db labels)) (into #{} (theo/reducible db labels)))
        "the rows of q")
    (is (= (theo/q db labels {'?s :ex/b})
           (into [] (theo/reducible db labels {'?s :ex/b})))
        "with bindings")
    (is (= 2 (count (into [] (take 2) (theo/reducible db labels))))
        "a reduction that stops early")
    (is (= :ok (txn/calculate-write store (constantly :ok)))
        "the transaction ended with the early stop")
    (is (thrown-with-msg? ExceptionInfo #"boom"
                          (reduce (fn [_ _] (throw (ex-info "boom" {})))
                                  nil
                                  (theo/reducible db labels))))
    (is (= :ok (txn/calculate-write store (constantly :ok)))
        "the transaction ended with the exception")
    (is (thrown-with-msg? ExceptionInfo #"Not a SELECT"
                          (theo/reducible db "ASK { ?s ?p ?o }")))
    (is (thrown? IllegalArgumentException
                 (first (theo/reducible db labels)))
        "not a collection")))

(deftest values-as-nodes
  (let [db   (theo/db (TDB2Factory/createDataset) {:prefixes prefixes})
        evil "x\" } ; DROP ALL ; SELECT * { ?s ?p \""]
    (theo/transact! db [[:ex/s :ex/p evil]])
    (is (= [{'?s :ex/s}]
           (theo/q db "SELECT ?s WHERE { ?s ex:p ?o }" {'?o evil}))
        "a value goes in as a node, never as SPARQL text")))

(deftest ask-construct-describe
  (doseq [db (label-dbs)]
    (is (true? (theo/q db "ASK { ex:a ex:count 2 }")))
    (is (false? (theo/q db "ASK { ex:a ex:count 3 }")))
    (is (true? (theo/q db "ASK { ?s ex:count ?n }" {'?s :ex/a})))
    (let [result (theo/q db
                         "CONSTRUCT { ?s ex:name ?l } WHERE { ?s rdfs:label ?l }")]
      (is (instance? Db result))
      (is (= (:prefixes db) (:prefixes result)))
      (is (= #{"kat" "del"
               (term/->LangString "hund" "da") (term/->LangString "dog" "en")}
             (set (map '?l (theo/q result
                                   "SELECT ?l WHERE { ?s ex:name ?l }"))))
          "a CONSTRUCT returns a db that can be queried"))
    (is (= 4 (.size ^Model (:model (theo/q db "DESCRIBE ex:a"))))
        "Jena describes ex:a with the triples of its blank node too")))

(deftest timeout
  (let [db (theo/db (ModelFactory/createDefaultModel) {:prefixes prefixes})]
    (theo/transact! db (for [i (range 100)] [:ex/s :ex/p i]))
    (is (= 100 (count (theo/q db "SELECT * WHERE { ?s ?p ?o }" nil
                             {:timeout 10000}))))
    (is (thrown? QueryCancelledException
                 (theo/q db "SELECT * WHERE { ?a ?p ?w . ?b ?p ?x . ?c ?p ?y }"
                         nil {:timeout 50}))
        "a million rows take longer than 50 ms")))

(def people
  "The data of Aristotle's pull test, as triples."
  [[:ex/luke :ex/name "Luke"]
   [:ex/luke :ex/age 32]
   [:ex/luke :ex/eyes "blue"]
   [:ex/luke :ex/spouse :ex/hannah]
   [:ex/hannah :ex/name "Hannah"]
   [:ex/luke :ex/friends :ex/jim]
   [:ex/luke :ex/friends :ex/jamie]
   [:ex/jim :ex/name "Jim"]
   [:ex/jim :ex/friends :ex/sara]
   [:ex/sara :ex/name "Sara"]
   [:ex/sara :ex/friends :ex/thom]
   [:ex/thom :ex/name "Thom"]
   [:ex/thom :ex/friends :ex/luke]
   [:ex/jamie :ex/name "Jamie"]
   [:ex/luke :ex/address '_address]
   ['_address :ex/city "Copenhagen"]
   ['_address :ex/building '_building]
   ['_building :ex/floor 3]
   [:ex/loop :ex/start '_c1]
   ['_c1 :ex/next '_c2]
   ['_c2 :ex/next '_c1]])

(defn people-dbs
  "A db of an in-memory Model and one of TDB2, both with the people."
  []
  [(theo/transact! (theo/db (ModelFactory/createDefaultModel)
                          {:prefixes prefixes})
                 people)
   (theo/transact! (theo/db (TDB2Factory/createDataset) {:prefixes prefixes})
                 people)])

(deftest entity
  (doseq [db (people-dbs)
          :let [luke (theo/entity db :ex/luke)]]
    (is (= "Luke" (:ex/name luke)) "one value")
    (is (= #{:ex/jim :ex/jamie} (:ex/friends luke)) "a set of several values")
    (is (= :ex/luke (:rdf/about luke)))
    (is (= {:rdf/about   '_address
            :ex/city     "Copenhagen"
            :ex/building {:rdf/about '_building :ex/floor 3}}
           (:ex/address luke))
        "blank nodes become maps, as in a Concise Bounded Description")
    (is (= {:rdf/about '_c1 :ex/next {:rdf/about '_c2 :ex/next '_c1}}
           (:ex/start (theo/entity db :ex/loop)))
        "a cycle of blank nodes stops at the first one seen again")
    (is (nil? (theo/entity db :ex/nobody)))
    (is (nil? (:inferred (meta luke))) "no inference model")))

(deftest inferred-entity
  (let [schema [[:ex/Dog :rdfs/subClassOf :ex/Animal]]
        data   [[:ex/rex :rdf/type :ex/Dog] [:ex/rex :ex/name "Rex"]]
        mem-db #(theo/db (ModelFactory/createDefaultModel) {:prefixes prefixes})
        rdfs   #(ModelFactory/createInfModel
                  (ReasonerRegistry/getRDFSReasoner)
                  (:model (theo/transact! (mem-db) schema))
                  ^Model %)
        tdb2   (TDB2Factory/createDataset)]
    ;; the union model of TDB2 has the named graphs, not the default one
    (theo/transact! (theo/named-graph (theo/db tdb2 {:prefixes prefixes}) :ex/g)
                    data)
    (doseq [model [(rdfs (:model (theo/transact! (mem-db) data)))
                   (rdfs (.getUnionModel tdb2))]
            :let [rex      (theo/entity (theo/db model {:prefixes prefixes})
                                       :ex/rex)
                  inferred (:inferred (meta rex))
                  types    #(let [t (:rdf/type %)] (if (set? t) t #{t}))]]
      (is (contains? (types rex) :ex/Animal))
      (is (contains? (types inferred) :ex/Animal))
      (is (not (contains? (types inferred) :ex/Dog)) "asserted in the data")
      (is (not (contains? inferred :ex/name))))))

(deftest pull
  (doseq [db (people-dbs)]
    (is (= {:rdf/about :ex/luke
            :ex/name   "Luke"
            :ex/spouse :ex/hannah
            :ex/age    32}
           (theo/pull db [:ex/spouse :ex/name :ex/age] :ex/luke))
        "one value each, without OWL cardinality")
    (is (= #{:rdf/about :ex/name :ex/age :ex/eyes :ex/spouse :ex/friends
             :ex/address}
           (set (keys (theo/pull db [:ex/name '*] :ex/luke)))))
    (is (nil? (theo/pull db [:ex/name] :ex/poseidon)))
    (is (= {:rdf/about  :ex/luke
            :ex/friends #{{:rdf/about :ex/jim :ex/name "Jim"}
                          {:rdf/about :ex/jamie :ex/name "Jamie"}}
            :ex/spouse  {:rdf/about :ex/hannah :ex/name "Hannah"}}
           (theo/pull db [{:ex/spouse [:ex/name] :ex/friends [:ex/name]}]
                     :ex/luke))
        "nested patterns")
    (is (= {:rdf/about   :ex/luke
            :ex/address  {:rdf/about '_address :ex/city "Copenhagen"}}
           (theo/pull db [{:ex/address [:ex/city]}] :ex/luke))
        "a nested pattern on a blank node")
    (is (= {:rdf/about :ex/hannah :ex/name "Hannah" :ex/_spouse :ex/luke}
           (theo/pull db [:ex/name :ex/_spouse] :ex/hannah))
        "a reverse predicate, as in transact!")
    (is (= {:rdf/about  :ex/luke
            :ex/_friends {:rdf/about :ex/thom :ex/name "Thom"}}
           (theo/pull db [{:ex/_friends [:ex/name]}] :ex/luke))
        "a nested pattern on a reverse predicate")
    (is (= {:rdf/about '_c1 :ex/_start :ex/loop}
           (theo/pull db [:ex/_start] '_c1))
        "the subjects that link to a blank node")
    (is (not (contains? (theo/pull db '[*] :ex/hannah) :ex/_spouse))
        "* only has the predicates of the subject itself")
    (let [friend-of (fn [e n]
                      (first (filter #(= n (:ex/name %)) (:ex/friends e))))
          jim       #(friend-of
                       (theo/pull db [:ex/name {:ex/friends %}] :ex/luke)
                       "Jim")]
      (is (= :ex/thom (-> (jim 2) :ex/friends :ex/friends))
          "limited recursion")
      (is (= "Thom" (-> (jim '...) :ex/friends :ex/friends :ex/name))
          "unlimited recursion")
      (is (= :ex/luke (-> (jim '...) :ex/friends :ex/friends :ex/friends))
          "a recursion stops at a subject already pulled"))))

(deftest map-form
  (let [m  (ModelFactory/createDefaultModel)
        db (theo/db m {:prefixes prefixes})]
    (theo/transact! db [{:rdf/about :ex/jane
                       :ex/name   "Jane"
                       :ex/knows  [{:rdf/about :ex/bill :ex/name "Bill"}
                                   {:rdf/about :ex/nicole :ex/name "Nicole"}]}])
    (is (= 5 (.size m)) "nested maps with an :rdf/about"))
  (let [db (theo/db (ModelFactory/createDefaultModel) {:prefixes prefixes})]
    (theo/transact! db [{:rdf/about :ex/luke :ex/_knows :ex/jon}
                      {:rdf/about :ex/hannah
                       :ex/_knows [{:rdf/about :ex/luke}]}])
    (is (= #{[:ex/jon :ex/luke] [:ex/luke :ex/hannah]}
           (set (map (juxt '?a '?b)
                     (theo/q db "SELECT * WHERE { ?a ex:knows ?b }"))))
        "a predicate such as :ex/_knows links the other way"))
  (let [m  (ModelFactory/createDefaultModel)
        db (theo/db m {:prefixes prefixes})]
    (theo/transact! db [{:rdf/about :ex/luke :ex/name "Luke" :ex/knows [nil]}
                      {:rdf/about :ex/nobody}])
    (is (= 1 (.size m)) "nil gives no value, and an empty map no triples"))
  (let [db (theo/db (ModelFactory/createDefaultModel) {:prefixes prefixes})]
    (theo/transact! db [{:rdf/about :ex/a :ex/address {:ex/city "Copenhagen"}}])
    (let [address (:ex/address (theo/entity db :ex/a))]
      (is (= {:ex/city "Copenhagen"} (dissoc address :rdf/about)))
      (is (term/blank-node? (:rdf/about address))
          "a nested map without an :rdf/about is a blank node")))
  (doseq [db (people-dbs)
          :let [luke (theo/entity db :ex/luke)
                copy (theo/db (ModelFactory/createDefaultModel)
                             {:prefixes prefixes})]]
    (theo/transact! copy [luke])
    (is (= luke (theo/entity copy :ex/luke))
        "an entity written back as it is reads the same")))

(deftest lists-and-containers
  (let [db (theo/db (ModelFactory/createDefaultModel) {:prefixes prefixes})]
    (theo/transact! db [{:rdf/about   :ex/race
                       :ex/starters (theo/rdf-list
                                      [:ex/luke :ex/stuart :ex/joe])}
                      {:rdf/about   :ex/groceries
                       :ex/contents (theo/rdf-bag [:ex/apple :ex/banana
                                                  :ex/orange])}])
    (is (true? (theo/q db "ASK { ex:race ex:starters ?l0 .
                                ?l0 rdf:first ex:luke ; rdf:rest ?l1 .
                                ?l1 rdf:first ex:stuart ; rdf:rest ?l2 .
                                ?l2 rdf:first ex:joe ; rdf:rest rdf:nil }")))
    (is (false? (theo/q db "ASK { ?l a rdf:List }"))
        "as in Turtle, a list has no rdf:type")
    (is (= :rdf/nil (theo/rdf-list [])))
    (is (true? (theo/q db "ASK { ex:groceries ex:contents ?bag .
                                ?bag a rdf:Bag ; rdf:_1 ex:apple ;
                                     rdf:_2 ex:banana ; rdf:_3 ex:orange }"))
        "the members start at rdf:_1, as in RDF Schema")
    (is (= {:rdf/type :rdf/Seq :rdf/_1 :ex/a :rdf/_2 :ex/b}
           (theo/rdf-seq [:ex/a :ex/b])))
    (is (= :rdf/Alt (:rdf/type (theo/rdf-alt [:ex/a]))))))

(deftest write
  (doseq [db [(first (people-dbs))
              (-> (theo/db (TDB2Factory/createDataset) {:prefixes prefixes})
                  (theo/named-graph :ex/g)
                  (theo/transact! people))]
          :let [f    (temp-file ".ttl" "")
                copy (theo/db (ModelFactory/createDefaultModel)
                             {:prefixes prefixes})]]
    (is (identical? db (theo/write db f)))
    (is (str/includes? (slurp f) "PREFIX ex:") "with the prefixes of the db")
    (theo/read! copy f)
    (is (= (count people) (graph-size copy)))
    (is (= (without-blank-labels (theo/entity (first (people-dbs)) :ex/luke))
           (without-blank-labels (theo/entity copy :ex/luke)))
        "Turtle gives the blank nodes new labels"))
  (is (thrown-with-msg? ExceptionInfo #"Not a syntax for one graph"
                        (theo/write (first (people-dbs)) (temp-file ".nq" "")))))

(def census-prefixes
  "The prefixes of Aristotle's query tests on the census data."
  (assoc term/default-prefixes
    :socrata "<http://www.socrata.com/rdf/terms#>"
    :ds      "<https://data.lacity.org/resource/zzzz-zzzz/>"))

(def census
  "Aristotle's census data."
  (delay
    (theo/read! (theo/db (ModelFactory/createDefaultModel)
                      {:prefixes census-prefixes})
              "test/la_census.rdf")))

(deftest census-bindings
  (let [where  (str "WHERE { ?e ds:zip_code ?zip ; socrata:rowID ?id ;"
                    "            ds:total_population ?pop . ?e ?a ?v }")
        tuples (fn [vars bindings]
                 (set (map (apply juxt vars)
                           (theo/q @census
                                   (str "SELECT DISTINCT " (str/join " " vars)
                                        " " where)
                                   bindings))))]
    (is (= #{["90001" "57110"]} (tuples '[?zip ?pop] {'?zip "90001"}))
        "one variable, one value")
    (is (= #{["90001" "57110"] ["90005" "37681"]}
           (tuples '[?zip ?pop] {'?zip ["90001" "90005"]}))
        "one variable, several values")
    (is (= #{} (tuples '[?pop] {'?zip "90001" '?id "228"}))
        "several variables, one value each")
    (is (= #{["51223"]} (tuples '[?pop] {'?zip ["90001" "90002"]
                                         '?id  ["2" "3"]}))
        "several variables, several values each")
    (is (= #{["57110"]} (tuples '[?pop] {'[?zip ?id] [["90001" "1"]
                                                     ["90002" "0"]]}))
        "rows of values")
    (is (= #{["57110"] ["51223"]}
           (tuples '[?pop] {'[?zip ?id] [["90001" "1"]
                                        ["90002" nil]]}))
        "rows of values, some unbound")))

(deftest retract
  (doseq [db (people-dbs)
          :let [names #(set (map '?n (theo/q db
                                             "SELECT ?n WHERE { ?s ex:name ?n }")))]]
    (is (identical? db (theo/retract! db [:ex/luke :ex/age '_])))
    (is (nil? (:ex/age (theo/entity db :ex/luke))) "_ matches anything")
    (theo/retract! db [[:ex/jim :ex/name "Jim"] [:ex/sara :ex/name "Sara"]])
    (is (= #{"Luke" "Hannah" "Thom" "Jamie"} (names)))
    (is (thrown? ExceptionInfo
                 (theo/retract! db [['_ :ex/name '_]
                                  [:ex/luke :ex/name
                                   (term/->LangString "x" "en_US")]])))
    (is (= #{"Luke" "Hannah" "Thom" "Jamie"} (names))
        "an error in one pattern leaves the db as it was")
    (theo/retract! db ['_address :ex/city '_])
    (is (= {:rdf/about   '_address
            :ex/building {:rdf/about '_building :ex/floor 3}}
           (:ex/address (theo/entity db :ex/luke)))
        "a blank node from the data as a pattern")
    (is (thrown-with-msg? ExceptionInfo #"Not a pattern"
                          (theo/retract! db [[:ex/luke :ex/name]]))))
  (let [db (theo/db (TDB2Factory/createDataset) {:prefixes prefixes})
        g  (theo/transact! (theo/named-graph db :ex/g) people)]
    (theo/retract! g ['_ :ex/name '_])
    (is (empty? (theo/q db "SELECT * WHERE { GRAPH ex:g { ?s ex:name ?n } }"))
        "in a named graph")))

(deftest update!
  (doseq [db (people-dbs)
          :let [name-of #(:ex/name (theo/entity db %))]]
    (is (identical? db (theo/update! db "INSERT DATA { ex:x ex:name \"X\" }")))
    (is (= "X" (name-of :ex/x)) "the prefixes of the db need no PREFIX line")
    (theo/update! db "DELETE { ?s ex:name ?n } INSERT { ?s ex:name ?u }
                     WHERE { ?s ex:name ?n BIND (UCASE(?n) AS ?u) }"
                 {'?s :ex/luke})
    (is (= "LUKE" (name-of :ex/luke)) "a binding")
    (is (= "Jim" (name-of :ex/jim)) "only the bound subject")
    (theo/update! db "INSERT { ?s ex:note ?n } WHERE { ?s ex:name ?n }"
                 {'?n "Jim\" } ; DROP ALL ; INSERT DATA { ex:y ex:p \""})
    (is (seq (theo/entity db :ex/jim)) "a value goes in as a node, not as text")
    (is (thrown-with-msg? ExceptionInfo #"one value"
                          (theo/update! db "DELETE WHERE { ?s ex:name ?n }"
                                       {'?s [:ex/luke :ex/jim]}))))
  (let [db (theo/transact! (theo/db (TDB2Factory/createDataset)
                                 {:prefixes prefixes})
                         people)]
    (is (thrown? Exception
                 (theo/update! db (str "DELETE WHERE { ?s ex:name ?n } ;"
                                      "LOAD <file:///nowhere/at/all.ttl>"))))
    (is (= "Luke" (:ex/name (theo/entity db :ex/luke)))
        "a failure undoes the whole request on TDB2")))
