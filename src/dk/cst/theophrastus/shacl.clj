(ns dk.cst.theophrastus.shacl
  "SHACL validation of a db, with the report as Clojure data.

  See https://www.w3.org/TR/shacl/"
  (:require [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.convert :as convert]
            [dk.cst.theophrastus.txn :as txn])
  (:import [org.apache.jena.graph Graph Node]
           [org.apache.jena.rdf.model Model]
           [org.apache.jena.shacl ShaclValidator Shapes ValidationReport]
           [org.apache.jena.shacl.validation ReportEntry]
           [org.apache.jena.sparql.path P_Link Path]))

(defn parse
  "The Jena Shapes in `x`: a Graph, a Model, a db, or a source or coll of
  sources (as theo/read! takes them)."
  ^Shapes [x]
  (let [^Graph g (cond
                   (instance? Graph x) x
                   (instance? Model x) (.getGraph ^Model x)
                   (map? x)            (theo/jena-graph x)
                   :else               (-> (theo/db {:transactional? false})
                                           (theo/read! x)
                                           (theo/jena-graph)))]
    (Shapes/parse g)))

(defn- entry->map
  "The map of the report entry `e`, with `prefixes` for keywords."
  [^ReportEntry e prefixes]
  (let [value #(some-> ^Node % (convert/data prefixes))]
    {:focus-node           (value (.focusNode e))
     :path                 (let [^Path path (.resultPath e)]
                             (if (instance? P_Link path)
                               (value (.getNode ^P_Link path))
                               (some-> path str)))
     :value                (value (.value e))
     :source-shape         (value (.source e))
     :constraint-component (value (.sourceConstraintComponent e))
     :message              (.message e)
     :severity             (some-> (.severity e) (.level) value)}))

;; SHACL, section 3.6: a validation report has a result for each failure,
;; with its focus node, path, value, source shape, constraint component and
;; severity
(defn- report->map
  "The map of the validation `report`, with `prefixes` for keywords."
  [^ValidationReport report prefixes]
  {:conforms? (.conforms report)
   :results   (mapv #(entry->map % prefixes) (.getEntries report))})

(defn validate
  "The SHACL report of `db` (or only of its `node`) against the `shapes`, as
  a map of :conforms? and :results. It's made in one read transaction.

  Each result is a map of :focus-node, :path, :value, :source-shape,
  :constraint-component, :message and :severity. Its keywords use the
  prefixes of `db`, and sh for SHACL, e.g. :sh/Violation. A path that isn't
  a single predicate is a string of SPARQL syntax."
  ([db shapes]
   (validate db shapes nil))
  ([{:keys [prefixes] :as db} ^Shapes shapes node]
   (let [prefixes (assoc prefixes :sh "<http://www.w3.org/ns/shacl#>")]
     (txn/calculate-read
       db
       #(let [validator (ShaclValidator/get)
              g         (theo/jena-graph db)]
          (report->map (if (some? node)
                         (.validate validator shapes g
                                    ^Node (convert/node node prefixes))
                         (.validate validator shapes g))
                       prefixes))))))

(defn by-severity
  "The results of the `report` grouped by their :severity."
  [report]
  (group-by :severity (:results report)))

(defn violations?
  "Whether the `report` has a result of :sh/Violation, rather than only
  :sh/Warning or :sh/Info."
  [report]
  (boolean (some #(= :sh/Violation (:severity %)) (:results report))))
