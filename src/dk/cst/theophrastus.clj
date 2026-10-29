(ns dk.cst.theophrastus
  "A db of a Jena Model or Dataset, in the style of Datomic. The functions
  that change a db end in !."
  (:require [dk.cst.theophrastus.convert :as convert]
            [dk.cst.theophrastus.impl.entity :as entity]
            [dk.cst.theophrastus.impl.files :as files]
            [dk.cst.theophrastus.sparql :as sparql]
            [dk.cst.theophrastus.txn :as txn]
            [dk.cst.theophrastus.term :as term]
            [clojure.java.io :as io])
  (:import [clojure.lang IReduceInit]
           [java.io Writer]
           [org.apache.jena.graph Graph GraphUtil Node Triple]
           [org.apache.jena.query ARQ Dataset DatasetFactory Query QueryFactory
            Syntax]
           [org.apache.jena.rdf.model Model ModelFactory]
           [org.apache.jena.reasoner BaseInfGraph]
           [org.apache.jena.riot RDFDataMgr]
           [org.apache.jena.shared PrefixMapping]
           [org.apache.jena.sparql.core DatasetGraph]
           [org.apache.jena.tdb2 DatabaseMgr TDB2]
           [org.apache.jena.update UpdateFactory UpdateRequest]))

(defrecord Db [model dataset prefixes])

(defmethod print-method Db
  [db ^Writer writer]
  ;; a Model prints its triples, and a TDB2 one fails outside a transaction
  (.write writer (str "#object[" (.getName Db) " \""
                      (if (:dataset db) "a Dataset" "a Model")
                      " with " (count (:prefixes db)) " prefixes\"]")))

(defn- set-prefixes!
  "Replace the prefixes of the Model or Dataset `x` with the `prefixes`,
  unless it has them already."
  [x prefixes]
  (let [mapping           (convert/ns-prefix-map prefixes)
        ^PrefixMapping pm (if (instance? Dataset x)
                            (.getPrefixMapping ^Dataset x)
                            x)]
    ;; read first, since a write transaction on TDB2 is a commit to disk
    (when (not= mapping
                (txn/calculate-read x #(into {} (.getNsPrefixMap pm))))
      (txn/calculate-write x #(-> pm
                                  (.clearNsPrefixMap)
                                  (.setNsPrefixes ^java.util.Map mapping))))))

(defn db
  "The db of `x`, a Jena Model, Dataset, Graph or DatasetGraph, with these
  `opts`:

  - :prefixes, the prefixes of the IRIs that become keywords, in the shape
    of term/default-prefixes, which it is unless given.
  - :transactional?, true unless given, and only for a db with no `x`.

  With no `x`, it returns the db of a new in-memory Dataset with
  transactions. With :transactional? false, it's a plain Model instead,
  which is faster to fill and read, but has only one graph and doesn't undo
  a failed write. A Graph is used through a Model of it, and a DatasetGraph
  through a Dataset, without copying. The prefixes replace those of `x`
  where they differ, which for TDB2 writes them to disk."
  ([]
   (db {}))
  ([x]
   ;; a Db is a map too, but not one of options
   (if (and (map? x) (not (record? x)))
     (db (if (get x :transactional? true)
           (DatasetFactory/createTxnMem)
           (ModelFactory/createDefaultModel))
         (dissoc x :transactional?))
     (db x nil)))
  ([x {:keys [prefixes] :or {prefixes term/default-prefixes} :as opts}]
   (cond
     (contains? opts :transactional?)
     (throw (ex-info "Not an option for a given store: :transactional?"
                     {:x x}))

     (instance? Graph x)
     (db (ModelFactory/createModelForGraph x) opts)

     (instance? DatasetGraph x)
     (db (DatasetFactory/wrap ^DatasetGraph x) opts)

     (instance? Dataset x)
     (do (set-prefixes! x prefixes)
         (->Db nil x prefixes))

     (instance? Model x)
     (do (set-prefixes! x prefixes)
         (->Db x nil prefixes))

     :else
     (throw (ex-info (str "Not a Model, Dataset or Graph: " x) {:x x})))))

(defn store
  "The Jena store of `db`: its Dataset, or else its Model. The functions of
  the txn namespace take it."
  [{:keys [model dataset] :as db}]
  (or dataset model))

(defn jena-graph
  "The Jena Graph of `db`: the graph of its Model, or the default graph of
  its Dataset."
  ^Graph [{:keys [^Model model ^Dataset dataset] :as db}]
  (.getGraph (or model (.getDefaultModel dataset))))

;; as in Jena's DatasetGraph: getDefaultGraph, getGraph and getUnionGraph
(defn default-graph
  "The db of the default graph of `db`, with its prefixes. The db of a Model
  has only that graph, so it comes back as it is."
  [{:keys [dataset prefixes] :as db}]
  (if dataset
    (->Db (.getDefaultModel ^Dataset dataset) nil prefixes)
    db))

(defn named-graph
  "The db of the named `graph` of `db`, with its prefixes. The graph is an
  IRI, e.g. a keyword under the prefixes, and the first write to it adds it
  to the Dataset. Writes to the db go into the Dataset, in its
  transactions."
  [{:keys [dataset prefixes] :as db} graph]
  (let [iri (term/iri graph prefixes)]
    (cond
      (nil? dataset)
      (throw (ex-info "A Model has no named graphs" {:graph graph}))

      iri
      (->Db (.getNamedModel ^Dataset dataset ^String iri) nil prefixes)

      :else
      (throw (ex-info (str "Not a graph name: " (pr-str graph))
                      {:graph graph})))))

(defn union-graph
  "The db of the union of the named graphs of `db`, with its prefixes. It
  can only be read, since Jena refuses to write to it."
  [{:keys [dataset prefixes] :as db}]
  (if dataset
    (->Db (.getUnionModel ^Dataset dataset) nil prefixes)
    (throw (ex-info "A Model has no named graphs" {}))))

(defn transact!
  "Add `tx-data` to `db` in one write transaction, and return `db`. A db of
  a Dataset gets it in its default graph, and a db of named-graph in that
  graph.

  The data is a Jena Graph or Model, or a coll of:

  - [s p o] triples of Clojure values;
  - maps of the properties of a subject, its :rdf/about or else a new blank
    node. A set or a vector gives several values, and nil none. A nested
    map is a subject of its own, so an entity can be written back. A
    predicate such as :ex/_knows links the other way;
  - colls of these.

  It's all converted first, so an error in it leaves the db as it was. A
  plain in-memory Model has no transactions (see the txn namespace)."
  [{:keys [prefixes] :as db} tx-data]
  (let [source (cond
                 (instance? Graph tx-data) tx-data
                 (instance? Model tx-data) (.getGraph ^Model tx-data)
                 :else (convert/triples tx-data prefixes))]
    (txn/calculate-write (store db)
                         #(let [g (jena-graph db)]
                            (if (instance? Graph source)
                              (GraphUtil/addInto g ^Graph source)
                              (GraphUtil/add g ^java.util.List source))))
    db))

(defn retract!
  "Remove the triples that the `patterns` match from `db` in one write
  transaction, and return `db`. A pattern is an [s p o] triple of Clojure
  values in which _ matches anything, e.g. [:ex/luke :ex/age '_], and one
  may stand alone. They're all converted first, so an error in one leaves
  the db as it was."
  [{:keys [prefixes] :as db} patterns]
  (let [nodes (mapv #(convert/pattern->nodes % prefixes)
                    (if (convert/triple? patterns) [patterns] patterns))]
    (txn/calculate-write (store db)
                         #(let [g (jena-graph db)]
                            (doseq [[s p o] nodes]
                              (.remove g s p o))))
    db))

(defn read!
  "Read the RDF file at `source`, or the files of a coll of sources, into
  `db`, as transact! adds data, and return `db`.

  A source is a File, a URL, a URI or a file path, and its name gives the
  syntax, e.g. Turtle for .ttl. Every file is parsed before anything is
  added, so a syntax error leaves the db as it was."
  [db source]
  (transact! db (files/parse (if (sequential? source) source [source]))))

(defn write
  "Write `db` to the file `target`, a File or a path, and return `db`. A db
  of a Dataset writes its default graph. The file name gives the syntax,
  e.g. Turtle for .ttl, and the file has the prefixes of `db`."
  [db target]
  (let [f    (io/file target)
        lang (files/syntax f)]
    (txn/calculate-read (store db)
                        #(with-open [out (io/output-stream f)]
                           (RDFDataMgr/write out (jena-graph db) lang)))
    db))

;; RDF 1.1 Turtle, section 2.8: a collection is rdf:first, rdf:rest and
;; rdf:nil, with no rdf:type
(defn rdf-list
  "The RDF list of the `items`, in the map form of transact!, or :rdf/nil
  when there are none."
  [items]
  (if (seq items)
    {:rdf/first (first items)
     :rdf/rest  (rdf-list (rest items))}
    :rdf/nil))

;; RDF Schema 1.1, sections 5.1.2 to 5.1.5: a container's members are the
;; values of rdf:_1, rdf:_2 and so on
(defn rdf-container
  "The RDF container of the `type`, e.g. :rdf/Bag, with the `items` as its
  members, in the map form of transact!."
  [type items]
  (into {:rdf/type type}
        (map-indexed (fn [i x] [(keyword "rdf" (str "_" (inc i))) x]))
        items))

(defn rdf-bag
  "The RDF Bag of the `items`, in the map form of transact!."
  [items]
  (rdf-container :rdf/Bag items))

(defn rdf-alt
  "The RDF Alt of the `items`, in the map form of transact!."
  [items]
  (rdf-container :rdf/Alt items))

(defn rdf-seq
  "The RDF Seq of the `items`, in the map form of transact!."
  [items]
  (rdf-container :rdf/Seq items))

(defn query
  "The Jena Query of the SPARQL string `s`, parsed with the `prefixes` set
  beforehand, default-prefixes unless given, so that `s` needs no PREFIX
  lines for them."
  (^Query [s]
   (query s term/default-prefixes))
  (^Query [s prefixes]
   (let [q (doto (Query.)
             (.setPrefixMapping (convert/prefix-mapping prefixes)))]
     (QueryFactory/parse q s nil Syntax/defaultQuerySyntax)
     q)))

;; for q, whose parameter db hides the function db
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
  ([db x]
   (q db x nil nil))
  ([db x bindings]
   (q db x bindings nil))
  ([{:keys [prefixes] :as db} x bindings opts]
   (let [bound   (sparql/bind (if (string? x) (query x prefixes) x)
                              bindings
                              prefixes)
         builder (sparql/query-exec-builder db bound opts)]
     (txn/calculate-read
       (store db)
       #(with-open [qe (.build builder)]
          (cond
            (.isSelectType bound)    (persistent!
                                       (sparql/reduce-rows conj!
                                                           (transient [])
                                                           (.select qe)
                                                           prefixes))
            (.isAskType bound)       (.ask qe)
            (.isConstructType bound) (graph->db (.construct qe) prefixes)
            (.isDescribeType bound)  (graph->db (.describe qe) prefixes)
            :else (throw (ex-info (str "Not a SELECT, ASK, CONSTRUCT or "
                                       "DESCRIBE query: " bound)
                                  {:query bound}))))))))

;; reducible rather than lazy, as next.jdbc's plan is, since a lazy seq
;; could be read after the transaction or on another thread
(defn reducible
  "The rows of the SPARQL SELECT `x`, a string or a Query, on `db` with the
  `bindings` and `opts` of q, as a reducible that runs the query when you
  reduce it, e.g. with reduce, into, transduce or run!.

  It hands the rows to the reducing function one at a time, in a read
  transaction that ends with the reduction, also when it stops early or
  throws, so only what the reduction keeps stays in memory. Each reduction
  runs the query again, and since it isn't a collection, first, count and
  seq don't work on it."
  ([db x]
   (reducible db x nil nil))
  ([db x bindings]
   (reducible db x bindings nil))
  ([{:keys [prefixes] :as db} x bindings opts]
   (let [bound   (sparql/bind (if (string? x) (query x prefixes) x)
                              bindings
                              prefixes)
         builder (sparql/query-exec-builder db bound opts)]
     (when-not (.isSelectType bound)
       (throw (ex-info (str "Not a SELECT query: " bound) {:query bound})))
     (reify IReduceInit
       (reduce [_ f init]
         (txn/calculate-read
           (store db)
           #(with-open [qe (.build builder)]
              (sparql/reduce-rows f init (.select qe) prefixes))))))))

(defn update-request
  "The Jena UpdateRequest of the SPARQL Update string `s`, parsed with the
  `prefixes` set beforehand, default-prefixes unless given, so that `s`
  needs no PREFIX lines for them."
  (^UpdateRequest [s]
   (update-request s term/default-prefixes))
  (^UpdateRequest [s prefixes]
   (doto (UpdateRequest.)
     (.setPrefixMapping (convert/prefix-mapping prefixes))
     (UpdateFactory/parse ^String s))))

(defn update!
  "Run the SPARQL Update `x`, a string or an UpdateRequest, on `db` with the
  `bindings` in one write transaction, and return `db`.

  A string is parsed with the prefixes of `db`. A binding gives a variable
  one value, e.g. {'?s :ex/luke}, which goes in as an RDF term, never as
  SPARQL text."
  ([db x]
   (update! db x nil))
  ([{:keys [prefixes] :as db} x bindings]
   (let [request (if (string? x) (update-request x prefixes) x)
         builder (sparql/update-exec-builder db request bindings)]
     (txn/calculate-write (store db) #(.execute (.build builder)))
     db)))

;; as TDB2's QueryEngineTDB checks it, with the context of the dataset over
;; the global one
(defn- union-default-graph?
  "Whether a query on the DatasetGraph `dsg` matches the union of its named
  graphs in place of its default graph, as TDB2 does when its context says
  so."
  [^DatasetGraph dsg]
  (and (DatabaseMgr/isTDB2 dsg)
       (let [cxt (.getContext dsg)
             on? (fn [sym]
                   (if (and cxt (.isDefined cxt sym))
                     (.isTrue cxt sym)
                     (.isTrue (ARQ/getContext) sym)))]
         (or (on? TDB2/symUnionDefaultGraph1)
             (on? TDB2/symUnionDefaultGraph2)))))

;; entity and pull read with Graph.find, which is many times faster than a
;; SPARQL query for each subject, so they have to read the graph that q
;; reads, and for TDB2 that can be the union graph rather than the default
(defn- query-graph
  "The Graph whose triples a query on `db` matches: the graph of its Model,
  or else the default graph of its Dataset, or the union of its named graphs
  when TDB2 makes that the default."
  ^Graph [{:keys [^Model model ^Dataset dataset]}]
  (if dataset
    (let [dsg (.asDatasetGraph dataset)]
      (if (union-default-graph? dsg)
        (.getUnionGraph dsg)
        (.getDefaultGraph dsg)))
    (.getGraph model)))

(defn- property-pairs
  "The predicate and object of each of the Jena `triples`, as q returns
  values, with IRIs under `prefixes` as keywords."
  [triples prefixes]
  (into []
        (map (fn [^Triple t]
               [(convert/data (.getPredicate t) prefixes)
                (convert/data (.getObject t) prefixes)]))
        triples))

(defn- inferred
  "The property map of the Jena `triples` of a subject in `db`, a db of an
  inference Model, that are in neither its raw graph nor its schema."
  [{:keys [^Model model prefixes] :as db} triples]
  (let [^BaseInfGraph g (.getGraph model)
        asserted        (keep identity [(.getRawGraph g) (.getSchemaGraph g)])
        asserted?       (fn [t]
                          (some #(.contains ^Graph % ^Triple t) asserted))]
    (entity/property-map (property-pairs (remove asserted? triples) prefixes))))

;; TODO: an option for one value or a set by a schema, as in Datomic, e.g.
;; SHACL's sh:maxCount 1 or owl:FunctionalProperty, instead of by the data
(defn entity
  "The properties of `subject` in `db` as a map of predicate to value, or to
  a set of values when it has several, with :rdf/about for the subject, or
  nil when it has none.

  A blank node among the values becomes a map of its own properties and its
  :rdf/about, and so on, as in a Concise Bounded Description, so the entity
  can go back into transact! as it is. For a db of an inference Model, the
  metadata of the entity has the :inferred properties: those in neither the
  raw graph nor the schema. The entity is read in one read transaction."
  [{:keys [^Model model prefixes] :as db} subject]
  (txn/calculate-read
    (store db)
    #(let [g        (query-graph db)
           triples  (fn [s]
                      (.toList (.find g (convert/node s prefixes)
                                      Node/ANY
                                      Node/ANY)))
           pairs-of (fn [s] (property-pairs (triples s) prefixes))]
       (when-let [ts (not-empty (triples subject))]
         (cond-> (assoc (entity/describe pairs-of
                                         (property-pairs ts prefixes)
                                         #{subject})
                   :rdf/about subject)
           (and model (instance? BaseInfGraph (.getGraph model)))
           (with-meta {:inferred (inferred db ts)}))))))

(defn- links-to
  "The subjects that link to `subject` in the Graph `g` by the predicate
  `p`, as one value or a set of them, or nil when there are none, with
  values under `prefixes`."
  [^Graph g p subject prefixes]
  (entity/property-value
    (map #(convert/data (.getSubject ^Triple %) prefixes)
         (.toList (.find g
                         Node/ANY
                         (convert/node p prefixes)
                         (convert/node subject prefixes))))))

(defn pull
  "The properties of `subject` in `db` that the `pattern` selects, in the
  style of Datomic's pull, with :rdf/about for the subject, or nil when it
  has no properties and the pattern selects no links to it. The pattern is
  a vector of:

  - a predicate, e.g. :rdfs/label, for its values;
  - a predicate such as :ex/_knows, as in transact!, for the subjects that
    link to this one by :ex/knows;
  - '*, for every predicate;
  - a map of predicate to what to pull from its values: a pattern of its
    own, e.g. {:ex/friend [:ex/name]}, '... for this pattern again at any
    depth, or a number for this pattern again at most that many times.

  A predicate has its value, or a set of values when it has several. A
  recursion stops at a subject that it has already pulled on its way. The
  pull is read in one read transaction."
  [{:keys [prefixes] :as db} pattern subject]
  (txn/calculate-read
    (store db)
    #(let [g (query-graph db)]
       ;; as functions, since impl.entity can't require this namespace
       (entity/pull {:entity   (fn [s] (entity db s))
                     :links-to (fn [p s] (links-to g p s prefixes))}
                    pattern
                    subject))))
