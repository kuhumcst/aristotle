(ns arachne.aristotle.inference
  "Jena's OWL rule sets and its tableAll rule, for a GenericRuleReasoner.

  See https://jena.apache.org/documentation/inference/"
  (:import [org.apache.jena.reasoner ReasonerRegistry]
           [org.apache.jena.reasoner.rulesys Rule RuleReasoner]))

(def owl-rules
  "The maximal set of OWL rules supported by Jena"
  (.getRules ^RuleReasoner (ReasonerRegistry/getOWLReasoner)))

(def mini-rules
  "The OWL rules supported by Jena's mini Reasoner"
  (.getRules ^RuleReasoner (ReasonerRegistry/getOWLMiniReasoner)))

(def table-all
  "Rule that calls the built in TableAll directive. This usually
   desirable, to prevent infinite circular backwards inferences."
  (Rule/parseRule "-> tableAll()."))
