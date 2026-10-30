(ns dk.cst.theophrastus.function-test
  "SPARQL functions written in Clojure, called from queries."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.convert :as convert]
            [dk.cst.theophrastus.function :as function]
            [dk.cst.theophrastus.term :as term])
  (:import [clojure.lang ExceptionInfo]
           [org.apache.jena.rdf.model Model ModelFactory]
           [org.apache.jena.sparql.core Var]
           [org.apache.jena.sparql.exec QueryExec]))

(def prefixes
  "The default prefixes, one for the examples and one for the functions."
  (assoc term/default-prefixes
    :ex "<http://example.com/>"
    :fn "<http://example.com/function/>"))

(function/register! :fn/twice #(* 2 %) prefixes)
(function/register! :fn/local-name #(when (keyword? %) (name %)) prefixes)
(function/register! :fn/resource #(keyword "ex" %) prefixes)
(function/register! :fn/lang #(when (instance? dk.cst.theophrastus.term.LangString %)
                                (term/lang %))
                    prefixes)
(function/register! :fn/blank term/blank-node? prefixes)
(function/register! "<http://example.com/function/fail>"
                    (fn [_] (throw (ex-info "fail" {})))
                    prefixes)
;; registered under the default prefixes, which lack ex
(function/register! "<http://example.com/function/kind>"
                    #(if (keyword? %) "keyword" "other"))

(def db
  "A db with a few numbers and labels."
  (theo/transact! (theo/db (ModelFactory/createDefaultModel) {:prefixes prefixes})
                 [[:ex/a :ex/n 3]
                  [:ex/b :ex/n 7]
                  [:ex/a :rdfs/label (term/->LangString "hund" "da")]
                  [:ex/a :ex/part '_part]]))

(deftest calls
  (is (= [{'?y 42}]
         (theo/q db "SELECT ?y WHERE { BIND (fn:twice(21) AS ?y) }")))
  (is (= [{'?s :ex/b}]
         (theo/q db
                 "SELECT ?s WHERE { ?s ex:n ?n FILTER (fn:twice(?n) > 10) }"))
      "in a FILTER")
  (is (= [{'?y "a"}]
         (theo/q db "SELECT ?y WHERE { BIND (fn:local-name(ex:a) AS ?y) }"))
      "an IRI comes in as a keyword")
  (is (= [{'?y :ex/abc}]
         (theo/q db "SELECT ?y WHERE { BIND (fn:resource(\"abc\") AS ?y) }"))
      "a keyword goes out as an IRI")
  (is (= [{'?y "da"}]
         (theo/q db "SELECT ?y WHERE { ex:a rdfs:label ?l
                                     BIND (fn:lang(?l) AS ?y) }"))
      "a language-tagged string comes in as a LangString")
  (is (= [{'?y true}]
         (theo/q db
                 "SELECT ?y WHERE { ex:a ex:part ?p BIND (fn:blank(?p) AS ?y) }"))
      "a blank node comes in as a symbol")
  (is (= [{}]
         (theo/q db
                 "SELECT ?y WHERE { BIND (fn:local-name(\"<urn:x>\") AS ?y) }"))
      "a nil leaves the result unbound")
  (is (thrown? Exception
               (theo/q db "SELECT ?y WHERE { BIND (fn:fail(1) AS ?y) }"))
      "an exception fails the query")
  (is (thrown-with-msg? ExceptionInfo #"Not an IRI"
                        (function/register! :nowhere/f identity))))

(deftest prefixes-of-the-query
  (let [kind "SELECT ?y WHERE { BIND (fn:kind(ex:a) AS ?y) }"]
    (is (= [{'?y "keyword"}] (theo/q db kind))
        "an IRI under the prefixes of the db rather than of the registration")
    (is (= [{'?y "keyword"}] (into [] (theo/reducible db kind)))
        "the same in a reducible")
    (is (= "other"
           (with-open [^QueryExec qe (-> (QueryExec/graph
                                           (.getGraph ^Model (:model db)))
                                         (.query (theo/parse-query kind
                                                                   prefixes))
                                         (.build))]
             (convert/data (.get (.next (.select qe)) (Var/alloc "y"))
                           prefixes)))
        "Jena's own API, without a db, falls back on the registration")))
