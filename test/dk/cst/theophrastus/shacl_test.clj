(ns dk.cst.theophrastus.shacl-test
  "SHACL validation of a db, with the report as Clojure data."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.shacl :as shacl]
            [dk.cst.theophrastus.term :as term])
  (:import [java.io File]
           [org.apache.jena.rdf.model ModelFactory]
           [org.apache.jena.shacl Shapes]
           [org.apache.jena.tdb2 TDB2Factory]))

(def prefixes
  "The default prefixes and one for the examples."
  (assoc term/default-prefixes :ex "<http://example.com/>"))

(def shapes-ttl
  "A person needs a name, which is an error, and has at most one age, which
  is a warning. The property shapes have IRIs, as in DanNet."
  "PREFIX ex: <http://example.com/>
   PREFIX sh: <http://www.w3.org/ns/shacl#>
   PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
   ex:PersonShape a sh:NodeShape ;
     sh:targetClass ex:Person ;
     sh:property ex:NameShape , ex:AgeShape .
   ex:NameShape sh:path ex:name ; sh:minCount 1 ; sh:datatype xsd:string .
   ex:AgeShape sh:path ex:age ; sh:maxCount 1 ; sh:severity sh:Warning .")

(defn shapes-file
  "A temporary Turtle file with the `ttl`."
  ^File [ttl]
  (doto (File/createTempFile "theophrastus" ".ttl")
    (.deleteOnExit)
    (spit ttl)))

(def data
  "Luke, who has two ages, and Jim, who has no name."
  [[:ex/luke :rdf/type :ex/Person]
   [:ex/luke :ex/name "Luke"]
   [:ex/luke :ex/age 32]
   [:ex/luke :ex/age 33]
   [:ex/jim :rdf/type :ex/Person]])

(deftest validate
  (let [shapes (shacl/parse (shapes-file shapes-ttl))]
    (doseq [db [(theo/db (ModelFactory/createDefaultModel) {:prefixes prefixes})
                (theo/db (TDB2Factory/createDataset) {:prefixes prefixes})]
            :let [_      (theo/transact! db data)
                  result (shacl/validate db shapes)
                  jim    (first (filter #(= :ex/jim (:focus-node %))
                                        (:entries result)))]]
      (is (false? (:conforms? result)))
      (is (= 2 (count (:entries result))))
      (is (= {:focus-node :ex/jim
              :path       :ex/name
              :value      nil
              :shape      :ex/NameShape
              :constraint :sh/MinCountConstraintComponent
              :severity   :sh/Violation}
             (dissoc jim :message)))
      (is (string? (:message jim)))
      (is (shacl/violations? result))
      (is (= #{:sh/Violation :sh/Warning}
             (set (keys (shacl/by-severity (:entries result))))))
      (let [luke (shacl/validate db shapes :ex/luke)]
        (is (= [:sh/Warning] (map :severity (:entries luke)))
            "only the node")
        (is (not (shacl/violations? luke)) "a warning isn't a violation"))
      (is (:conforms? (shacl/validate db shapes :ex/nobody))))))

(deftest parse
  (let [f      (shapes-file shapes-ttl)
        mem-db #(theo/read! (theo/db (ModelFactory/createDefaultModel)) f)]
    (is (instance? Shapes (shacl/parse f)) "a file")
    (is (instance? Shapes (shacl/parse [f (shapes-file "")])) "several files")
    (is (instance? Shapes (shacl/parse (mem-db))) "a db")
    (is (instance? Shapes (shacl/parse (:model (mem-db)))) "a Model")))
