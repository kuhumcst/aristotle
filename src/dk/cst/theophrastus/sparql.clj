(ns dk.cst.theophrastus.sparql
  "The parts of theo/q, theo/reducible and theo/update!, for running SPARQL
  on a db with Jena's own API."
  (:require [dk.cst.theophrastus.convert :as convert])
  (:import [java.util.concurrent TimeUnit]
           [org.apache.jena.graph Node]
           [org.apache.jena.query Dataset Query]
           [org.apache.jena.rdf.model Model]
           [org.apache.jena.sparql.core Var]
           [org.apache.jena.sparql.engine.binding Binding BindingFactory]
           [org.apache.jena.sparql.exec QueryExec QueryExecBuilder RowSet
            UpdateExec UpdateExecBuilder]
           [org.apache.jena.sparql.syntax ElementData ElementGroup]
           [org.apache.jena.update UpdateRequest]))

(defn- variable
  "The Jena Var of the symbol `sym`, e.g. ?s."
  ^Var [sym]
  (if (and (symbol? sym) (= \? (first (name sym))))
    (Var/alloc (subs (name sym) 1))
    (throw (ex-info (str "Not a variable: " (pr-str sym)) {:variable sym}))))

(defn- values->binding
  "The Jena Binding of the Vars `vars` to the `values`, with nil for unbound
  and `prefixes` for keywords."
  ^Binding [vars values prefixes]
  (let [b (BindingFactory/builder)]
    (doseq [[^Var v x] (map vector vars values)
            :when (some? x)]
      (.add b v (convert/node x prefixes)))
    (.build b)))

(defn- inline-data
  "The VALUES block of the variables `vars` and the `rows` of their values,
  with nil for unbound and `prefixes` for keywords."
  ^ElementData [vars rows prefixes]
  (let [vars (mapv variable vars)
        data (ElementData.)]
    (doseq [^Var v vars]
      (.add data v))
    (doseq [values rows]
      (.add data (values->binding vars values prefixes)))
    data))

;; SPARQL 1.1 Query Language, section 10.2: a VALUES block at the start of
;; the WHERE clause joins with the rest, as initial bindings did, and keeps
;; the bound variables in the results, which a substitution doesn't
(defn bind
  "A copy of `query` with the `bindings` in a VALUES block at the start of
  its WHERE clause, or `query` itself when there are none. The bindings are
  those of theo/q, with `prefixes` for keywords."
  ^Query [^Query query bindings prefixes]
  (if (empty? bindings)
    query
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
      q)))

(defn query-exec-builder
  "The QueryExecBuilder that runs `query` on `db` with the `opts` of theo/q.
  Its context has the prefixes of `db`, for the SPARQL functions of the
  function namespace."
  ^QueryExecBuilder [{:keys [^Model model ^Dataset dataset prefixes]}
                     ^Query query
                     {:keys [timeout]}]
  (cond-> (-> (if dataset
                (QueryExec/dataset (.asDatasetGraph dataset))
                (QueryExec/graph (.getGraph model)))
              (.query query)
              (.set convert/prefixes-symbol ^Object prefixes))
    timeout (.timeout (long timeout) TimeUnit/MILLISECONDS)))

(defn row
  "The Binding `b` as a map of variable to value, as theo/q returns it, with
  `prefixes` for keywords."
  [^Binding b prefixes]
  ;; a loop over the Iterator, since iterator-seq allocates a chunk of 32
  ;; for each row
  (let [vars (.vars b)]
    (loop [m (transient {})]
      (if (.hasNext vars)
        (let [^Var v (.next vars)]
          (recur (assoc! m
                         (symbol (str "?" (.getVarName v)))
                         (convert/data (.get b v) prefixes))))
        (persistent! m)))))

(defn reduce-rows
  "Reduce the RowSet `rows` with `f` from `init`, as reduce does, one row at
  a time, with `prefixes` for keywords."
  [f init ^RowSet rows prefixes]
  ;; a loop rather than a reduce over iterator-seq, which reads rows 32 at a
  ;; time, past the point where a reduction stops
  (loop [acc init]
    (if (.hasNext rows)
      (let [acc (f acc (row (.next rows) prefixes))]
        (if (reduced? acc) @acc (recur acc)))
      acc)))

(defn- substitute
  "The builder `b`, with the value `v` in place of the variable `k` and
  `prefixes` for keywords."
  ^UpdateExecBuilder [^UpdateExecBuilder b k v prefixes]
  (if (or (sequential? v) (set? v))
    (throw (ex-info (str "Not a variable with one value: " k)
                    {:variable k :value v}))
    (.substitution b (variable k) ^Node (convert/node v prefixes))))

(defn update-exec-builder
  "The UpdateExecBuilder that runs the UpdateRequest `request` on `db`, with
  the values of the `bindings` (as theo/update! takes them) in place of
  their variables."
  ^UpdateExecBuilder [{:keys [^Model model ^Dataset dataset prefixes]}
                      ^UpdateRequest request
                      bindings]
  (reduce-kv #(substitute %1 %2 %3 prefixes)
             (-> (if dataset
                   (UpdateExec/dataset (.asDatasetGraph dataset))
                   (UpdateExec/graph (.getGraph model)))
                 (.update request))
             bindings))
