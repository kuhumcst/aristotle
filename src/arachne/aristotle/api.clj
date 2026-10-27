(ns arachne.aristotle.api
  "A db of a Jena Model or Dataset, in the style of Datomic: db, transact
  and read. It takes the place of the root namespace when the old API goes."
  (:refer-clojure :exclude [read])
  (:require [arachne.aristotle.impl.convert :as convert]
            [arachne.aristotle.txn :as txn]
            [arachne.aristotle.term :as term]
            [clojure.java.io :as io])
  (:import [java.io Writer]
           [org.apache.jena.graph Graph GraphMemFactory GraphUtil Node Triple]
           [org.apache.jena.query Dataset DatasetFactory]
           [org.apache.jena.rdf.model Model ModelFactory]
           [org.apache.jena.riot RDFLanguages RDFParser]
           [org.apache.jena.shared PrefixMapping]
           [org.apache.jena.sparql.core DatasetGraph]))

(defrecord Db [model dataset prefixes])

(defmethod print-method Db
  [db ^Writer writer]
  ;; a Model prints its triples, and a TDB2 one fails outside a transaction
  (.write writer (str "#object[" (.getName Db) " \""
                      (if (:dataset db) "a Dataset" "a Model")
                      " with " (count (:prefixes db)) " prefixes\"]")))

(defn- prefix-map
  "The `prefixes` as Jena takes them: each prefix as a string, mapped to its
  namespace IRI without angle brackets."
  [prefixes]
  (into {}
        (for [[prefix ns-iri] prefixes]
          (if-let [iri (term/iri ns-iri prefixes)]
            [(name prefix) iri]
            (throw (ex-info (str "Not a namespace IRI: " (pr-str ns-iri))
                            {:prefix prefix :namespace ns-iri}))))))

(defn db
  "The db of `x`, a Jena Model, Dataset, Graph or DatasetGraph, with these
  `opts`:

  - :prefixes, the prefixes of the IRIs that become keywords, in the shape
    of term/default-prefixes, which it is unless given.

  A Graph is used through a Model of it, and a DatasetGraph through a
  Dataset, without copying. The prefixes replace those of `x` where they
  differ, which for TDB2 writes them to disk."
  ([x]
   (db x nil))
  ([x {:keys [prefixes] :or {prefixes term/default-prefixes} :as opts}]
   (cond
     (instance? Graph x)
     (db (ModelFactory/createModelForGraph x) opts)

     (instance? DatasetGraph x)
     (db (DatasetFactory/wrap ^DatasetGraph x) opts)

     (not (or (instance? Dataset x) (instance? Model x)))
     (throw (ex-info (str "Not a Model, Dataset or Graph: " x) {:x x}))

     :else
     (let [mapping           (prefix-map prefixes)
           ^PrefixMapping pm (if (instance? Dataset x)
                               (.getPrefixMapping ^Dataset x)
                               x)]
       (when (not= mapping
                   (txn/calculate-read x #(into {} (.getNsPrefixMap pm))))
         (txn/calculate-write x #(-> pm
                                     (.clearNsPrefixMap)
                                     (.setNsPrefixes ^java.util.Map mapping))))
       (if (instance? Dataset x)
         (->Db nil x prefixes)
         (->Db x nil prefixes))))))

(defn- ->triples
  "The Jena triples of `tx-data`, a coll of [s p o] triples of Clojure
  values, with keywords under `prefixes`."
  [tx-data prefixes]
  (mapv (fn [t]
          (if (and (vector? t) (= 3 (count t)))
            (let [[s p o] t]
              (Triple/create (convert/node s prefixes)
                             (convert/node p prefixes)
                             (convert/node o prefixes)))
            (throw (ex-info (str "Not a triple: " (pr-str t)) {:triple t}))))
        tx-data))

(defn- target
  "The Jena graph of `db` that `graph` names: its default graph when nil."
  ^Graph [{:keys [^Model model ^Dataset dataset prefixes] :as db} graph]
  (cond
    (nil? graph)
    (.getGraph (or model (.getDefaultModel dataset)))

    model
    (throw (ex-info "A Model has no named graphs" {:graph graph}))

    :else
    (let [^Node n (convert/node graph prefixes)]
      (if (.isURI n)
        (.getGraph (.getNamedModel dataset (.getURI n)))
        (throw (ex-info (str "Not a graph name: " (pr-str graph))
                        {:graph graph}))))))

(defn transact
  "Add `tx-data` to the default graph of `db`, or to the named `graph`, in
  one write transaction, and return `db`.

  The data is a coll of [s p o] triples of Clojure values, or a Jena Graph
  or Model. It's all converted first, so an error in it leaves the db as it
  was. A plain in-memory Model has no transactions (see the txn namespace)."
  ([db tx-data]
   (transact db tx-data nil))
  ([{:keys [model dataset prefixes] :as db} tx-data graph]
   (let [source (cond
                  (instance? Graph tx-data) tx-data
                  (instance? Model tx-data) (.getGraph ^Model tx-data)
                  :else (->triples tx-data prefixes))]
     (txn/calculate-write (or dataset model)
                          #(let [g (target db graph)]
                             (if (instance? Graph source)
                               (GraphUtil/addInto g ^Graph source)
                               (GraphUtil/add g ^java.util.List source))))
     db)))

(defn read
  "Read the RDF file at `source` into the default graph of `db`, or into the
  named `graph`, and return `db`.

  The source is a File, a URL, a URI or a file path, and its name gives the
  syntax, e.g. Turtle for .ttl. The whole file is parsed before anything is
  added, so a syntax error leaves the db as it was."
  ([db source]
   (read db source nil))
  ([db source graph]
   (let [url  (io/as-url (if (string? source) (io/file source) source))
         lang (RDFLanguages/filenameToLang (str url))
         g    (GraphMemFactory/createDefaultGraph)]
     (cond
       (nil? lang)
       (throw (ex-info (str "No RDF syntax for the name " url)
                       {:source source}))

       (RDFLanguages/isQuads lang)
       (throw (ex-info (str "Not a syntax for one graph: " lang)
                       {:source source})))
     (with-open [in (io/input-stream url)]
       (-> (RDFParser/create)
           (.source in)
           (.lang lang)
           (.base (str url))
           (.parse g)))
     (transact db g graph))))
