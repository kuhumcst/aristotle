(ns dk.cst.theophrastus.shacl
  "SHACL validation of a db, with the report as Clojure data.

  See https://www.w3.org/TR/shacl/"
  (:require [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.convert :as convert]
            [dk.cst.theophrastus.txn :as txn])
  (:import [org.apache.jena.graph Graph Node]
           [org.apache.jena.rdf.model Model ModelFactory]
           [org.apache.jena.shacl ShaclValidator Shapes ValidationReport]
           [org.apache.jena.shacl.validation ReportEntry]
           [org.apache.jena.sparql.path P_Link Path]))

(defn parse
  "The Jena Shapes of `x`: a Graph, a Model, a db, or the file of a source,
  or files of a coll of sources, as for theophrastus/read!."
  ^Shapes [x]
  (let [^Graph g (cond
                   (instance? Graph x) x
                   (instance? Model x) (.getGraph ^Model x)
                   (map? x)            (theo/jena-graph x nil)
                   :else               (-> (ModelFactory/createDefaultModel)
                                           (theo/db)
                                           (theo/read! x)
                                           (theo/jena-graph nil)))]
    (Shapes/parse g)))

(defn- entry->map
  "The map of the report entry `e`, with values under `prefixes`."
  [^ReportEntry e prefixes]
  (let [value #(some-> ^Node % (convert/data prefixes))]
    {:focus-node (value (.focusNode e))
     :path       (let [^Path path (.resultPath e)]
                   (if (instance? P_Link path)
                     (value (.getNode ^P_Link path))
                     (some-> path str)))
     :value      (value (.value e))
     :shape      (value (.source e))
     :constraint (value (.sourceConstraintComponent e))
     :message    (.message e)
     :severity   (some-> (.severity e) (.level) value)}))

;; SHACL, section 3.6: a validation report has a result for each failure,
;; with its focus node, path, value, source shape, constraint component and
;; severity
(defn validate
  "The SHACL report of `db` against the `shapes`, or only of its `node`, as
  {:conforms? boolean, :n long, :entries [...]} in a read transaction.

  Each entry is a map of :focus-node, :path, :value, :shape, :constraint,
  :message and :severity, with values under the prefixes of `db` and sh for
  SHACL, e.g. :sh/Violation. A path that isn't a single predicate is a
  string of SPARQL syntax."
  ([db shapes]
   (validate db shapes nil))
  ([{:keys [model dataset prefixes] :as db} ^Shapes shapes node]
   (let [prefixes (assoc prefixes :sh "<http://www.w3.org/ns/shacl#>")]
     (txn/calculate-read
       (or dataset model)
       #(let [^Graph g                 (theo/jena-graph db nil)
              ^ValidationReport report (if (some? node)
                                         (.validate (ShaclValidator/get) shapes
                                                    g ^Node (convert/node
                                                              node prefixes))
                                         (.validate (ShaclValidator/get) shapes
                                                    g))
              entries                  (mapv (fn [e] (entry->map e prefixes))
                                             (.getEntries report))]
          {:conforms? (.conforms report)
           :n         (count entries)
           :entries   entries})))))

(defn by-severity
  "The report `entries` grouped by their :severity."
  [entries]
  (group-by :severity entries))

(defn blocking?
  "Whether the validation `result` has an entry of :sh/Violation, which
  should block a write, unlike :sh/Warning and :sh/Info."
  [result]
  (boolean (some #(= :sh/Violation (:severity %)) (:entries result))))
