(ns dk.cst.theophrastus.shacl-test
  "SHACL validation of a db, with the report as Clojure data."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.shacl :as shacl]
            [dk.cst.theophrastus.term :as term])
  (:import [clojure.lang ExceptionInfo]
           [java.io File StringReader]
           [org.apache.jena.rdf.model ModelFactory]
           [org.apache.jena.shacl Shapes]
           [org.apache.jena.tdb2 TDB2Factory]))

(def prefixes
  "The default prefixes, SHACL's and one for the examples."
  (assoc term/default-prefixes
         :sh "<http://www.w3.org/ns/shacl#>"
         :ex "<http://example.com/>"))

(def shapes-ttl
  "A person needs a name, which is an error, and has at most one age, which
  is a warning. The property shapes have IRIs rather than blank nodes."
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
                                        (:results result)))]]
      (is (false? (:conforms? result)))
      (is (= 2 (count (:results result))))
      (is (= {:focus-node           :ex/jim
              :path                 :ex/name
              :value                nil
              :source-shape         :ex/NameShape
              :constraint-component :sh/MinCountConstraintComponent
              :severity             :sh/Violation}
             (dissoc jim :message)))
      (is (string? (:message jim)))
      (is (shacl/violations? result))
      (is (= #{:sh/Violation :sh/Warning}
             (set (keys (shacl/by-severity result)))))
      (let [luke (shacl/validate db shapes :ex/luke)]
        (is (= [:sh/Warning] (map :severity (:results luke)))
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

(def ontology-ttl
  "OWL restrictions that require values, each attached in a different way,
  and two that require none: one in a union and one of 0."
  "PREFIX ex: <http://example.com/>
   PREFIX owl: <http://www.w3.org/2002/07/owl#>
   PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
   PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
   ex:Person rdfs:subClassOf [
     owl:onProperty ex:name ;
     owl:minCardinality \"1\"^^xsd:nonNegativeInteger ] .
   ex:Employee rdfs:subClassOf ex:Person .
   ex:Pair owl:equivalentClass [ owl:intersectionOf (
     ex:Group [ owl:onProperty ex:member ; owl:cardinality 2 ] ) ] .
   ex:Either rdfs:subClassOf [ owl:unionOf (
     [ owl:onProperty ex:a ; owl:minCardinality 1 ]
     [ owl:onProperty ex:b ; owl:minCardinality 1 ] ) ] .
   ex:canonicalForm rdfs:subPropertyOf ex:form .
   ex:form rdfs:range ex:Form .
   ex:Entry rdfs:subClassOf [ owl:onProperty ex:form ;
                              owl:minQualifiedCardinality 1 ;
                              owl:onClass ex:Form ] .
   ex:Team rdfs:subClassOf [ owl:onProperty ex:member ;
                             owl:minQualifiedCardinality 1 ;
                             owl:onClass ex:Person ] .
   ex:isSenseOf owl:inverseOf ex:sense .
   ex:Sense rdfs:subClassOf [ owl:onProperty ex:isSenseOf ;
                              owl:minCardinality 1 ] .
   ex:Known rdfs:subClassOf [ owl:onProperty [ owl:inverseOf ex:knows ] ;
                              owl:minCardinality 1 ] .
   ex:Optional rdfs:subClassOf [ owl:onProperty ex:note ;
                                 owl:minCardinality 0 ] .")

(def instances-ttl
  "Instances of the classes of ontology-ttl, without the ontology, where
  the second of each kind lacks what its restriction requires."
  "PREFIX ex: <http://example.com/>
   ex:alice a ex:Person ; ex:name \"Alice\" .
   ex:bob a ex:Employee .
   ex:p1 a ex:Pair ; ex:member ex:alice .
   ex:p2 a ex:Pair ; ex:member ex:alice , ex:bob .
   ex:e a ex:Either .
   ex:entry1 a ex:Entry ; ex:canonicalForm ex:f1 ; ex:sense ex:s1 .
   ex:entry2 a ex:Entry .
   ex:team1 a ex:Team ; ex:member ex:bob .
   ex:team2 a ex:Team ; ex:member ex:thing .
   ex:s1 a ex:Sense .
   ex:s2 a ex:Sense .
   ex:alice ex:knows ex:k1 .
   ex:k1 a ex:Known .
   ex:k2 a ex:Known .
   ex:o a ex:Optional .")

(defn turtle-db
  "The `db` with the Turtle `ttl` read into it."
  [db ttl]
  (theo/read! db (StringReader. ttl)))

(defn components
  "The constraint component of each focus node in the results of the
  `report`."
  [report]
  (into {} (map (juxt :focus-node :constraint-component)) (:results report)))

(deftest owl-min-cardinality
  (let [ontology (turtle-db (theo/db {:prefixes prefixes}) ontology-ttl)
        shapes   (shacl/owl-shapes ontology
                                   {:restrictions #{:min-cardinality}})]
    (is (= 6 (count (theo/q shapes "SELECT ?s WHERE { ?s a sh:NodeShape }")))
        "none for the union or the restriction of 0")
    (doseq [db [(theo/db {:prefixes prefixes})
                (theo/db (TDB2Factory/createDataset) {:prefixes prefixes})]
            :let [report (shacl/validate (turtle-db db instances-ttl) shapes)]]
      (is (= {:ex/bob    :sh/MinCountConstraintComponent
              :ex/p1     :sh/MinCountConstraintComponent
              :ex/entry2 :sh/MinCountConstraintComponent
              :ex/team2  :sh/QualifiedMinCountConstraintComponent
              :ex/s2     :sh/MinCountConstraintComponent
              :ex/k2     :sh/MinCountConstraintComponent}
             (components report))
          (str "a superclass, an intersection, a subproperty with a range, "
               "a subclass of a qualifying class, an inverse property and "
               "an inverse property expression"))))
  (is (:conforms? (shacl/validate (turtle-db (theo/db) instances-ttl)
                                  (shacl/owl-shapes (theo/db))))
      "an ontology without restrictions"))

(def edge-cases-ttl
  "Restrictions on a union, on a symmetric property, with a plain string as
  the number, with a stray owl:onClass, with a range that is an
  intersection, with rdfs:Resource and with a datatype restriction, and
  instances where the second of each kind breaks its restriction."
  "PREFIX ex: <http://example.com/>
   PREFIX owl: <http://www.w3.org/2002/07/owl#>
   PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
   PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
   ex:Agent owl:equivalentClass [ owl:unionOf ( ex:Human ex:Robot ) ] ;
            rdfs:subClassOf [ owl:onProperty ex:id ; owl:minCardinality 1 ] .
   ex:sibling a owl:SymmetricProperty .
   ex:Twin rdfs:subClassOf [ owl:onProperty ex:sibling ;
                             owl:minCardinality 1 ] .
   ex:Named rdfs:subClassOf [ owl:onProperty ex:label ;
                              owl:minCardinality \"1\" ] .
   ex:Owner rdfs:subClassOf [ owl:onProperty ex:owns ;
                              owl:minCardinality 1 ; owl:onClass ex:Car ] .
   ex:drives rdfs:range [ owl:intersectionOf ( ex:Car ex:Machine ) ] .
   ex:Driver rdfs:subClassOf [ owl:onProperty ex:drives ;
                               owl:minQualifiedCardinality 1 ;
                               owl:onClass ex:Car ] .
   ex:Holder rdfs:subClassOf [ owl:onProperty ex:holds ;
                               owl:minQualifiedCardinality 1 ;
                               owl:onClass rdfs:Resource ] .
   ex:Adult rdfs:subClassOf [
     owl:onProperty ex:age ;
     owl:allValuesFrom [ a rdfs:Datatype ;
                         owl:onDatatype xsd:integer ;
                         owl:withRestrictions ( [ xsd:minInclusive 18 ] ) ] ] .
   ex:h1 a ex:Human ; ex:id 1 .
   ex:h2 a ex:Human .
   ex:t0 ex:sibling ex:t1 .
   ex:t1 a ex:Twin .
   ex:t2 a ex:Twin .
   ex:n1 a ex:Named ; ex:label \"x\" .
   ex:n2 a ex:Named .
   ex:o1 a ex:Owner ; ex:owns ex:house .
   ex:o2 a ex:Owner .
   ex:d1 a ex:Driver ; ex:drives ex:thing .
   ex:d2 a ex:Driver .
   ex:ho1 a ex:Holder ; ex:holds ex:thing .
   ex:ho2 a ex:Holder .
   ex:a1 a ex:Adult ; ex:age 5 .")

(deftest owl-edge-cases
  (let [db (turtle-db (theo/db {:prefixes prefixes}) edge-cases-ttl)]
    (is (= (zipmap [:ex/h2 :ex/t2 :ex/n2 :ex/o2 :ex/d2 :ex/ho2]
                   (repeat :sh/MinCountConstraintComponent))
           (components (shacl/validate db (shacl/owl-shapes db))))
        (str "a member of a union, a symmetric property, a plain string as "
             "the number, a stray owl:onClass, a range that implies the "
             "class, a trivial class, and no shape of a datatype "
             "restriction"))))

(def kinds-ttl
  "A restriction of each kind but min-cardinality, and instances of their
  classes, where the second of each kind breaks its restriction."
  "PREFIX ex: <http://example.com/>
   PREFIX owl: <http://www.w3.org/2002/07/owl#>
   PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
   ex:Single rdfs:subClassOf [ owl:onProperty ex:spouse ;
                               owl:maxCardinality 1 ] .
   ex:Parent rdfs:subClassOf [ owl:onProperty ex:child ;
                               owl:someValuesFrom ex:Person ] .
   ex:Vegan rdfs:subClassOf [ owl:onProperty ex:eats ;
                              owl:allValuesFrom ex:Plant ] .
   ex:Dane rdfs:subClassOf [ owl:onProperty ex:country ;
                             owl:hasValue ex:denmark ] .
   ex:alice a ex:Person .
   ex:apple a ex:Plant .
   ex:s1 a ex:Single ; ex:spouse ex:a .
   ex:s2 a ex:Single ; ex:spouse ex:a , ex:b .
   ex:par1 a ex:Parent ; ex:child ex:alice .
   ex:par2 a ex:Parent ; ex:child ex:rock .
   ex:v1 a ex:Vegan ; ex:eats ex:apple .
   ex:v2 a ex:Vegan ; ex:eats ex:steak .
   ex:d1 a ex:Dane ; ex:country ex:denmark .
   ex:d2 a ex:Dane .")

(deftest owl-shapes
  (let [db (turtle-db (theo/db {:prefixes prefixes}) kinds-ttl)]
    (is (= {:ex/s2 :sh/MaxCountConstraintComponent
            :ex/v2 :sh/ClassConstraintComponent
            :ex/d2 :sh/HasValueConstraintComponent}
           (components (shacl/validate db (shacl/owl-shapes db))))
        "the default kinds, without :some-values-from")
    (is (= :sh/QualifiedMinCountConstraintComponent
           (:ex/par2 (components
                       (shacl/validate db (shacl/owl-shapes
                                            db {:restrictions
                                                shacl/owl-restrictions})))))
        "owl:someValuesFrom when asked for")
    (is (= #{:sh/Warning}
           (->> (shacl/owl-shapes db {:severity :sh/Warning})
                (shacl/validate db)
                (:results)
                (map :severity)
                (set))))
    (is (thrown-with-msg? ExceptionInfo #"Not a kind"
                          (shacl/owl-shapes db {:restrictions #{:nope}})))
    (is (= #{:ex/s2 :ex/v2 :ex/d2 :ex/alice}
           (->> (shacl/validate db [(shacl/owl-shapes db)
                                    (shapes-file shapes-ttl)])
                (:results)
                (map :focus-node)
                (set)))
        "generated shapes and a file at once, where Alice has no name")))

(def linked-ttl
  "The graphs of a shapes graph that the data names: one with a person's
  shape, which imports one with the shape of the name and one that isn't
  in the Dataset."
  {:ex/shapes "PREFIX ex: <http://example.com/>
               PREFIX owl: <http://www.w3.org/2002/07/owl#>
               PREFIX sh: <http://www.w3.org/ns/shacl#>
               ex:shapes owl:imports ex:names , ex:elsewhere .
               ex:PersonShape a sh:NodeShape ;
                 sh:targetClass ex:Person ;
                 sh:property ex:NameShape ."
   :ex/names  "PREFIX ex: <http://example.com/>
               PREFIX sh: <http://www.w3.org/ns/shacl#>
               ex:NameShape sh:path ex:name ; sh:minCount 1 ."})

(deftest linked-shapes-graph
  (doseq [db [(theo/db {:prefixes prefixes})
              (theo/db (TDB2Factory/createDataset) {:prefixes prefixes})]]
    (is (thrown-with-msg? ExceptionInfo #"names no shapes graph"
                          (shacl/validate db)))
    (theo/transact! db [[:ex/data :sh/shapesGraph :ex/shapes]
                        [:ex/luke :rdf/type :ex/Person]
                        [:ex/luke :ex/name "Luke"]
                        [:ex/jim :rdf/type :ex/Person]])
    (is (thrown-with-msg? ExceptionInfo #"Not a named graph"
                          (shacl/validate db)))
    (doseq [[graph ttl] linked-ttl]
      (turtle-db (theo/named-graph db graph) ttl))
    (is (= [:ex/jim] (map :focus-node (:results (shacl/validate db))))
        "sh:shapesGraph, and owl:imports of a named graph of the Dataset"))
  (is (thrown-with-msg? ExceptionInfo #"A Model has no named"
                        (-> (theo/db {:prefixes       prefixes
                                      :transactional? false})
                            (theo/transact!
                              [[:ex/data :sh/shapesGraph :ex/shapes]])
                            (shacl/validate)))))
