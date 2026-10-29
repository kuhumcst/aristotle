(ns dk.cst.theophrastus.rules
  "Jena's rule reasoner: parse rules, make a reasoner of them, and make an
  inference Model with it.

  Pass the inference Model to theo/db, and an entity of the db has the
  properties that the rules infer as :inferred in its metadata.

  See https://jena.apache.org/documentation/inference/"
  (:import [java.util List]
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

(defn inf-model
  "An inference Model of the `reasoner` over the `data` Model, with the
  `schema` Model when given."
  (^InfModel [^Reasoner reasoner ^Model data]
   (ModelFactory/createInfModel reasoner data))
  (^InfModel [^Reasoner reasoner ^Model schema ^Model data]
   (ModelFactory/createInfModel reasoner schema data)))

(def owl-rules
  "The largest set of OWL rules that Jena supports, as a vector."
  (vec (.getRules ^RuleReasoner (ReasonerRegistry/getOWLReasoner))))

(def mini-rules
  "The OWL rules of Jena's mini reasoner, as a vector."
  (vec (.getRules ^RuleReasoner (ReasonerRegistry/getOWLMiniReasoner))))

(def table-all
  "The rule that calls tableAll, which keeps backward rules from going
  round in circles."
  (Rule/parseRule "-> tableAll()."))
