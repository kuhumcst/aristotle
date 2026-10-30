(ns dk.cst.theophrastus.rules
  "Jena's rule reasoner: parse rules, make a reasoner of them, make an
  inference Model with it, and read its schema or check its consistency.

  Pass the inference Model to theo/db, and an entity of the db has the
  properties that the rules infer as :inferred in its metadata.

  See https://jena.apache.org/documentation/inference/"
  (:require [clojure.string :as str]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.txn :as txn])
  (:import [java.util List]
           [org.apache.jena.graph Graph]
           [org.apache.jena.rdf.model InfModel Model ModelFactory]
           [org.apache.jena.reasoner BaseInfGraph InfGraph Reasoner
            ReasonerRegistry ValidityReport ValidityReport$Report]
           [org.apache.jena.reasoner.rulesys GenericRuleReasoner Rule
            RuleReasoner]))

(defn parse
  "The Jena rules in `x` (a string of rule text, or a File, URL or resource
  with rule text in it), as a vector."
  [x]
  (let [^String text (if (string? x) x (slurp x))]
    (vec (Rule/parseRules text))))

(def modes
  "The modes of a GenericRuleReasoner, by keyword."
  {:hybrid       GenericRuleReasoner/HYBRID
   :forward      GenericRuleReasoner/FORWARD
   :forward-rete GenericRuleReasoner/FORWARD_RETE
   :backward     GenericRuleReasoner/BACKWARD})

(defn reasoner
  "A GenericRuleReasoner of the `rules`, with these `opts`:

  - :mode, a key of modes (:hybrid when not given);
  - :transitive-closure-caching?, to cache the closures of
    rdfs:subClassOf and rdfs:subPropertyOf (false when not given);
  - :owl-translation?, for the OWL rules (false when not given)."
  ([rules]
   (reasoner rules nil))
  ([rules {:keys [mode transitive-closure-caching? owl-translation?]
           :or   {mode :hybrid}
           :as   opts}]
   (doto (GenericRuleReasoner. ^List rules)
     (.setMode (or (modes mode)
                   (throw (ex-info (str "Not a mode: " mode) {:mode mode}))))
     (.setTransitiveClosureCaching (boolean transitive-closure-caching?))
     (.setOWLTranslation (boolean owl-translation?)))))

(defn- model
  "The Model of `x`: a db, a Model or a Graph."
  ^Model [x]
  (cond
    (instance? Model x) x
    (instance? Graph x) (ModelFactory/createModelForGraph x)
    (map? x)            (theo/jena-model x)
    :else (throw (ex-info (str "Not a db, Model or Graph: " (pr-str x))
                          {:x x}))))

(defn inf-model
  "An inference Model of the `reasoner` over the `data`, with the `schema`
  when given. Each is a db, a Model or a Graph, and the data is a new
  in-memory Model when there is none, as (theo/db {:transactional? false})
  makes it."
  (^InfModel [reasoner]
   (inf-model reasoner (theo/db {:transactional? false})))
  (^InfModel [^Reasoner reasoner data]
   (ModelFactory/createInfModel reasoner (model data)))
  (^InfModel [^Reasoner reasoner schema data]
   (ModelFactory/createInfModel reasoner (model schema) (model data))))

(defn schema
  "A db of the schema of the inference Model of `db`, with the same prefixes,
  e.g. for shacl/owl-shapes, or nil when there is none."
  [{:keys [prefixes] :as db}]
  (let [g (theo/jena-graph db)]
    (when-let [schema-graph (when (instance? BaseInfGraph g)
                              (.getSchemaGraph ^BaseInfGraph g))]
      ;; not theo/db, which would write the prefixes to the schema
      (theo/->Db (ModelFactory/createModelForGraph schema-graph)
                 nil
                 prefixes
                 nil))))

;; Jena prints the type and the text of a rule's report as literals, and
;; the text is the first line of the description
(defn- without-quotes
  "The string `s` without the quotes around its first line, if it has them."
  [s]
  (str/replace-first s #"^\"(.*)\"(?=\n|$)" "$1"))

(defn validate
  "The consistency report of the reasoner of the inference db `db`, as a map
  of :valid? (no errors), :clean? (no warnings either) and :reports. Each
  report is a map of :error?, :type and :description. It's made in one read
  transaction, and it throws when `db` isn't a db of an inference Model.

  Only rules that check consistency report anything, e.g. owl-rules."
  [db]
  (let [g (theo/jena-graph db)]
    (if (instance? InfGraph g)
      (txn/calculate-read
        db
        #(let [^ValidityReport report (.validate ^InfGraph g)]
           {:valid?  (.isValid report)
            :clean?  (.isClean report)
            :reports (mapv (fn [^ValidityReport$Report r]
                             {:error?      (.isError r)
                              :type        (without-quotes (.getType r))
                              :description (without-quotes
                                             (.getDescription r))})
                           (iterator-seq (.getReports report)))}))
      (throw (ex-info "Not a db of an inference Model" {:db db})))))

(def owl-rules
  "The largest set of OWL rules that Jena supports, as a vector."
  (vec (.getRules ^RuleReasoner (ReasonerRegistry/getOWLReasoner))))

(def owl-mini-rules
  "The OWL rules of Jena's mini reasoner, as a vector."
  (vec (.getRules ^RuleReasoner (ReasonerRegistry/getOWLMiniReasoner))))

(def table-all
  "The rule that calls tableAll, which keeps backward rules from going
  round in circles."
  (Rule/parseRule "-> tableAll()."))
