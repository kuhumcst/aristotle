(ns arachne.aristotle.function-test
  "SPARQL functions written in Clojure, called from queries."
  (:require [clojure.test :refer [deftest is]]
            [arachne.aristotle.api :as api]
            [arachne.aristotle.function :as function]
            [arachne.aristotle.term :as term])
  (:import [clojure.lang ExceptionInfo]
           [org.apache.jena.rdf.model ModelFactory]))

(def prefixes
  "The default prefixes, one for the examples and one for the functions."
  (assoc term/default-prefixes
    :ex "<http://example.com/>"
    :fn "<http://example.com/function/>"))

(function/register! :fn/twice #(* 2 %) prefixes)
(function/register! :fn/local-name #(when (keyword? %) (name %)) prefixes)
(function/register! :fn/resource #(keyword "ex" %) prefixes)
(function/register! :fn/lang #(when (instance? arachne.aristotle.term.LangStr %)
                                (term/lang %))
                    prefixes)
(function/register! :fn/blank term/blank? prefixes)
(function/register! "<http://example.com/function/fail>"
                    (fn [_] (throw (ex-info "fail" {})))
                    prefixes)

(def db
  "A db with a few numbers and labels."
  (api/transact! (api/db (ModelFactory/createDefaultModel) {:prefixes prefixes})
                 [[:ex/a :ex/n 3]
                  [:ex/b :ex/n 7]
                  [:ex/a :rdfs/label (term/->LangStr "hund" "da")]
                  [:ex/a :ex/part '_part]]))

(deftest calls
  (is (= [{'?y 42}] (api/q "SELECT ?y WHERE { BIND (fn:twice(21) AS ?y) }" db)))
  (is (= [{'?s :ex/b}]
         (api/q "SELECT ?s WHERE { ?s ex:n ?n FILTER (fn:twice(?n) > 10) }" db))
      "in a FILTER")
  (is (= [{'?y "a"}]
         (api/q "SELECT ?y WHERE { BIND (fn:local-name(ex:a) AS ?y) }" db))
      "an IRI comes in as a keyword")
  (is (= [{'?y :ex/abc}]
         (api/q "SELECT ?y WHERE { BIND (fn:resource(\"abc\") AS ?y) }" db))
      "a keyword goes out as an IRI")
  (is (= [{'?y "da"}]
         (api/q "SELECT ?y WHERE { ex:a rdfs:label ?l
                                  BIND (fn:lang(?l) AS ?y) }"
                db))
      "a language-tagged string comes in as a LangStr")
  (is (= [{'?y true}]
         (api/q "SELECT ?y WHERE { ex:a ex:part ?p BIND (fn:blank(?p) AS ?y) }"
                db))
      "a blank node comes in as a symbol")
  (is (= [{}]
         (api/q "SELECT ?y WHERE { BIND (fn:local-name(\"<urn:x>\") AS ?y) }"
                db))
      "a nil leaves the result unbound")
  (is (thrown? Exception
               (api/q "SELECT ?y WHERE { BIND (fn:fail(1) AS ?y) }" db))
      "an exception fails the query")
  (is (thrown-with-msg? ExceptionInfo #"Not an IRI"
                        (function/register! :nowhere/f identity))))
