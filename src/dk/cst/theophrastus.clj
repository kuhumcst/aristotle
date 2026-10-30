(ns dk.cst.theophrastus
  "A db of a Jena Model or Dataset, in the style of Datomic. The functions
  that change a db end in !."
  (:require [dk.cst.theophrastus.convert :as convert]
            [dk.cst.theophrastus.impl.dataset :as dataset]
            [dk.cst.theophrastus.impl.entity :as entity]
            [dk.cst.theophrastus.impl.files :as files]
            [dk.cst.theophrastus.sparql :as sparql]
            [dk.cst.theophrastus.txn :as txn]
            [dk.cst.theophrastus.term :as term]
            [clojure.java.io :as io])
  (:import [clojure.lang IReduceInit]
           [java.io OutputStream Writer]
           [org.apache.jena.graph Graph GraphMemFactory GraphUtil Node Triple]
           [org.apache.jena.query ARQ Dataset DatasetFactory Query QueryFactory
            Syntax]
           [org.apache.jena.rdf.model Model ModelFactory]
           [org.apache.jena.reasoner BaseInfGraph]
           [org.apache.jena.riot RDFWriter]
           [org.apache.jena.shared PrefixMapping]
           [org.apache.jena.sparql.core DatasetGraph]
           [org.apache.jena.tdb2 DatabaseMgr TDB2]
           [org.apache.jena.update UpdateFactory UpdateRequest]))

(defrecord Db [model dataset prefixes default-graph])

(defmethod print-method Db
  [db ^Writer writer]
  ;; a Model prints its triples, and a TDB2 one fails outside a transaction
  (.write writer (str "#object[" (.getName Db) " \""
                      (if (:dataset db) "a Dataset" "a Model")
                      " with " (count (:prefixes db)) " prefixes\"]")))

(defn- set-prefixes!
  "Replace the prefixes of `x` (a Model or Dataset) with the `prefixes`,
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

(defn- graph-names
  "The :default-graph option `x` with each IRI in it expanded by the
  `prefixes`."
  [x prefixes]
  (let [iri #(or (term/iri % prefixes)
                 (throw (ex-info (str "Not a graph name: " (pr-str %))
                                 {:graph-name %})))]
    (cond
      (or (nil? x) (= :union x)) x
      (set? x)                   (into #{} (map iri) x)
      :else                      (iri x))))

(defn db
  "A db of `x` (a Jena Model, Dataset, Graph or DatasetGraph) with these
  `opts`:

  - :prefixes, for the keywords that stand for IRIs (term/default-prefixes
    when none are given). They replace the prefixes stored in `x`, and on
    TDB2 that writes them to disk.
  - :transactional?, only when there is no `x` (true when not given).
  - :default-graph, the graph of a Dataset that the db reads and writes as
    its default graph: :union for the union of the named graphs, the IRI of
    a named graph, e.g. a keyword, or a set of IRIs for the union of those.
    A query still sees every named graph with GRAPH. A union can only be
    read.

  With no `x`, the db is a new in-memory Dataset with transactions, or a
  plain Model when :transactional? is false. A Model is faster to fill and
  read, but it has one graph and can't undo a failed write. A Graph or
  DatasetGraph is wrapped, not copied."
  ([]
   (db {}))
  ([x]
   ;; a Db is a map too, but not one of options
   (if (and (map? x) (not (record? x)))
     (db (if (get x :transactional? true)
           (DatasetFactory/createTxnMem)
           ;; a graph that matches by term, as the Dataset and TDB2 do,
           ;; unlike the one of ModelFactory/createDefaultModel
           (GraphMemFactory/createDefaultGraph))
         (dissoc x :transactional?))
     (db x nil)))
  ([x {:keys [prefixes default-graph]
       :or   {prefixes term/default-prefixes}
       :as   opts}]
   (cond
     (contains? opts :transactional?)
     (throw (ex-info "Not an option for a given store: :transactional?"
                     {:x x}))

     (instance? Graph x)
     (db (ModelFactory/createModelForGraph x) opts)

     (instance? DatasetGraph x)
     (db (DatasetFactory/wrap ^DatasetGraph x) opts)

     (instance? Dataset x)
     (let [names (graph-names default-graph prefixes)]
       (set-prefixes! x prefixes)
       (->Db nil x prefixes names))

     (some? default-graph)
     (throw (ex-info "A Model has no named graphs"
                     {:default-graph default-graph}))

     (instance? Model x)
     (do (set-prefixes! x prefixes)
         (->Db x nil prefixes nil))

     :else
     (throw (ex-info (str "Not a Model, Dataset or Graph: " (pr-str x))
                     {:x x})))))

(defn jena-model
  "The Jena Model of `db`: its Model, or the Model of its Dataset's default
  graph, which :default-graph picks."
  ^Model [{:keys [^Model model] :as db}]
  (or model (ModelFactory/createModelForGraph (dataset/default-graph db))))

(defn jena-dataset
  "The Jena Dataset of `db`, or nil when it's a db of a Model."
  ^Dataset [{:keys [dataset] :as db}]
  dataset)

(defn jena-graph
  "The Jena Graph of `db`: its Model's graph, or its Dataset's default
  graph, which :default-graph picks."
  ^Graph [db]
  (dataset/default-graph db))

;; as in Jena's DatasetGraph: getDefaultGraph, getGraph and getUnionGraph
(defn default-graph
  "A db of the default graph of `db` (the one that its :default-graph
  picks), with the same prefixes, or `db` itself when it's a db of a Model,
  which has no other graph."
  [{:keys [dataset prefixes] :as db}]
  (if dataset
    (->Db (jena-model db) nil prefixes nil)
    db))

(defn named-graph
  "A db of the graph named `graph-name` in `db`, with the same prefixes. The
  name is an IRI, e.g. a keyword, and the first write to the graph adds it
  to the Dataset. Writes go into the Dataset, in its transactions."
  [{:keys [dataset prefixes] :as db} graph-name]
  (let [iri (term/iri graph-name prefixes)]
    (cond
      (nil? dataset)
      (throw (ex-info "A Model has no named graphs" {:graph-name graph-name}))

      iri
      (->Db (.getNamedModel ^Dataset dataset ^String iri) nil prefixes nil)

      :else
      (throw (ex-info (str "Not a graph name: " (pr-str graph-name))
                      {:graph-name graph-name})))))

(defn union-graph
  "A read-only db of the union of the named graphs in `db`, with the same
  prefixes."
  [{:keys [dataset prefixes] :as db}]
  (if dataset
    (->Db (.getUnionModel ^Dataset dataset) nil prefixes nil)
    (throw (ex-info "A Model has no named graphs" {}))))

(defn transact!
  "Add `tx-data` to `db` in one write transaction, and return `db`. It goes
  into the default graph of a Dataset (the one that :default-graph picks),
  or into the graph of a db from named-graph.

  The data is another db, e.g. from a CONSTRUCT, a Jena Graph or Model, or
  a coll of:

  - [s p o] triples of Clojure values;
  - maps of a subject's properties, with the subject as :rdf/about, or a
    new blank node when there is none. A set or vector gives a property
    several values, and nil gives it none. A nested map is a subject of its
    own, so an entity can go back in as it is. A predicate such as
    :ex/_knows links the other way;
  - colls of these.

  The data is all converted first, so an error in it leaves `db` as it
  was. A plain in-memory Model has no transactions (see the txn
  namespace)."
  [{:keys [prefixes] :as db} tx-data]
  (let [source (cond
                 (instance? Graph tx-data) tx-data
                 (instance? Model tx-data) (.getGraph ^Model tx-data)
                 ;; read in its own transaction, since it may be on TDB2
                 (instance? Db tx-data)    (txn/calculate-read
                                             tx-data
                                             #(.toList (.find (jena-graph
                                                                tx-data))))
                 :else (convert/triples tx-data prefixes))]
    (txn/calculate-write db
                         #(let [g (jena-graph db)]
                            (if (instance? Graph source)
                              (GraphUtil/addInto g ^Graph source)
                              (GraphUtil/add g ^java.util.List source))))
    db))

(defn retract!
  "Remove the triples that match the `patterns` from `db` in one write
  transaction, and return `db`.

  A pattern is an [s p o] triple of Clojure values where _ matches
  anything, e.g. [:ex/luke :ex/age '_], and a single one needs no coll. The
  patterns are all converted first, so an error in one leaves `db` as it
  was."
  [{:keys [prefixes] :as db} patterns]
  (let [nodes (mapv #(convert/pattern->nodes % prefixes)
                    (if (convert/triple? patterns) [patterns] patterns))]
    (txn/calculate-write db
                         #(let [g (jena-graph db)]
                            (doseq [[s p o] nodes]
                              (.remove g s p o))))
    db))

(defn read!
  "Read the RDF `source` (or a coll of sources) into `db` as transact! does,
  with these `opts`, and return `db`:

  - :syntax, a media type, e.g. \"text/turtle\", or a keyword of the name or
    file extension of a syntax: :turtle or :ttl, :n-triples, :ntriples or
    :nt, :rdf-xml, :rdfxml or :rdf, :json-ld or :jsonld, and :n3. Without
    it, the name of a source gives the syntax, e.g. Turtle for .ttl, and a
    stream is read as Turtle.
  - :base, the IRI that relative IRIs resolve against, by default the URL of
    the source.

  A source is a File, URL, URI or file path, or an InputStream or Reader.
  All the sources are parsed before anything is added, so a syntax error
  leaves `db` as it was."
  ([db source]
   (read! db source nil))
  ([db source opts]
   (transact! db (files/parse (if (sequential? source) source [source])
                              opts))))

(defn write
  "Write `db` to the `target`, with the :syntax of the `opts` as read! takes
  it, and return `db`.

  The target is a File or path, or an OutputStream or Writer. Without a
  :syntax, the name of a file gives the syntax, e.g. Turtle for .ttl, and a
  stream gets Turtle. The output has the prefixes of `db`, and a db of a
  Dataset writes its default graph."
  ([db target]
   (write db target nil))
  ([db target {:keys [syntax]}]
   (let [stream? (or (instance? OutputStream target) (instance? Writer target))
         lang    (files/lang syntax (when-not stream? (io/file target)))]
     (txn/calculate-read
       db
       #(let [w (-> (RDFWriter/source (jena-graph db)) (.lang lang) (.build))]
          (cond
            (instance? Writer target)       (.output w ^Writer target)
            (instance? OutputStream target) (.output w ^OutputStream target)
            :else (with-open [out (io/output-stream (io/file target))]
                    (.output w out)))))
     db)))

;; RDF 1.1 Turtle, section 2.8: a collection is rdf:first, rdf:rest and
;; rdf:nil, with no rdf:type
(defn rdf-list
  "An RDF list of the `items` as transact! takes it, or :rdf/nil when there
  are none."
  [items]
  (if (seq items)
    {:rdf/first (first items)
     :rdf/rest  (rdf-list (rest items))}
    :rdf/nil))

;; RDF Schema 1.1, sections 5.1.2 to 5.1.5: a container's members are the
;; values of rdf:_1, rdf:_2 and so on
(defn rdf-container
  "An RDF container of the `type` (e.g. :rdf/Bag) with the `items` as its
  members, as transact! takes it."
  [type items]
  (into {:rdf/type type}
        (map-indexed (fn [i x] [(keyword "rdf" (str "_" (inc i))) x]))
        items))

(defn rdf-bag
  "An RDF Bag of the `items` as transact! takes it."
  [items]
  (rdf-container :rdf/Bag items))

(defn rdf-alt
  "An RDF Alt of the `items` as transact! takes it."
  [items]
  (rdf-container :rdf/Alt items))

(defn rdf-seq
  "An RDF Seq of the `items` as transact! takes it."
  [items]
  (rdf-container :rdf/Seq items))

(defn parse-query
  "The Jena Query of the SPARQL string `s`, parsed with the `prefixes` set
  (term/default-prefixes when none are given), so that `s` needs no PREFIX
  lines for them. A relative IRI in `s` resolves against the IRI `base`,
  when given."
  (^Query [s]
   (parse-query s term/default-prefixes))
  (^Query [s prefixes]
   (parse-query s prefixes nil))
  (^Query [s prefixes base]
   (let [q (doto (Query.)
             (.setPrefixMapping (convert/prefix-mapping prefixes)))]
     (QueryFactory/parse q s base Syntax/defaultQuerySyntax)
     q)))

;; for q, whose parameter db hides the function db
(defn- graph->db
  "A db of the in-memory Graph `g` that a query returned, with `prefixes`."
  [g prefixes]
  (db g {:prefixes prefixes}))

;; TODO: a projection into a set of tuples, e.g. {:find [?s ?o]}, as
;; Aristotle's q/run had, if Datomic-shaped queries don't bring one
(defn q
  "The results of the SPARQL query `x` on `db`, with the `bindings` and
  `opts`.

  The query is a Query, or a string that is parsed with the prefixes of
  `db`. A binding gives a variable one value or several, or gives several
  variables rows of values (with nil for unbound):

      {'?s :ex/a}
      {'?s [:ex/a :ex/b]}
      {'[?s ?o] [[:ex/a nil] [:ex/b \"x\"]]}

  The values go in as RDF terms, never as SPARQL text. A SELECT returns a
  vector of maps of variable to value, an ASK a boolean, and a CONSTRUCT
  or DESCRIBE a new in-memory db of the triples, with the prefixes of
  `db`. The query runs in one read transaction.

  The only option is :timeout, in milliseconds. Jena only checks it when
  the query moves on to its next result, so for a hard timeout, see the
  txn namespace."
  ([db x]
   (q db x nil nil))
  ([db x bindings]
   (q db x bindings nil))
  ([{:keys [prefixes] :as db} x bindings opts]
   (let [bound (sparql/bind (if (string? x) (parse-query x prefixes) x)
                            bindings
                            prefixes)]
     (txn/calculate-read
       db
       #(with-open [qe (.build (sparql/query-exec-builder db bound opts))]
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
  "A reducible of the rows of the SELECT `x` on `db`, with the `bindings`
  and `opts` of q. It runs the query each time you reduce it.

  The rows go to the reducing function one at a time, in a read
  transaction that lasts as long as the reduction, so only what the
  reduction keeps stays in memory. Use it with reduce, into, transduce or
  run!, since first, count and seq don't work on it."
  ([db x]
   (reducible db x nil nil))
  ([db x bindings]
   (reducible db x bindings nil))
  ([{:keys [prefixes] :as db} x bindings opts]
   (let [bound (sparql/bind (if (string? x) (parse-query x prefixes) x)
                            bindings
                            prefixes)]
     (when-not (.isSelectType bound)
       (throw (ex-info (str "Not a SELECT query: " bound) {:query bound})))
     (reify IReduceInit
       (reduce [_ f init]
         (txn/calculate-read
           db
           #(with-open [qe (.build (sparql/query-exec-builder db bound opts))]
              (sparql/reduce-rows f init (.select qe) prefixes))))))))

(defn parse-update
  "The Jena UpdateRequest of the SPARQL Update string `s`, parsed with the
  `prefixes` set (term/default-prefixes when none are given), so that `s`
  needs no PREFIX lines for them. A relative IRI in `s` resolves against
  the IRI `base`, when given."
  (^UpdateRequest [s]
   (parse-update s term/default-prefixes))
  (^UpdateRequest [s prefixes]
   (parse-update s prefixes nil))
  (^UpdateRequest [s prefixes base]
   (doto (UpdateRequest.)
     (.setPrefixMapping (convert/prefix-mapping prefixes))
     (UpdateFactory/parse ^String s ^String base))))

(defn update!
  "Run the SPARQL Update `x` on `db` with the `bindings` and `opts` in one
  write transaction, and return `db`.

  The update is an UpdateRequest, or a string that is parsed with the
  prefixes of `db`. A binding gives a variable one value, e.g.
  {'?s :ex/luke}, which goes in as an RDF term, never as SPARQL text. The
  only option is :timeout, in milliseconds, as for q."
  ([db x]
   (update! db x nil nil))
  ([db x bindings]
   (update! db x bindings nil))
  ([{:keys [prefixes] :as db} x bindings opts]
   (let [request (if (string? x) (parse-update x prefixes) x)]
     (txn/calculate-write
       db
       #(.execute (.build (sparql/update-exec-builder db request bindings
                                                      opts))))
     db)))

;; as TDB2's QueryEngineTDB checks it, with the context of the dataset over
;; the global one
(defn- union-default-graph?
  "Whether a query on the DatasetGraph `dsg` reads the union of its named
  graphs instead of its default graph, as TDB2 does when its context says
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
  "The Graph that a query on `db` reads: its default graph, or the union of
  the named graphs when the context of TDB2 makes that the default."
  ^Graph [{:keys [^Dataset dataset default-graph] :as db}]
  (if (and dataset
           (nil? default-graph)
           (union-default-graph? (.asDatasetGraph dataset)))
    (.getUnionGraph (.asDatasetGraph dataset))
    (jena-graph db)))

(defn triples
  "The triples of `db` that match the [s p o] `pattern`, where _ matches
  anything, as [s p o] vectors of Clojure values, or all of them when there
  is no pattern. They are read in one read transaction."
  ([db]
   (triples db '[_ _ _]))
  ([{:keys [prefixes] :as db} pattern]
   (txn/calculate-read
     db
     #(let [[^Node s ^Node p ^Node o] (convert/pattern->nodes pattern
                                                              prefixes)]
        (into []
              (map (fn [^Triple t]
                     [(convert/data (.getSubject t) prefixes)
                      (convert/data (.getPredicate t) prefixes)
                      (convert/data (.getObject t) prefixes)]))
              (.toList (.find (query-graph db) s p o)))))))

(defn- property-pairs
  "The predicate and object of each of the Jena `triples` as Clojure
  values, with `prefixes` for keywords."
  [triples prefixes]
  (into []
        (map (fn [^Triple t]
               [(convert/data (.getPredicate t) prefixes)
                (convert/data (.getObject t) prefixes)]))
        triples))

(defn- inferred
  "The property map of the `pairs` of a subject's Jena `triples` (in the same
  order) that the inference Model of `db` infers, rather than finds in its
  raw graph or schema."
  [{:keys [^Model model] :as db} triples pairs]
  ;; one find per asserted graph, as a Graph.contains call per triple is slow
  ;; for a subject with many triples
  (let [^BaseInfGraph g (.getGraph model)
        s               (.getSubject ^Triple (first triples))
        triples-of      (fn [^Graph a] (.toList (.find a s Node/ANY Node/ANY)))
        asserted        (->> [(.getRawGraph g) (.getSchemaGraph g)]
                             (keep identity)
                             (into #{} (mapcat triples-of)))]
    (->> (map (fn [t pair] (when-not (asserted t) pair)) triples pairs)
         (remove nil?)
         (entity/property-map))))

;; TODO: an option for one value or a set by a schema, as in Datomic, e.g.
;; SHACL's sh:maxCount 1 or owl:FunctionalProperty, instead of by the data
(defn entity
  "A map of the properties of `subject` in `db`, with :rdf/about for the
  subject, or nil when it has none. A property has one value, or a set when
  it has several.

  A blank node among the values becomes a map of its own properties, as in
  a Concise Bounded Description, so the entity can go back into transact!
  as it is. For a db of an inference Model, the :inferred metadata has the
  properties that the Model infers (those in neither its raw graph nor its
  schema). The entity is read in one read transaction."
  [{:keys [^Model model prefixes] :as db} subject]
  (txn/calculate-read
    db
    #(let [g               (query-graph db)
           subject-triples (fn [s]
                             (.toList (.find g (convert/node s prefixes)
                                             Node/ANY
                                             Node/ANY)))
           pairs-of        (fn [s]
                             (property-pairs (subject-triples s) prefixes))]
       (when-let [ts (not-empty (subject-triples subject))]
         (let [pairs (property-pairs ts prefixes)]
           (cond-> (assoc (entity/describe pairs-of pairs #{subject})
                     :rdf/about subject)
             (and model (instance? BaseInfGraph (.getGraph model)))
             (with-meta {:inferred (inferred db ts pairs)})))))))

(defn- links-to
  "The subjects in the Graph `g` that link to `subject` by the predicate `p`
  (one, a set, or nil for none), with `prefixes` for keywords."
  [^Graph g p subject prefixes]
  (entity/property-value
    (map #(convert/data (.getSubject ^Triple %) prefixes)
         (.toList (.find g
                         Node/ANY
                         (convert/node p prefixes)
                         (convert/node subject prefixes))))))

(defn pull
  "The properties of `subject` in `db` that the `pattern` selects, as in
  Datomic's pull, with :rdf/about for the subject. It returns nil when the
  subject has no properties and the pattern selects no links to it.

  The pattern is a vector of:

  - a predicate, e.g. :rdfs/label;
  - a reverse predicate such as :ex/_knows, for the subjects that link to
    this one by :ex/knows;
  - '*, for every predicate;
  - a map of a predicate to what to pull from its values: a pattern of its
    own, e.g. {:ex/friend [:ex/name]}, '... for this pattern again at any
    depth, or a number for this pattern again at most that many times.

  A property has one value, or a set when it has several. A recursion stops
  at a subject that it has already pulled on the way. The pull is read in
  one read transaction."
  [{:keys [prefixes] :as db} pattern subject]
  (txn/calculate-read
    db
    #(let [g (query-graph db)]
       ;; as functions, since impl.entity can't require this namespace
       (entity/pull {:entity   (fn [s] (entity db s))
                     :links-to (fn [p s] (links-to g p s prefixes))}
                    pattern
                    subject))))
