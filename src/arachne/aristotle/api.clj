(ns arachne.aristotle.api
  "A db of a Jena Model or Dataset, in the style of Datomic: db, q,
  transact and read. It takes the place of the root namespace when the old
  API goes."
  (:refer-clojure :exclude [read])
  (:require [arachne.aristotle.impl.convert :as convert]
            [arachne.aristotle.txn :as txn]
            [arachne.aristotle.term :as term]
            [clojure.java.io :as io])
  (:import [java.io Writer]
           [java.util Map]
           [java.util.concurrent TimeUnit]
           [org.apache.jena.graph Graph GraphMemFactory GraphUtil Node Triple]
           [org.apache.jena.query Dataset DatasetFactory Query QueryFactory
            Syntax]
           [org.apache.jena.rdf.model Model ModelFactory]
           [org.apache.jena.riot RDFLanguages RDFParser]
           [org.apache.jena.shared PrefixMapping PrefixMapping$Factory]
           [org.apache.jena.sparql.core DatasetGraph Var]
           [org.apache.jena.sparql.engine.binding Binding BindingFactory]
           [org.apache.jena.sparql.exec QueryExec RowSet]
           [org.apache.jena.sparql.syntax ElementData ElementGroup]))

(defrecord Db [model dataset prefixes])

(defmethod print-method Db
  [db ^Writer writer]
  ;; a Model prints its triples, and a TDB2 one fails outside a transaction
  (.write writer (str "#object[" (.getName Db) " \""
                      (if (:dataset db) "a Dataset" "a Model")
                      " with " (count (:prefixes db)) " prefixes\"]")))

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
     (let [mapping           (convert/prefix-map prefixes)
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

(defn query
  "The Jena Query of the SPARQL string `s`, parsed with the `prefixes` set
  beforehand, default-prefixes unless given, so that `s` needs no PREFIX
  lines for them."
  ([s]
   (query s term/default-prefixes))
  ([s prefixes]
   (let [pm (doto (PrefixMapping$Factory/create)
              (.setNsPrefixes ^Map (convert/prefix-map prefixes)))
         q  (doto (Query.) (.setPrefixMapping pm))]
     (QueryFactory/parse q s nil Syntax/defaultQuerySyntax)
     q)))

(defn- variable
  "The Jena Var of the symbol `sym`, e.g. ?s."
  ^Var [sym]
  (if (and (symbol? sym) (= \? (first (name sym))))
    (Var/alloc (subs (name sym) 1))
    (throw (ex-info (str "Not a variable: " (pr-str sym)) {:variable sym}))))

(defn- inline-data
  "The VALUES block of the `rows` of values of the variables `vars`, with
  nil for unbound."
  ^ElementData [vars rows prefixes]
  (let [data (ElementData.)]
    (doseq [v vars]
      (.add data (variable v)))
    (doseq [row rows]
      (let [b (BindingFactory/builder)]
        (doseq [[v x] (map vector vars row)
                :when (some? x)]
          (.add b (variable v) (convert/node x prefixes)))
        (.add data (.build b))))
    data))

;; SPARQL 1.1 Query Language, section 10.2: a VALUES block at the start of
;; the WHERE clause joins with the rest, as initial bindings did, and keeps
;; the bound variables in the results, which a substitution doesn't
(defn- bind
  "The copy of `query` with the `bindings` at the start of its WHERE clause,
  with values under `prefixes`."
  ^Query [^Query query bindings prefixes]
  (let [q     (.cloneQuery query)
        group (ElementGroup.)]
    (doseq [[k v] bindings]
      (.addElement group (cond
                           (vector? k)
                           (inline-data k v prefixes)

                           (or (sequential? v) (set? v))
                           (inline-data [k] (map vector v) prefixes)

                           :else
                           (inline-data [k] [[v]] prefixes))))
    (when-let [pattern (.getQueryPattern q)]
      (.addElement group pattern))
    (.setQueryPattern q group)
    q))

(defn- rows
  "The rows of the `row-set` as maps of variable to value, with IRIs under
  `prefixes` as keywords."
  [^RowSet row-set prefixes]
  (mapv (fn [^Binding b]
          (into {}
                (map (fn [^Var v]
                       [(symbol (str "?" (.getVarName v)))
                        (convert/data (.get b v) prefixes)]))
                (iterator-seq (.vars b))))
        (iterator-seq row-set)))

(defn- graph->db
  "A db of the in-memory Graph `g` that a query returned, with `prefixes`."
  [g prefixes]
  (db g {:prefixes prefixes}))

;; TODO: a projection into a set of tuples, e.g. {:find [?s ?o]}, as
;; Aristotle's q/run had, if Datomic-shaped queries don't bring one
(defn q
  "The results of `x`, a SPARQL query as a string or a Query, on `db`, with
  the `bindings` and these `opts`:

  - :timeout, in milliseconds, after which Jena cancels the query when it
    moves on to the next result. For a hard timeout, see the txn namespace.

  A string is parsed with the prefixes of `db`. A binding gives a variable
  one value, e.g. {'?s :ex/a}, several values, e.g. {'?s [:ex/a :ex/b]},
  or gives several variables rows of values, e.g.
  {'[?s ?o] [[:ex/a nil] [:ex/b \"x\"]]}, with nil for unbound. The values
  go in as RDF terms, never as SPARQL text.

  A SELECT returns a vector of maps of variable to value, and an ASK a
  boolean. A CONSTRUCT or DESCRIBE returns a new db of an in-memory Model
  of the triples, with the prefixes of `db`. The query runs in a read
  transaction."
  ([x db]
   (q x db nil nil))
  ([x db bindings]
   (q x db bindings nil))
  ([x {:keys [^Model model ^Dataset dataset prefixes] :as db} bindings
    {:keys [timeout] :as opts}]
   (let [parsed       (if (string? x) (query x prefixes) x)
         ^Query bound (cond-> parsed
                        (seq bindings) (bind bindings prefixes))
         exec         (if dataset
                        (QueryExec/dataset (.asDatasetGraph dataset))
                        (QueryExec/graph (.getGraph model)))
         builder      (cond-> (.query exec bound)
                        timeout (.timeout (long timeout)
                                          TimeUnit/MILLISECONDS))]
     (txn/calculate-read
       (or dataset model)
       #(with-open [qe (.build builder)]
          (cond
            (.isSelectType bound)    (rows (.select qe) prefixes)
            (.isAskType bound)       (.ask qe)
            (.isConstructType bound) (graph->db (.construct qe) prefixes)
            (.isDescribeType bound)  (graph->db (.describe qe) prefixes)
            :else (throw (ex-info (str "Not a SELECT, ASK, CONSTRUCT or "
                                       "DESCRIBE query: " bound)
                                  {:query bound}))))))))
