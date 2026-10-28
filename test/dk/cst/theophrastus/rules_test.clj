(ns dk.cst.theophrastus.rules-test
  "Rules, reasoners and inference Models, read through a db."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.rules :as rules]
            [dk.cst.theophrastus.term :as term])
  (:import [clojure.lang ExceptionInfo]
           [java.io File]
           [org.apache.jena.rdf.model ModelFactory]
           [org.apache.jena.reasoner.rulesys Rule]
           [org.apache.jena.tdb2 TDB2Factory]))

(def prefixes
  "The default prefixes and one for the examples."
  (assoc term/default-prefixes :ex "<http://example.com/>"))

(def rule-text
  "A rule that makes ex:knows symmetric, and one that applies the classes
  of a schema."
  "@prefix ex: <http://example.com/>.
   [symmetric: (?a ex:knows ?b) -> (?b ex:knows ?a)]
   [subclass: (?c rdfs:subClassOf ?d) (?x rdf:type ?c) -> (?x rdf:type ?d)]")

(defn model-of
  "A Model of the `triples`."
  [triples]
  (:model (theo/transact! (theo/db (ModelFactory/createDefaultModel)
                                 {:prefixes prefixes})
                         triples)))

(def schema
  "The triples of the schema."
  [[:ex/Dog :rdfs/subClassOf :ex/Animal]])

(def data
  "The triples of the data."
  [[:ex/a :ex/knows :ex/b]
   [:ex/rex :rdf/type :ex/Dog]])

(deftest parse
  (is (= 2 (count (rules/parse rule-text))))
  (let [f (doto (File/createTempFile "theophrastus" ".rules")
            (.deleteOnExit)
            (spit rule-text))]
    (is (= (rules/parse rule-text) (rules/parse f)) "rule text in a file")))

(deftest inference
  ;; a symmetric rule run backward calls itself forever unless tabled
  (doseq [mode (keys rules/modes)
          :let [reasoner (rules/reasoner (conj (rules/parse rule-text)
                                               rules/table-all)
                                         {:mode mode})
                db       (theo/db (rules/inf-model reasoner
                                                  (model-of schema)
                                                  (model-of data))
                                 {:prefixes prefixes})
                rex      (theo/entity db :ex/rex)]]
    (is (= {:ex/knows :ex/a} (theo/entity db :ex/b)) (str mode))
    (is (= #{:ex/Dog :ex/Animal} (:rdf/type rex))
        (str mode ", with the schema"))
    (is (= {:rdf/type :ex/Animal} (:inferred (meta rex))) (str mode)))
  (is (thrown-with-msg? ExceptionInfo #"Not a mode"
                        (rules/reasoner [] {:mode :sideways}))))

(deftest dannet-setup
  (let [ds       (TDB2Factory/createDataset)
        _        (theo/transact! (theo/db ds {:prefixes prefixes}) data :ex/g)
        reasoner (rules/reasoner (rules/parse rule-text)
                                 {:transitive-closure-caching? true})
        db       (theo/db (rules/inf-model reasoner
                                          (model-of schema)
                                          (.getUnionModel ds))
                         {:prefixes prefixes})]
    (is (= {:ex/knows :ex/a} (:inferred (meta (theo/entity db :ex/b))))
        "an inference Model on the union of a TDB2 dataset, as in DanNet")))

(deftest rule-sets
  (is (seq rules/owl-rules))
  (is (seq rules/mini-rules))
  (is (instance? Rule rules/table-all)))
