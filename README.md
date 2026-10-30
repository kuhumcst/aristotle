Theophrastus
============

> [Aristotle][Aristotle the philosopher] bequeathed his own library to [Theophrastus], to whom he also left his school.
>
> — [Strabo], [_Geography_ 13.1.54][13.1.54]

At the [Centre for Language Technology] we have used [Aristotle] as our RDF library of choice for [DanNet] since we started building its new version in 2020. We even made a few contributions to Aristotle itself. Over the years we gained a lot of experience with the library and with [Apache Jena], which it wraps. Our needs led to several extensions, which piled up in the DanNet source code.

Theophrastus is our **total makeover** of Aristotle. It's based on Apache Jena 6 and adds:

- an API more [like Datomic's]
- support for persistent [TDB2] databases
- transactions, which TDB2 needs
- support for building a [SPARQL endpoint]
- [SHACL] validation

**NOTE:** we removed support for [Aristotle's query language] (based on [ARQ]). Complex queries were too hard to build with it, and it was poorly documented. Theophrastus still supports SPARQL, so you can write a query by hand or with any library that produces SPARQL, e.g. [Flint] for queries as Clojure data.

[Centre for Language Technology]: https://cst.ku.dk/english
[Aristotle]: https://github.com/arachne-framework/aristotle
[DanNet]: https://github.com/kuhumcst/DanNet
[Apache Jena]: https://jena.apache.org
[Aristotle's query language]: https://github.com/arachne-framework/aristotle#query
[ARQ]: https://www.w3.org/2011/09/SparqlAlgebra/ARQalgebra
[like Datomic's]: https://docs.datomic.com/clojure/index.html
[TDB2]: https://jena.apache.org/documentation/tdb2/
[SPARQL endpoint]: https://www.w3.org/TR/sparql11-protocol/
[SHACL]: https://www.w3.org/TR/shacl/
[Flint]: https://github.com/yetanalytics/flint
[Aristotle the philosopher]: https://en.wikipedia.org/wiki/Aristotle
[Theophrastus]: https://en.wikipedia.org/wiki/Theophrastus
[Strabo]: https://en.wikipedia.org/wiki/Strabo
[13.1.54]: https://www.perseus.tufts.edu/hopper/text?doc=Perseus:text:1999.01.0198:book=13:chapter=1:section=54

Installation
------------

Theophrastus needs Clojure 1.12+ and Java 21+. It isn't released yet. Add
it to your `deps.edn` as a [Git dependency]:

```clojure
dk.cst/theophrastus {:git/url "https://github.com/kuhumcst/theophrastus"
                     :git/sha "..."}
```

Usage
-----

Create an in-memory db with your prefixes and add some data. The `ex`
prefix lets you write `<http://example.com/alice>` as `:ex/alice`:

```clojure
(require '[dk.cst.theophrastus :as theo]
         '[dk.cst.theophrastus.term :as term])

(def prefixes
  (assoc term/default-prefixes :ex "<http://example.com/>"))

(def db
  (theo/db {:prefixes prefixes}))

(theo/transact! db [{:rdf/about  :ex/alice
                     :rdfs/label "Alice"
                     :ex/knows   #{:ex/bob :ex/carol}}
                    [:ex/bob :rdfs/label "Bob"]
                    [:ex/carol :rdfs/label "Carol"]])
```

### Queries and lookups

You can query the db with [SPARQL] (with optional bindings) or look up a
single subject as a map:

```clojure
(theo/q db
        "SELECT ?name WHERE { ?p ex:knows ?f . ?f rdfs:label ?name }"
        {'?p :ex/alice})
;; => [{?name "Bob"} {?name "Carol"}]

(theo/entity db :ex/alice)
;; => {:rdf/about  :ex/alice
;;     :rdfs/label "Alice"
;;     :ex/knows   #{:ex/bob :ex/carol}}
```

You can also pull nested data with a [Datomic-style pattern]:

```clojure
(theo/pull db [:rdfs/label {:ex/knows [:rdfs/label]}] :ex/alice)
;; => {:rdf/about  :ex/alice
;;     :rdfs/label "Alice"
;;     :ex/knows   #{{:rdf/about :ex/bob :rdfs/label "Bob"}
;;                   {:rdf/about :ex/carol :rdfs/label "Carol"}}}
```

### Named graphs

A [named graph] can be used as a db. So can the union of all the named
graphs:

```clojure
(theo/transact! (theo/named-graph db :ex/staff) [[:ex/dave :rdfs/label "Dave"]])

(theo/entity (theo/union-graph db) :ex/dave)
;; => {:rdf/about :ex/dave :rdfs/label "Dave"}
```

### Persistence and transactions

Write the data to a file and load it into a [TDB2] database on disk:

```clojure
(require '[dk.cst.theophrastus.tdb2 :as tdb2])

(theo/write db "people.ttl")

(def tdb2-db
  (theo/read! (tdb2/connect "db/example" {:prefixes prefixes}) "people.ttl"))
```

To run several operations in one transaction, use the txn macros:

```clojure
(require '[dk.cst.theophrastus.txn :as txn])

(txn/with-write-transaction tdb2-db
  (theo/retract! tdb2-db [:ex/alice :rdfs/label '_])
  (theo/transact! tdb2-db [[:ex/alice :rdfs/label "Alice Smith"]]))
```

### Inference and validation

Inference uses [Jena rules]. This one makes `ex:knows` symmetric:

```clojure
(require '[dk.cst.theophrastus.rules :as rules])

(def reasoner
  (rules/reasoner
    (rules/parse "@prefix ex: <http://example.com/>.
                  [(?a ex:knows ?b) -> (?b ex:knows ?a)]")))

(def inf-db
  (theo/db (rules/inf-model reasoner db) {:prefixes prefixes}))

(theo/entity inf-db :ex/carol)
;; => {:rdf/about :ex/carol :rdfs/label "Carol" :ex/knows :ex/alice}
```

[SHACL] validation returns its report as plain Clojure data:

```clojure
(require '[dk.cst.theophrastus.shacl :as shacl])

(shacl/validate db "shapes.ttl")
;; => {:conforms? false
;;     :results   [{:focus-node :ex/alice :path :ex/age ...}]}
```

### SPARQL endpoint

You can serve a db as a read-only [SPARQL endpoint] through a [Ring]
handler or [Pedestal interceptors]:

```clojure
(require '[dk.cst.theophrastus.endpoint.ring :as endpoint.ring])

(def handler
  (endpoint.ring/handler {:db db :limit 1000}))
```

Documentation
-------------

Every public function has a docstring with the details. The following
pages cover the rest:

- [Design](doc/design.md): why the library works the way it does
- [From Aristotle](doc/from-aristotle.md): what has changed if you're
  coming from Aristotle
- [SPARQL endpoint](doc/endpoint.md): how the endpoint handles requests and
  what it does where the protocol leaves the choice open

Not yet
-------

- triple terms and base direction from [RDF 1.2] (they throw for now)
- reading and writing TriG, N-Quads and other syntaxes with several graphs
- a query that returns a set of tuples
- one value or a set by a schema (e.g. SHACL's sh:maxCount 1)
- queries in Datomic's syntax
- updates through the SPARQL endpoint and the [Graph Store Protocol]

Development
-----------

```bash
clojure -X:test                # the tests on the JVM
npm install                    # once, for the Node tests
clojure -M:cljs compile test   # the tests of the term namespace in Node
clojure -M:test:nrepl          # an nREPL with the tests on the classpath
clojure -T:build jar           # a jar in target
```

CI also lints `src` and `test` with [clj-kondo].

License
-------

The Theophrastus project is licensed under the
[Apache License 2.0](LICENSE.txt). It began as a fork of [Aristotle] (see
the [NOTICE](NOTICE)).

[Git dependency]: https://clojure.org/reference/deps_edn#deps_git
[SPARQL]: https://www.w3.org/TR/sparql11-query/
[Datomic-style pattern]: https://docs.datomic.com/query/query-pull.html
[named graph]: https://www.w3.org/TR/rdf11-concepts/#section-dataset
[Jena rules]: https://jena.apache.org/documentation/inference/#rules
[Ring]: https://github.com/ring-clojure/ring
[Pedestal interceptors]: https://pedestal.io/pedestal/0.7/guides/what-is-an-interceptor.html
[RDF 1.2]: https://www.w3.org/TR/rdf12-concepts/
[Graph Store Protocol]: https://www.w3.org/TR/sparql11-http-rdf-update/
[clj-kondo]: https://github.com/clj-kondo/clj-kondo
