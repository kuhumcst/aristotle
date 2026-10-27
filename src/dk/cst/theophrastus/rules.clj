(ns dk.cst.theophrastus.rules
  "Jena's rule reasoner: rules, a reasoner of them, and an inference Model.

  Pass the inference Model to theophrastus/db, and an entity of the db
  then has the properties that the rules infer as :inferred in its
  metadata.

  See https://jena.apache.org/documentation/inference/"
  (:import [java.util List]
           [org.apache.jena.rdf.model InfModel Model ModelFactory]
           [org.apache.jena.reasoner Reasoner ReasonerRegistry]
           [org.apache.jena.reasoner.rulesys GenericRuleReasoner Rule
            RuleReasoner]))

(defn parse
  "The vector of the Jena rules of `x`: a string of rule text, or a File,
  URL or resource with rule text in it."
  [x]
  (let [^String text (if (string? x) x (slurp x))]
    (vec (Rule/parseRules text))))

(def modes
  "The modes of a GenericRuleReasoner, by keyword."
  {:hybrid       GenericRuleReasoner/HYBRID
   :forward      GenericRuleReasoner/FORWARD
   :forward-rete GenericRuleReasoner/FORWARD_RETE
   :backward     GenericRuleReasoner/BACKWARD})

(defn ->reasoner
  "The GenericRuleReasoner of the `rules`, with these `opts`:

  - :mode, one of the keys of modes, :hybrid unless given;
  - :transitive-closure-caching?, to cache the closures of
    rdfs:subClassOf and rdfs:subPropertyOf, false unless given;
  - :owl-translation?, for the OWL rules, false unless given."
  ([rules]
   (->reasoner rules nil))
  ([rules {:keys [mode transitive-closure-caching? owl-translation?]
           :or   {mode :hybrid}
           :as   opts}]
   (doto (GenericRuleReasoner. ^List rules)
     (.setMode (or (modes mode)
                   (throw (ex-info (str "Not a mode: " mode) {:mode mode}))))
     (.setTransitiveClosureCaching (boolean transitive-closure-caching?))
     (.setOWLTranslation (boolean owl-translation?)))))

(defn ->inf-model
  "The inference Model of the `reasoner` over the `data`, a Model, with the
  `schema` Model when given."
  (^InfModel [^Reasoner reasoner ^Model data]
   (ModelFactory/createInfModel reasoner data))
  (^InfModel [^Reasoner reasoner ^Model schema ^Model data]
   (ModelFactory/createInfModel reasoner schema data)))

(def owl-rules
  "The vector of the largest set of OWL rules that Jena supports."
  (vec (.getRules ^RuleReasoner (ReasonerRegistry/getOWLReasoner))))

(def mini-rules
  "The vector of the OWL rules of Jena's mini reasoner."
  (vec (.getRules ^RuleReasoner (ReasonerRegistry/getOWLMiniReasoner))))

(def table-all
  "The rule that calls tableAll, which keeps backward rules from going
  round in circles."
  (Rule/parseRule "-> tableAll()."))
