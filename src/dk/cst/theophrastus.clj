(ns dk.cst.theophrastus
  "A db of a Jena Model or Dataset, in the style of Datomic: db, q, entity,
  pull, transact!, retract!, update!, read! and write. The functions that
  change a db end in !."
  (:require [dk.cst.theophrastus.impl.convert :as convert]
            [dk.cst.theophrastus.txn :as txn]
            [dk.cst.theophrastus.term :as term]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io Writer]
           [java.util.concurrent TimeUnit]
           [org.apache.jena.graph Graph GraphMemFactory GraphUtil Node
            NodeFactory Triple]
           [org.apache.jena.query Dataset DatasetFactory Query QueryFactory
            Syntax]
           [org.apache.jena.rdf.model Model ModelFactory]
           [org.apache.jena.reasoner BaseInfGraph]
           [org.apache.jena.riot Lang RDFDataMgr RDFLanguages RDFParser]
           [org.apache.jena.shared PrefixMapping]
           [org.apache.jena.sparql.core DatasetGraph Var]
           [org.apache.jena.sparql.engine.binding Binding BindingFactory]
           [org.apache.jena.sparql.exec QueryExec RowSet UpdateExec
            UpdateExecBuilder]
           [org.apache.jena.sparql.syntax ElementData ElementGroup]
           [org.apache.jena.update UpdateFactory UpdateRequest]))

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

;; RDF Schema 1.1, section 5.1.5: rdf:_1, rdf:_2 and so on are the
;; container membership properties, not reversed ones
(defn- reverse-property
  "The predicate that the keyword `p` names the other way round, e.g.
  :ex/knows for :ex/_knows, or nil when it doesn't."
  [p]
  (when (keyword? p)
    (let [n (name p)]
      (when (and (str/starts-with? n "_")
                 (not (re-matches #"_[1-9][0-9]*" n)))
        (keyword (namespace p) (subs n 1))))))

(defn- map->triples
  "The Jena node of the subject of the map `m`, its :rdf/about or a new
  blank node, and the triples of its properties, with keywords under
  `prefixes`. A nested map is a subject of its own, and a predicate such as
  :ex/_knows links the other way."
  [m prefixes]
  (let [s (if-let [about (:rdf/about m)]
            (convert/node about prefixes)
            (NodeFactory/createBlankNode))]
    [s (vec (for [[p v] (dissoc m :rdf/about)
                  :let  [reverse (reverse-property p)
                         pred    (convert/node (or reverse p) prefixes)]
                  x     (if (or (set? v) (sequential? v)) v [v])
                  :when (some? x)
                  :let  [[o more] (if (map? x)
                                    (map->triples x prefixes)
                                    [(convert/node x prefixes) []])]
                  t     (cons (if reverse
                                (Triple/create o pred s)
                                (Triple/create s pred o))
                              more)]
              t))]))

(defn- ->triples
  "The Jena triples of `tx-data`: [s p o] triples of Clojure values, maps of
  a subject's properties, and colls of these, with keywords under
  `prefixes`."
  [tx-data prefixes]
  (cond
    (map? tx-data)
    (second (map->triples tx-data prefixes))

    (and (vector? tx-data) (= 3 (count tx-data)) (not-any? coll? tx-data))
    (let [[s p o] tx-data]
      [(Triple/create (convert/node s prefixes)
                      (convert/node p prefixes)
                      (convert/node o prefixes))])

    (coll? tx-data)
    (into [] (mapcat #(->triples % prefixes)) tx-data)

    :else
    (throw (ex-info (str "Not a triple or a map: " (pr-str tx-data))
                    {:tx-data tx-data}))))

(defn jena-graph
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

(defn transact!
  "Add `tx-data` to the default graph of `db`, or to the named `graph`, in
  one write transaction, and return `db`.

  The data is a Jena Graph or Model, or a coll of:

  - [s p o] triples of Clojure values;
  - maps of the properties of a subject, its :rdf/about or else a new blank
    node. A set or a vector gives several values, and nil none. A nested
    map is a subject of its own, so an entity can be written back. A
    predicate such as :ex/_knows links the other way;
  - colls of these.

  It's all converted first, so an error in it leaves the db as it was. A
  plain in-memory Model has no transactions (see the txn namespace)."
  ([db tx-data]
   (transact! db tx-data nil))
  ([{:keys [model dataset prefixes] :as db} tx-data graph]
   (let [source (cond
                  (instance? Graph tx-data) tx-data
                  (instance? Model tx-data) (.getGraph ^Model tx-data)
                  :else (->triples tx-data prefixes))]
     (txn/calculate-write (or dataset model)
                          #(let [g (jena-graph db graph)]
                             (if (instance? Graph source)
                               (GraphUtil/addInto g ^Graph source)
                               (GraphUtil/add g ^java.util.List source))))
     db)))

(defn retract!
  "Remove the triples that the `patterns` match from the default graph of
  `db`, or from the named `graph`, in one write transaction, and return
  `db`. A pattern is an [s p o] triple of Clojure values in which _ matches
  anything, e.g. [:ex/luke :ex/age '_], and one may stand alone. They're
  all converted first, so an error in one leaves the db as it was."
  ([db patterns]
   (retract! db patterns nil))
  ([{:keys [model dataset prefixes] :as db} patterns graph]
   (let [triple? #(and (vector? %) (= 3 (count %)) (not-any? coll? %))
         nodes   (mapv (fn [pattern]
                         (if (triple? pattern)
                           (mapv #(if (= '_ %)
                                    Node/ANY
                                    (convert/node % prefixes))
                                 pattern)
                           (throw (ex-info (str "Not a pattern: "
                                                (pr-str pattern))
                                           {:pattern pattern}))))
                       (if (triple? patterns) [patterns] patterns))]
     (txn/calculate-write (or dataset model)
                          #(let [g (jena-graph db graph)]
                             (doseq [[s p o] nodes]
                               (.remove g s p o))))
     db)))

(defn read!
  "Read the RDF file at `source`, or the files of a coll of sources, into the
  default graph of `db`, or into the named `graph`, and return `db`.

  A source is a File, a URL, a URI or a file path, and its name gives the
  syntax, e.g. Turtle for .ttl. Every file is parsed before anything is
  added, so a syntax error leaves the db as it was."
  ([db source]
   (read! db source nil))
  ([db source graph]
   (let [g (GraphMemFactory/createDefaultGraph)]
     (doseq [s (if (sequential? source) source [source])
             :let [url  (io/as-url (if (string? s) (io/file s) s))
                   lang (RDFLanguages/filenameToLang (str url))]]
       (cond
         (nil? lang)
         (throw (ex-info (str "No RDF syntax for the name " url)
                         {:source s}))

         (RDFLanguages/isQuads lang)
         (throw (ex-info (str "Not a syntax for one graph: " lang)
                         {:source s})))
       (with-open [in (io/input-stream url)]
         (-> (RDFParser/create)
             (.source in)
             (.lang lang)
             (.base (str url))
             (.parse g))))
     (transact! db g graph))))

(defn write
  "Write the default graph of `db`, or the named `graph`, to the file
  `target`, a File or a path, and return `db`. The file name gives the
  syntax, e.g. Turtle for .ttl, and the file has the prefixes of `db`."
  ([db target]
   (write db target nil))
  ([{:keys [model dataset] :as db} target graph]
   (let [f    (io/file target)
         lang (RDFLanguages/filenameToLang (str f))]
     (cond
       (nil? lang)
       (throw (ex-info (str "No RDF syntax for the name " f) {:target target}))

       (RDFLanguages/isQuads lang)
       (throw (ex-info (str "Not a syntax for one graph: " lang)
                       {:target target})))
     (txn/calculate-read (or dataset model)
                         #(with-open [out (io/output-stream f)]
                            (RDFDataMgr/write out
                                              (jena-graph db graph)
                                              ^Lang lang)))
     db)))

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
  ([s]
   (query s term/default-prefixes))
  ([s prefixes]
   (let [q (doto (Query.)
             (.setPrefixMapping (convert/prefix-mapping prefixes)))]
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

(def properties-query
  "The query of the predicates and objects of ?s."
  (query "SELECT ?p ?o WHERE { ?s ?p ?o }"))

(defn- property-map
  "The map of the predicate of each of the `rows` to its object, or to a set
  of its objects when it has several."
  [rows]
  (reduce (fn [m {:syms [?p ?o]}]
            (let [v (get m ?p ::none)]
              (assoc m ?p (cond
                            (= ::none v) ?o
                            (set? v)     (conj v ?o)
                            (= v ?o)     v
                            :else        #{v ?o}))))
          {}
          rows))

;; Concise Bounded Description (W3C Member Submission, 2005), section 2,
;; without the reifications of its third step
(defn- describe
  "The property map of the `rows` of a subject in `db`, with each blank node
  among the values replaced by the property map of its own rows, unless
  it's one of the subjects `seen` on the way there."
  [db rows seen]
  (update-vals
    (property-map rows)
    (fn [v]
      (let [expand (fn [x]
                     (if (and (term/blank? x) (not (seen x)))
                       (with-meta (describe db
                                            (q properties-query db {'?s x})
                                            (conj seen x))
                         {:subject x})
                       x))]
        (if (set? v)
          (into #{} (map expand) v)
          (expand v))))))

(defn- inferred
  "The property map of the `rows` of `subject` in `db` that its inference
  Model infers: those in neither its raw graph nor its schema."
  [{:keys [^Model model prefixes] :as db} subject rows]
  (let [^BaseInfGraph g (.getGraph model)
        rows-in         #(q properties-query
                            (->Db (ModelFactory/createModelForGraph %)
                                  nil
                                  prefixes)
                            {'?s subject})
        asserted        (into #{}
                              (mapcat rows-in)
                              (keep identity [(.getRawGraph g)
                                              (.getSchemaGraph g)]))]
    (property-map (remove asserted rows))))

;; TODO: an option for one value or a set by a schema, as in Datomic, e.g.
;; SHACL's sh:maxCount 1 or owl:FunctionalProperty, instead of by the data
(defn entity
  "The properties of `subject` in `db` as a map of predicate to value, or to
  a set of values when it has several, and {} when it has none.

  A blank node among the values becomes a map of its own properties, and
  so on, as in a Concise Bounded Description. The metadata of each map has
  its :subject. For a db of an inference Model, the metadata of the entity
  also has the :inferred properties: those in neither the raw graph nor the
  schema. The entity is read in one read transaction."
  [{:keys [^Model model ^Dataset dataset] :as db} subject]
  (txn/calculate-read
    (or dataset model)
    #(let [rows (q properties-query db {'?s subject})]
       (with-meta (describe db rows #{subject})
         (cond-> {:subject subject}
           (and model (instance? BaseInfGraph (.getGraph model)))
           (assoc :inferred (inferred db subject rows)))))))

(defn- pull*
  "The part of the entity `x` in `db` that the `pattern` selects, with its
  :rdf/about, at the `depth` of recursion, past the subjects `seen`. The
  entity is a subject, or the map of a blank node that entity expanded."
  [db pattern x depth seen]
  (let [e (if (map? x) x (entity db x))]
    (when (seq e)
      (let [subject (if (map? x) (:subject (meta x)) x)
            seen    (conj seen subject)
            wild?   (some #{'*} pattern)
            attrs   (set (remove #(or (map? %) (= '* %)) pattern))
            nested  (apply merge (filter map? pattern))
            pull-v  (fn [spec v]
                      (let [id (if (map? v) (:subject (meta v)) v)]
                        (or (when (or (map? v) (keyword? v) (term/blank? v)
                                      (term/iri-string? v))
                              (cond
                                (vector? spec)
                                (pull* db spec v 0 #{})

                                (and (not (seen id))
                                     (or (= '... spec) (< depth spec)))
                                (pull* db pattern v (inc depth) seen)))
                            v)))]
        (reduce-kv (fn [m p v]
                     (if-let [spec (get nested p)]
                       (assoc m p (if (set? v)
                                    (into #{} (map #(pull-v spec %)) v)
                                    (pull-v spec v)))
                       (if (or wild? (attrs p))
                         (assoc m p v)
                         m)))
                   {:rdf/about subject}
                   e)))))

(defn pull
  "The properties of `subject` in `db` that the `pattern` selects, in the
  style of Datomic's pull, with :rdf/about for the subject, or nil when it
  has none. The pattern is a vector of:

  - a predicate, e.g. :rdfs/label, for its values;
  - '*, for every predicate;
  - a map of predicate to what to pull from its values: a pattern of its
    own, e.g. {:ex/friend [:ex/name]}, '... for this pattern again at any
    depth, or a number for this pattern again at most that many times.

  A predicate has its value, or a set of values when it has several. A
  recursion stops at a subject that it has already pulled on its way."
  [db pattern subject]
  (pull* db pattern subject 0 #{}))

(defn- substitute
  "The builder `b` with the variable `k` bound to the value `v`, under
  `prefixes`."
  ^UpdateExecBuilder [^UpdateExecBuilder b k v prefixes]
  (let [n (convert/node k prefixes)]
    (if (and (instance? Var n) (not (or (sequential? v) (set? v))))
      (.substitution b ^Var n ^Node (convert/node v prefixes))
      (throw (ex-info (str "Not a variable with one value: " k)
                      {:variable k :value v})))))

(defn update!
  "Run the SPARQL Update `x`, a string or an UpdateRequest, on `db` with the
  `bindings` in one write transaction, and return `db`.

  A string is parsed with the prefixes of `db`. A binding gives a variable
  one value, e.g. {'?s :ex/luke}, which goes in as an RDF term, never as
  SPARQL text."
  ([db x]
   (update! db x nil))
  ([{:keys [^Model model ^Dataset dataset prefixes] :as db} x bindings]
   (let [^UpdateRequest request (if (string? x)
                                  (doto (UpdateRequest.)
                                    (.setPrefixMapping
                                      (convert/prefix-mapping prefixes))
                                    (UpdateFactory/parse ^String x))
                                  x)
         builder                (reduce-kv #(substitute %1 %2 %3 prefixes)
                                           (-> (if dataset
                                                 (UpdateExec/dataset
                                                   (.asDatasetGraph dataset))
                                                 (UpdateExec/graph
                                                   (.getGraph model)))
                                               (.update request))
                                           bindings)]
     (txn/calculate-write (or dataset model)
                          #(.execute (.build ^UpdateExecBuilder builder)))
     db)))
