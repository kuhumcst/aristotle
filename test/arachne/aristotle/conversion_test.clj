(ns arachne.aristotle.conversion-test
  "Clojure data to Jena nodes and back, for each kind of term that DanNet
  writes or reads."
  (:require [clojure.test :refer [deftest is]]
            [arachne.aristotle.graph :as graph]
            [arachne.aristotle.registry :as reg]
            [ont-app.vocabulary.lstr :refer [->LangStr lang]])
  (:import [java.net URI]
           [java.util Date]
           [ont_app.vocabulary.lstr LangStr]
           [org.apache.jena.datatypes BaseDatatype$TypedValue]
           [org.apache.jena.datatypes.xsd XSDDateTime]
           [org.apache.jena.graph Node]
           [org.apache.jena.sparql.util NodeFactoryExtra]))

(reg/prefix 'conv "http://example.com/conversion/")

(def xsd
  "The XSD namespace, for datatype IRIs."
  "http://www.w3.org/2001/XMLSchema#")

(defn literal
  "The lexical form, datatype IRI and language tag of the literal `node`."
  [^Node node]
  [(.getLiteralLexicalForm node)
   (.getLiteralDatatypeURI node)
   (.getLiteralLanguage node)])

(defn parse
  "The node written as `s` in Turtle syntax."
  [s]
  (NodeFactoryExtra/parseNode s))

(deftest clojure->iri
  (is (= "http://example.com/conversion/a" (.getURI (graph/node :conv/a))))
  (is (= "http://example.com/x" (.getURI (graph/node "<http://example.com/x>"))))
  (is (= "http://example.com/x" (.getURI (graph/node (URI. "http://example.com/x"))))))

(deftest clojure->literal
  (is (= ["hund" (str xsd "string") ""] (literal (graph/node "hund"))))
  (is (= ["1" (str xsd "integer") ""] (literal (graph/node 1)))
      "a Long is an xsd:integer, like 1 in SPARQL")
  (is (= ["1" (str xsd "integer") ""] (literal (graph/node (int 1)))))
  (is (= [(str xsd "double") ""] (rest (literal (graph/node 1.5)))))
  (is (= ["true" (str xsd "boolean") ""] (literal (graph/node true))))
  (is (= [(str xsd "decimal") ""] (rest (literal (graph/node 1.5M))))))

(deftest clojure->lang-string
  (let [[lex _ tag] (literal (graph/node (->LangStr "hund" "da")))]
    (is (= ["hund" "da"] [lex tag])))
  (is (= "en-GB" (last (literal (graph/node (->LangStr "colour" "en-gb")))))
      "Jena writes language tags in canonical case"))

(deftest clojure->blank-node
  (is (.isBlank (graph/node '_)))
  (is (= "b1" (.getBlankNodeLabel (graph/node '_b1))))
  (is (= '_b1 (graph/data (graph/node '_b1)))))

(deftest clojure->variable
  (is (= "x" (.getName (graph/node '?x)))))

(deftest iri->clojure
  (is (= :conv/a (graph/data (parse "<http://example.com/conversion/a>"))))
  (is (= "<http://nowhere.example/x>"
         (graph/data (parse "<http://nowhere.example/x>")))
      "an IRI with no registered prefix is a string in angle brackets"))

(deftest literal->clojure
  (is (= "hund" (graph/data (parse "\"hund\""))))
  (is (= 5 (graph/data (parse "5"))))
  (is (= 5 (graph/data (parse (str "\"5\"^^<" xsd "long>")))))
  (is (= 1.5 (graph/data (parse "1.5e0"))))
  (is (= 4.5M (graph/data (parse "4.5"))))
  (is (= true (graph/data (parse "true"))))
  (is (instance? XSDDateTime
                 (graph/data (parse (str "\"2024-01-02\"^^<" xsd "date>")))))
  (let [v (graph/data (parse "\"x\"^^<http://example.com/datatype>"))]
    (is (instance? BaseDatatype$TypedValue v))
    (is (= "x" (.-lexicalValue ^BaseDatatype$TypedValue v)))))

(deftest lang-string->clojure
  (let [v (graph/data (parse "\"hund\"@da"))]
    (is (instance? LangStr v))
    (is (= "hund" (str v)))
    (is (= "da" (lang v))))
  (is (= "en-GB" (lang (graph/data (parse "\"colour\"@en-gb"))))
      "Jena reads language tags in canonical case"))

(deftest date-round-trip
  (let [date (Date. 1704164645000)]
    (is (= (str xsd "dateTime") (second (literal (graph/node date)))))
    (is (= date (graph/data (graph/node date))))))
