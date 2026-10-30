From Aristotle
==============

Theophrastus began as a fork of [Aristotle]. This page shows how to move
code from Aristotle to Theophrastus. In the examples, `aa` is
`arachne.aristotle` and `theo` is `dk.cst.theophrastus`. The other
namespaces go by the last part of their name, e.g. `rules` for
`dk.cst.theophrastus.rules`.

The biggest change is that you work with a db rather than a Jena Graph. A
db holds a Jena Model or Dataset together with its prefixes. Queries are
written in SPARQL instead of Aristotle's query data. Theophrastus also
needs Java 21 and Jena 6.

Prefixes
--------

Aristotle kept prefixes in a global registry. In Theophrastus you give a
db its prefixes when you make it:

```clojure
;; Aristotle
(registry/prefix 'ex "http://example.com/")

(def graph (aa/graph :simple))

;; Theophrastus
(def prefixes
  (assoc term/default-prefixes :ex "<http://example.com/>"))

(def db (theo/db {:prefixes prefixes}))
```

Wildcard prefixes, aliases and the `#rdf/prefix` tags are gone.

Writing and reading
-------------------

Replace `aa/add` with `theo/transact!`. It takes the same triples and maps.
Files work much as before, but `theo/write` gets the syntax from the name
of the file:

```clojure
;; Aristotle
(aa/add graph [{:rdf/about :ex/alice :ex/name "Alice" :ex/knows :ex/bob}
               {:rdf/about :ex/bob :ex/name "Bob"}])
(aa/write graph "people.ttl" :ttl)
(aa/read graph "people.ttl")

;; Theophrastus
(theo/transact! db [{:rdf/about :ex/alice :ex/name "Alice" :ex/knows :ex/bob}
                    {:rdf/about :ex/bob :ex/name "Bob"}])
(theo/write db "people.ttl")
(theo/read! db "people.ttl")
```

To give the syntax yourself, pass e.g. `{:syntax :ttl}`. Aristotle's
keywords `:ttl`, `:turtle`, `:ntriples`, `:rdfxml`, `:jsonld` and `:n3`
all work.

The functions for RDF lists and containers have moved from the graph
namespace to `theo/rdf-list`, `theo/rdf-bag`, `theo/rdf-seq` and
`theo/rdf-alt`.

Queries
-------

Write your queries in SPARQL. A query can use the prefixes of the db
without PREFIX lines. Bindings work as before:

```clojure
;; Aristotle
(query/run graph '[?name]
           '[:bgp [?p :ex/knows ?f] [?f :ex/name ?name]]
           '{?p :ex/alice})
;; => #{["Bob"]}

;; Theophrastus
(theo/q db
        "SELECT ?name WHERE { ?p ex:knows ?f . ?f ex:name ?name }"
        {'?p :ex/alice})
;; => [{?name "Bob"}]
```

A query returns a vector of maps. There's no binding vector that turns the
results into a set of tuples. To parse a query once and run it often, use
`theo/parse-query` instead of `query/build` or `query/parse`.

Pull
----

In `theo/pull` the pattern comes before the subject (as in Datomic):

```clojure
;; Aristotle
(query/pull graph :ex/alice [:ex/name {:ex/knows [:ex/name]}])

;; Theophrastus
(theo/pull db [:ex/name {:ex/knows [:ex/name]}] :ex/alice)
```

Aristotle's pull gave a property a single value only when an OWL
restriction on the subject's class allowed just one (owl:cardinality or
owl:maxCardinality 1). That usually took an OWL reasoner to infer the
class, and every other property came back as a set. In Theophrastus a
property has a single value when the data has one and a set when it has
several.

To read every property of a subject, use `theo/entity`.

Inference
---------

Make the reasoner yourself and pass its inference Model to `theo/db`.
Aristotle always turned on OWL translation and transitive closure caching.
Ask for both to get the same reasoner as `(aa/graph :jena-mini)`:

```clojure
;; Aristotle
(def graph (aa/graph :jena-mini))

;; Theophrastus
(def reasoner
  (rules/reasoner rules/owl-mini-rules
                  {:owl-translation?            true
                   :transitive-closure-caching? true}))

(def db
  (theo/db (rules/inf-model reasoner) {:prefixes prefixes}))
```

Write your own rules in Jena's rule syntax and parse them with
`rules/parse` instead of building them with `inference/rule`. To add more
rules, make a new reasoner with all of them.

Validation
----------

Theophrastus validates with [SHACL]. To check the min-cardinality
restrictions of an ontology as Aristotle's min-cardinality validator did,
make SHACL shapes of them with `shacl/owl-shapes`:

```clojure
;; Aristotle
(validation/validate graph [validation/min-cardinality])

;; Theophrastus
(shacl/validate db (shacl/owl-shapes ontology
                                     {:restrictions #{:min-cardinality}}))
```

Without `:restrictions` you also get shapes of `owl:maxCardinality`,
`owl:allValuesFrom` and `owl:hasValue` (and of `owl:someValuesFrom` if you
ask for it). The ontology can be the db itself, a db of its own, or the
schema of an inference db from `rules/schema`. The shapes follow
subclasses, subproperties, inverses and ranges in the ontology, so the data
needs no reasoner. They're a db too, so you can write them to a file and
edit them as ordinary SHACL.

Aristotle's `validate` took a vector of validators. To check several sets
of shapes at once, pass `shacl/validate` a coll of them instead, e.g. the
OWL shapes and a file of your own. Aristotle also added the reasoner's own
consistency checks to the results. In Theophrastus they come from
`rules/validate`:

```clojure
(rules/validate inf-db)
;; => {:valid? false
;;     :clean? false
;;     :reports [{:error? true :type "conflict" :description "..."}]}
```

Values
------

Some values convert differently now:

- A long becomes an xsd:integer instead of an xsd:long (as `1` does in
  SPARQL and Turtle).
- A query returns an xsd:decimal, date or time as a Literal instead of a
  BigDecimal, Date or XSDDateTime. On the JVM, `term/value` turns it into a
  BigDecimal or a java.time value.
- A string with a language tag is a LangString from the term namespace
  instead of ont-app's LangStr.
- A java.net.URL is no longer an IRI because it can change one. Use a
  java.net.URI or a string in angle brackets.
- A symbol that isn't a variable or a blank node throws. Aristotle turned
  it into a urn:clojure: IRI.
- A map with no properties adds nothing. Aristotle added an rdf:type
  rdfs:Resource.
- An RDF list has no rdf:type rdf:List (as in RDF 1.1 Turtle).
- A container starts at rdf:_1 instead of rdf:_0 (as in RDF Schema 1.1).
- The default prefixes no longer include owl2 (a namespace from an OWL 2
  draft).

Replace `graph/data` and `graph/node` with `convert/data` and
`convert/node`. Both also take the prefixes.

Gone
----

- the RDF/EDN syntax for .edn files
- `graph/graph->clj` (read each subject with `theo/entity` instead)
- `graph/reify` and `query/sparql`

[Aristotle]: https://github.com/arachne-framework/aristotle
[SHACL]: https://www.w3.org/TR/shacl/
