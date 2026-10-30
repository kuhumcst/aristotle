(ns dk.cst.theophrastus.impl.owl
  "The OWL restrictions of an ontology as SHACL shapes, for
  shacl/owl-shapes."
  (:require [clojure.string :as str]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.term :as term]))

;; OWL 2 Direct Semantics: an instance of a class is an instance of its
;; superclasses, its equivalent classes, every member of an intersection
;; it's equal to or below, and every union it's a member of, but not of each
;; member of a union
(def implied-class-path
  "The SPARQL path from a class to the class expressions that each of its
  instances belongs to."
  (str "(rdfs:subClassOf|owl:equivalentClass|^owl:equivalentClass"
       "|owl:intersectionOf/rdf:rest*/rdf:first"
       "|^rdf:first/^rdf:rest*/^(owl:unionOf|owl:disjointUnionOf))"))

(def implied-property-path
  "The SPARQL path from a property to the properties that each of its
  values is also a value of."
  "(rdfs:subPropertyOf|owl:equivalentProperty|^owl:equivalentProperty)")

(def inverse-path
  "The SPARQL pattern of the inverse ?i of the property ?f, which is ?f
  itself when it's symmetric."
  "{ ?f (owl:inverseOf|^owl:inverseOf) ?i }
   UNION { ?f a owl:SymmetricProperty BIND (?f AS ?i) }")

;; OWL 2 Structural Specification, section 8.3: onClass and onDataRange
;; only qualify the qualified cardinalities. The number is cast, since some
;; ontologies write it as a plain string.
(defn- cardinality-query
  "The query of the cardinality restrictions with the `plain` and
  `qualified` predicates, keeping those of more than `least`."
  [plain qualified least]
  (str "SELECT DISTINCT ?r ?p ?n ?class ?datatype WHERE {
          ?r owl:onProperty ?p .
          { ?r " plain " ?m }
          UNION { ?r owl:cardinality ?m }
          UNION { ?r " qualified " ?m .
                  OPTIONAL { ?r owl:onClass ?class }
                  OPTIONAL { ?r owl:onDataRange ?datatype } }
          UNION { ?r owl:qualifiedCardinality ?m .
                  OPTIONAL { ?r owl:onClass ?class }
                  OPTIONAL { ?r owl:onDataRange ?datatype } }
          BIND (xsd:integer(?m) AS ?n)
          FILTER (?n > " least ")
        }"))

(def restriction-queries
  "The query of the restrictions of each kind, with ?r for a restriction
  and ?p for its property. A cardinality has ?n and the ?class or ?datatype
  of a qualified one, a value restriction the ?filler, and owl:hasValue the
  ?value."
  {:min-cardinality  (cardinality-query "owl:minCardinality"
                                        "owl:minQualifiedCardinality"
                                        0)
   :max-cardinality  (cardinality-query "owl:maxCardinality"
                                        "owl:maxQualifiedCardinality"
                                        -1)
   :some-values-from "SELECT DISTINCT ?r ?p ?filler WHERE {
                        ?r owl:onProperty ?p ; owl:someValuesFrom ?filler
                      }"
   :all-values-from  "SELECT DISTINCT ?r ?p ?filler WHERE {
                        ?r owl:onProperty ?p ; owl:allValuesFrom ?filler
                      }"
   :has-value        "SELECT DISTINCT ?r ?p ?value WHERE {
                        ?r owl:onProperty ?p ; owl:hasValue ?value
                      }"})

(defn- datatype?
  "Whether the class expression `x` in the ontology `db` is a datatype
  rather than a class, by its namespace or its rdf:type."
  [{:keys [prefixes] :as db} x]
  (or (str/starts-with? (str (term/iri x prefixes))
                        "http://www.w3.org/2001/XMLSchema#")
      (contains? #{:rdfs/Literal :rdf/langString :rdf/PlainLiteral} x)
      (theo/q db "ASK { ?x a rdfs:Datatype }" {'?x x})))

(defn restrictions
  "The restrictions of `kind` in the ontology `db`, as rows of ?r, ?p and
  ?n, ?class, ?datatype or ?value. A value restriction's filler is its
  ?class or ?datatype."
  [db kind]
  (for [{:syms [?filler] :as row} (theo/q db (restriction-queries kind))]
    (cond-> (dissoc row '?filler)
      ?filler (assoc (if (datatype? db ?filler) '?datatype '?class)
                     ?filler))))

(defn subclasses
  "The named classes in the ontology `db` whose instances all belong to the
  class expression `x`, with `x` itself when it's named."
  [db x]
  (->> (theo/q db
               (str "SELECT DISTINCT ?c WHERE { ?c " implied-class-path "* ?x "
                    "FILTER (isIRI(?c)) }")
               {'?x x})
       (map '?c)
       (sort-by str)))

(defn- property-alternatives
  "The [property inverse?] pairs whose values are values of the named
  property `p` in the ontology `db`: its subproperties, and the inverses of
  them as inverse paths."
  [db p]
  (->> (theo/q db
               (str "SELECT DISTINCT ?q ?inverse WHERE {
                       { ?q " implied-property-path "* ?p
                         BIND (false AS ?inverse) }
                       UNION
                       { ?f " implied-property-path "* ?p .
                         " inverse-path "
                         ?q " implied-property-path "* ?i
                         BIND (true AS ?inverse) }
                       FILTER (isIRI(?q))
                     }")
               {'?p p})
       (map (juxt '?q '?inverse))
       (sort-by (juxt (comp str first) second))))

(defn alternatives
  "The [property inverse?] pairs whose values are values of the property
  `p` in the ontology `db`. A property expression such as
  [owl:inverseOf ex:p] has those of ex:p the other way round."
  [db p]
  (if-let [x (when (symbol? p)
               (some-> (theo/q db "SELECT ?x WHERE { ?p owl:inverseOf ?x }"
                               {'?p p})
                       (first)
                       (get '?x)))]
    (map (fn [[q inverse?]] [q (not inverse?)]) (property-alternatives db x))
    (property-alternatives db p)))

(defn shacl-path
  "The SHACL path of the [property inverse?] `alternatives`, or nil when
  there are none."
  [alternatives]
  (let [paths (map (fn [[q inverse?]] (if inverse? {:sh/inversePath q} q))
                   alternatives)]
    (if (next paths)
      {:sh/alternativePath (theo/rdf-list paths)}
      (first paths))))

;; RDF Schema 1.1, sections 3.1 and 3.2: the objects of a property are
;; instances of its range, and the subjects instances of its domain
(defn value-classes
  "The named classes and datatypes in the ontology `db` that every value of
  the property `q` belongs to, or of its inverse when it's `inverse?`: the
  ranges of the property and its superproperties, the domains of their
  inverses, and what those imply."
  [db [q inverse?]]
  (let [[own other] (if inverse?
                      ["rdfs:domain" "rdfs:range"]
                      ["rdfs:range" "rdfs:domain"])]
    (->> (theo/q db
                 (str "SELECT DISTINCT ?c WHERE {
                         ?q " implied-property-path "* ?f .
                         { ?f " own " ?d }
                         UNION { " inverse-path " ?i " other " ?d }
                         ?d " implied-class-path "* ?c
                         FILTER (isIRI(?c))
                       }")
                 {'?q q})
         (map '?c)
         (set))))

(defn- value-shape
  "The constraints that each value of the `classes` or the `datatype`
  meets, or nil when there are no classes."
  [classes datatype]
  (cond
    (= :rdfs/Literal datatype) {:sh/nodeKind :sh/Literal}
    datatype                   {:sh/datatype datatype}
    (next classes)             {:sh/or (theo/rdf-list
                                         (map #(hash-map :sh/class %)
                                              classes))}
    (seq classes)              {:sh/class (first classes)}))

(defn- constraints
  "The constraints of the property shape of the restriction `row` of `kind`,
  or nil when it constrains nothing. A `qualified?` one counts only the
  values that meet the `value-shape`."
  [kind {:syms [?n ?value] :as row} qualified? value-shape]
  (case kind
    :min-cardinality  (if qualified?
                        {:sh/qualifiedValueShape value-shape
                         :sh/qualifiedMinCount   ?n}
                        {:sh/minCount ?n})
    :max-cardinality  (if qualified?
                        {:sh/qualifiedValueShape value-shape
                         :sh/qualifiedMaxCount   ?n}
                        {:sh/maxCount ?n})
    :some-values-from (if qualified?
                        {:sh/qualifiedValueShape value-shape
                         :sh/qualifiedMinCount   1}
                        {:sh/minCount 1})
    :all-values-from  (when qualified? value-shape)
    :has-value        (when-not (symbol? ?value) {:sh/hasValue ?value})))

(defn shape
  "The node shape of the restriction `row` of `kind` in the ontology `db`,
  or nil when no named class has the restriction or it can't be described,
  e.g. with a datatype that is itself a restriction.

  A qualified restriction counts only the values of its class or datatype,
  unless the ranges of its properties already say that every value is one,
  in which case it's checked as unqualified."
  [db kind {:syms [?r ?p ?class ?datatype] :as row}]
  (let [qualifier  (or ?datatype
                       (when-not (#{:owl/Thing :rdfs/Resource} ?class) ?class))
        targets    (subclasses db ?r)
        alts       (alternatives db ?p)
        qualified? (and qualifier
                        (not-every? #(contains? (value-classes db %) qualifier)
                                    alts))
        values     (when qualified?
                     (value-shape (when ?class (subclasses db ?class))
                                  ?datatype))
        property   (when (or (not qualified?) values)
                     (constraints kind row qualified? values))]
    (when (and (seq targets)
               (seq alts)
               (not (symbol? ?datatype))
               property)
      {:rdf/type       :sh/NodeShape
       :sh/targetClass (set targets)
       :sh/property    (assoc property :sh/path (shacl-path alts))})))
