(ns dk.cst.theophrastus.sparql-test
  "SPARQL on a db with Jena's own API: a Query with values bound, the
  builders of the QueryExec and UpdateExec of a db, and rows as maps."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.sparql :as sparql]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.theophrastus.txn :as txn]))

(def prefixes
  "The default prefixes and one for the examples."
  (assoc term/default-prefixes :ex "<http://example.com/>"))

(def labels
  "The query of the labels of ?s."
  (theo/query "SELECT ?l WHERE { ?s rdfs:label ?l }" prefixes))

(defn label-db
  "A db with a label for :ex/a and one for :ex/b."
  []
  (theo/transact! (theo/db {:prefixes prefixes})
                  [[:ex/a :rdfs/label "A"]
                   [:ex/b :rdfs/label "B"]]))

(deftest bind
  (is (identical? labels (sparql/bind labels nil prefixes)) "no bindings")
  (is (str/includes? (str (sparql/bind labels {'?s :ex/a} prefixes))
                     "VALUES ?s { ex:a }"))
  (is (not (str/includes? (str labels) "VALUES")) "a copy"))

(deftest query-exec
  (let [db    (label-db)
        query (sparql/bind labels {'?s :ex/a} prefixes)]
    (txn/with-read-transaction (theo/store db)
      (with-open [qe (.build (sparql/query-exec-builder db query nil))]
        (is (= [{'?l "A"}]
               (mapv #(sparql/row % prefixes) (iterator-seq (.select qe)))))))
    (txn/with-read-transaction (theo/store db)
      (with-open [qe (.build (sparql/query-exec-builder db labels nil))]
        (is (= #{"A" "B"}
               (sparql/reduce-rows #(conj %1 (get %2 '?l)) #{} (.select qe)
                                   prefixes)))))))

(deftest update-exec
  (let [db      (label-db)
        request (theo/update-request "DELETE WHERE { ?s rdfs:label ?l }"
                                     prefixes)]
    (txn/with-write-transaction (theo/store db)
      (.execute (.build (sparql/update-exec-builder db request {'?s :ex/a}))))
    (is (= [{'?l "B"}] (theo/q db labels)) "only the bound subject")))
