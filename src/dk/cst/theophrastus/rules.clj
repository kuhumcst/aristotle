(ns dk.cst.theophrastus.rules
  "Jena's rule reasoner: parse rules, make a reasoner of them, and make an
  inference Model with it.

  Pass the inference Model to theo/db, and an entity of the db has the
  properties that the rules infer as :inferred in its metadata.

  See https://jena.apache.org/documentation/inference/"
  (:require [dk.cst.theophrastus :as theo])
  (:import [java.util List]
           [org.apache.jena.graph Graph]
           [org.apache.jena.rdf.model InfModel Model ModelFactory]
           [org.apache.jena.reasoner Reasoner ReasonerRegistry]
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
    :else (throw (ex-info (str "Not a db, Model or Graph: " x) {:x x}))))

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
