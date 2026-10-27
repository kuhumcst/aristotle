(ns arachne.aristotle.impl.convert-test
  "Jena nodes to Clojure values and back, for each kind of term that DanNet
  writes or reads."
  (:require [clojure.test :refer [deftest is]]
            [arachne.aristotle.impl.convert :as convert]
            [arachne.aristotle.term :as term])
  (:import [clojure.lang ExceptionInfo]
           [java.net URI]
           [java.time LocalDate OffsetDateTime]
           [org.apache.jena.graph Node NodeFactory]
           [org.apache.jena.sparql.core Var]
           [org.apache.jena.sparql.util NodeFactoryExtra]))

(def prefixes
  "The default prefixes, one of DanNet's, and two namespaces, one inside
  the other."
  (assoc term/default-prefixes
    :dn  "<https://wordnet.dk/dannet/data/>"
    :ex  "<http://example.com/>"
    :exa "<http://example.com/a/>"))

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

(defn ->data
  "The Clojure value of the node written as `s` in Turtle syntax."
  [s]
  (convert/data (parse s) prefixes))

(deftest clojure->iri
  (is (= "https://wordnet.dk/dannet/data/synset-1"
         (.getURI (convert/node :dn/synset-1 prefixes))))
  (is (= "http://example.com/x"
         (.getURI (convert/node "<http://example.com/x>" prefixes))))
  (is (= "http://example.com/x"
         (.getURI (convert/node (URI. "http://example.com/x") prefixes))))
  (is (thrown-with-msg? ExceptionInfo #"No namespace"
                        (convert/node :nowhere/x prefixes)))
  (is (thrown-with-msg? ExceptionInfo #"Not an absolute IRI"
                        (convert/node (URI. "x") prefixes))))

(deftest clojure->literal
  (is (= ["hund" (str xsd "string") ""] (literal (convert/node "hund" prefixes))))
  (is (= ["<b>bold</b>" (str xsd "string") ""]
         (literal (convert/node "<b>bold</b>" prefixes)))
      "text that fails the IRI rule")
  (is (= ["<urn:isbn:123>" (str xsd "string") ""]
         (literal (convert/node (term/literal "<urn:isbn:123>") prefixes)))
      "text that passes the IRI rule, wrapped in a Literal")
  (is (= ["1" (str xsd "integer") ""] (literal (convert/node 1 prefixes))))
  (is (= ["1" (str xsd "integer") ""] (literal (convert/node (int 1) prefixes))))
  (is (= ["123456789012345678901234567890" (str xsd "integer") ""]
         (literal (convert/node 123456789012345678901234567890N prefixes))))
  (is (= ["1.5" (str xsd "double") ""] (literal (convert/node 1.5 prefixes))))
  (is (= ["1.5" (str xsd "float") ""] (literal (convert/node (float 1.5) prefixes))))
  (is (= ["true" (str xsd "boolean") ""] (literal (convert/node true prefixes))))
  (is (= [(str xsd "decimal") ""] (rest (literal (convert/node 1.5M prefixes)))))
  (is (= ["2024-01-02" (str xsd "date") ""]
         (literal (convert/node (LocalDate/of 2024 1 2) prefixes))))
  (is (= ["2024-01-02T03:04:05+02:00" (str xsd "dateTime") ""]
         (literal (convert/node (OffsetDateTime/parse "2024-01-02T03:04:05+02:00")
                                prefixes))))
  (is (= ["1970-01-01T00:00:00Z" (str xsd "dateTime") ""]
         (literal (convert/node (java.util.Date. 0) prefixes))))
  (is (= ["x" "http://example.com/datatype" ""]
         (literal (convert/node (term/literal "x" "<http://example.com/datatype>")
                                prefixes))))
  (is (thrown? ExceptionInfo (convert/node 1/3 prefixes)))
  (is (thrown? ExceptionInfo (convert/node nil prefixes))))

(deftest clojure->langstring
  (is (= ["hund" (str "http://www.w3.org/1999/02/22-rdf-syntax-ns#" "langString") "da"]
         (literal (convert/node (term/->LangStr "hund" "da") prefixes))))
  (is (= "en-GB"
         (last (literal (convert/node (term/->LangStr "colour" "en-gb") prefixes))))
      "Jena writes language tags in canonical case")
  (doseq [tag ["en_US" "" "da-"]]
    (is (thrown-with-msg? ExceptionInfo #"Not a well-formed language tag"
                          (convert/node (term/->LangStr "x" tag) prefixes))
        (str "Jena would store " (pr-str tag) " as it is"))))

(deftest clojure->blank-node-and-variable
  (is (.isBlank ^Node (convert/node '_ prefixes)))
  (is (= "b1" (.getBlankNodeLabel ^Node (convert/node '_b1 prefixes))))
  (is (= "x" (.getName ^Node (convert/node '?x prefixes))))
  (is (thrown? ExceptionInfo (convert/node 'x prefixes)))
  (is (thrown? ExceptionInfo (convert/node 'a/_b1 prefixes))))

(deftest iri->clojure
  (is (= :dn/synset-1 (->data "<https://wordnet.dk/dannet/data/synset-1>")))
  (is (= "<http://nowhere.example/x>" (->data "<http://nowhere.example/x>")))
  (is (= :ex/x (->data "<http://example.com/x>")))
  (is (= :exa/b (->data "<http://example.com/a/b>")) "the longest namespace")
  (is (= "<http://example.com/a/b/c>" (->data "<http://example.com/a/b/c>"))
      "a slash in what the longest namespace leaves, as in Aristotle's registry")
  (is (= "<http://example.com/>" (->data "<http://example.com/>"))
      "a namespace itself, e.g. the object of rdfs:isDefinedBy")
  (is (= "<http://www.w3.org/2002/07/owl#>"
         (->data "<http://www.w3.org/2002/07/owl#>"))))

(deftest literal->clojure
  (is (= "hund" (->data "\"hund\"")))
  (is (= (term/literal "<urn:isbn:123>") (->data "\"<urn:isbn:123>\""))
      "text that passes the IRI rule stays a Literal, so it's written back as text")
  (is (= "<b>bold</b>" (->data "\"<b>bold</b>\"")))
  (is (= 5 (->data "5")))
  (is (= 5 (->data "\"05\"^^<http://www.w3.org/2001/XMLSchema#integer>")))
  (is (= 5 (->data (str "\"5\"^^<" xsd "long>"))))
  (is (instance? Long (->data (str "\"5\"^^<" xsd "unsignedByte>"))))
  (is (= 123456789012345678901234567890N (->data "123456789012345678901234567890")))
  (is (= 18446744073709551615N
         (->data (str "\"18446744073709551615\"^^<" xsd "unsignedLong>")))
      "the largest unsignedLong, past the largest long")
  (is (= (term/literal "18446744073709551616" :xsd/unsignedLong)
         (->data (str "\"18446744073709551616\"^^<" xsd "unsignedLong>")))
      "past the largest unsignedLong, so ill-formed")
  (is (= 1.5 (->data "1.5e0")))
  (is (= ##Inf (->data (str "\"INF\"^^<" xsd "double>"))))
  (is (= (float 1.5) (->data (str "\"1.5\"^^<" xsd "float>"))))
  (is (instance? Float (->data (str "\"1.5\"^^<" xsd "float>"))))
  (is (= true (->data "true")))
  (is (= true (->data (str "\"1\"^^<" xsd "boolean>"))))
  (is (= (term/literal "yes" :xsd/boolean) (->data (str "\"yes\"^^<" xsd "boolean>")))
      "an ill-formed literal stays a Literal")
  (is (= (term/literal "4.5" :xsd/decimal) (->data "4.5")))
  (is (= (term/literal "2024-01-02" :xsd/date)
         (->data (str "\"2024-01-02\"^^<" xsd "date>"))))
  (is (= (term/literal "x" "<http://example.com/datatype>")
         (->data "\"x\"^^<http://example.com/datatype>"))))

(deftest langstring->clojure
  (is (= (term/->LangStr "hund" "da") (->data "\"hund\"@da")))
  (is (= "en-GB" (term/lang (->data "\"colour\"@en-gb")))
      "Jena reads language tags in canonical case"))

(deftest blank-node-and-variable->clojure
  (is (= '_b1 (->data "_:b1")))
  (is (= '?x (convert/data (Var/alloc "x") prefixes))))

(deftest unsupported-terms
  (is (thrown-with-msg? ExceptionInfo #"No base direction"
                        (convert/data (NodeFactory/createLiteralDirLang "x" "ar" "rtl")
                                      prefixes)))
  (is (thrown-with-msg? ExceptionInfo #"No triple terms"
                        (convert/data (NodeFactory/createTripleTerm
                                        (parse "<http://example.com/s>")
                                        (parse "<http://example.com/p>")
                                        (parse "<http://example.com/o>"))
                                      prefixes))))

(deftest round-trip
  (doseq [s ["<https://wordnet.dk/dannet/data/synset-1>"
             "<http://nowhere.example/x>"
             "<http://example.com/a/b/c>"
             "<http://example.com/>"
             "\"hund\""
             "\"<urn:isbn:123>\""
             "\"hund\"@da"
             "_:b1"
             "5"
             "true"
             "4.5"
             (str "\"1.5\"^^<" xsd "double>")
             (str "\"1.5\"^^<" xsd "float>")
             (str "\"2024-01-02\"^^<" xsd "date>")
             (str "\"2024-01-02T03:04:05+02:00\"^^<" xsd "dateTime>")
             (str "\"yes\"^^<" xsd "boolean>")
             "\"x\"^^<http://example.com/datatype>"]]
    (let [n (parse s)]
      (is (= n (convert/node (convert/data n prefixes) prefixes)) s))))

(deftest value-of-data
  (is (= (LocalDate/of 2024 1 2)
         (term/value (->data (str "\"2024-01-02\"^^<" xsd "date>")))))
  (is (= (OffsetDateTime/parse "2024-01-02T03:04:05+02:00")
         (term/value (->data (str "\"2024-01-02T03:04:05+02:00\"^^<" xsd "dateTime>")))))
  (is (= "4.5" (str (term/value (->data "4.5"))))))

(deftest date-round-trip
  (let [date (java.util.Date. 1704164645000)
        n    (convert/node date prefixes)]
    (is (= (str xsd "dateTime") (second (literal n))))
    (is (= (.toInstant date)
           (.toInstant ^OffsetDateTime (term/value (convert/data n prefixes))))
        "a Date comes back through value as an OffsetDateTime, the same instant")))
