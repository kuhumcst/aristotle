Theophrastus
============

This library lets you work with RDF as Clojure data, on top of
[Apache Jena](https://jena.apache.org) 6. You keep the data in a Jena Model
or Dataset, in memory or on disk with TDB2. Then you query it with SPARQL,
read its subjects as maps and write Clojure data to it. The library was made
for [DanNet](https://github.com/kuhumcst/DanNet), the Danish WordNet. It
began as a fork of [Aristotle](https://github.com/arachne-framework/aristotle);
see [From Aristotle](doc/from-aristotle.md) for what changed.

- **An API like Datomic's.** If you know Datomic, `q`, `entity`, `pull` and
  `transact!` will look familiar. What they return follows RDF and SPARQL,
  e.g. a property has one value or a set of them.
- **SPARQL, with values as terms.** You write queries in SPARQL, or as data
  with Flint. The values you bind go into a query as RDF terms, never as
  text, so a value from a user can't change the query.
- **Terms as Clojure values.** An IRI is a keyword or a string in angle
  brackets, and a literal is a Clojure value. A literal with no Clojure type
  of its own prints with a tag and reads back. The terms also work in
  ClojureScript, e.g. in EDN sent to a browser.
- **Transactions where the store has them.** Each write is one transaction
  in TDB2 or any other Dataset with transactions, and you can group several
  writes in one.
- **Rules, functions and SHACL.** Build an inference Model from Jena rules,
  call Clojure functions from SPARQL, and get SHACL reports as data.
- **Close to the standards.** Where RDF, SPARQL or XSD has a rule, e.g. for
  what an IRI is or how language tags compare, the code follows it and
  cites it.

> This library was developed with assistance from frontier LLMs.

Getting started
---------------

The library needs Clojure 1.12+ and Java 21+. It isn't released yet, so add
it to the `:deps` of your `deps.edn` as a Git dependency, with the SHA of
the latest commit:

```clojure
dk.cst/theophrastus {:git/url "https://github.com/kuhumcst/theophrastus"
                     :git/sha "..."}
```

The term namespace also works in ClojureScript. In shadow-cljs a Git
dependency only works through `deps.edn`, so set `:deps true` in your
`shadow-cljs.edn` too.

Make a db of a Jena Dataset, here one in memory, and write some data to it:

```clojure
(require '[dk.cst.theophrastus :as theo]
         '[dk.cst.theophrastus.term :as term])
(import '[org.apache.jena.query DatasetFactory])

(def prefixes
  (assoc term/default-prefixes :ex "<http://example.com/>"))

(def db
  (theo/db (DatasetFactory/createTxnMem) {:prefixes prefixes}))

(theo/transact! db [{:rdf/about  :ex/alice
                     :rdfs/label "Alice"
                     :ex/knows   #{:ex/bob :ex/carol}}
                    [:ex/bob :rdfs/label "Bob"]
                    [:ex/carol :rdfs/label "Carol"]])
```

Then query it, or read a subject as a map:

```clojure
(theo/q "SELECT ?name WHERE { ?p ex:knows ?f . ?f rdfs:label ?name }"
        db
        {'?p :ex/alice})
;; => [{?name "Bob"} {?name "Carol"}]

(theo/entity db :ex/alice)
;; => {:rdfs/label "Alice" :ex/knows #{:ex/bob :ex/carol}}
```

Unlike a Datomic db, a db isn't a value, since later writes change it. The
functions that write to it end in `!` and return it.

RDF terms
---------

An IRI is a keyword when the db has its prefix, e.g. `:rdfs/label`, and
otherwise a string in angle brackets, e.g. `"<https://cst.dk>"`. Only a
string that matches the IRIREF rule of SPARQL, with a scheme, is an IRI, so
`"<b>bold</b>"` stays a string. A blank node is a symbol that starts with
`_`, e.g. `_b1`.

The prefixes map each prefix as a keyword to its namespace in angle
brackets, as Flint's do, and `term/default-prefixes` has rdf, rdfs, xsd and
owl. Jena stores the prefixes of a db with its data, so a TDB2 database
keeps them, and the files you write have them. When the prefixes you pass to
`theo/db` differ from the stored ones, they replace them.

A literal is a Clojure value when a type holds it exactly:

- a string for an xsd:string;
- a boolean or a number for xsd:boolean, xsd:double, xsd:float, and
  xsd:integer with the types derived from it;
- a LangStr for a string with a language tag;
- a Literal for any other literal, with its lexical form and datatype IRI.

LangStr and Literal print with a tag and read back from it:

```clojure
(term/->LangStr "hund" "da")
;; => #rdf/langstring ["hund" "da"]

(term/literal "1.50" :xsd/decimal)
;; => #rdf/literal ["1.50" "<http://www.w3.org/2001/XMLSchema#decimal>"]
```

Two LangStrs are equal when their tags only differ in case, as in RDF 1.2.
To read the tags as EDN, pass `term/read-langstring` and `term/read-literal`
as the readers.

On the JVM, `term/value` turns an xsd:decimal into a BigDecimal, and the
XSD date, time and duration types into java.time values. A query returns
these as Literals, since ClojureScript has no types that hold them exactly.
To write such a value, pass it as it is, or turn it into a Literal with
`term/literal`:

```clojure
(term/value (term/literal "1.50" :xsd/decimal))
;; => 1.50M

(term/literal (java.time.LocalDate/of 2024 1 2))
;; => #rdf/literal ["2024-01-02" "<http://www.w3.org/2001/XMLSchema#date>"]
```

Writing
-------

Add data with `theo/transact!`. It takes triples as vectors of three
values, maps of a subject's properties, and colls of these:

```clojure
(theo/transact! db [[:ex/alice :ex/age 41]
                    {:rdf/about :ex/bob
                     :ex/age    37
                     :ex/_knows :ex/carol}])
```

A map has the properties of its `:rdf/about`, or of a new blank node when
it has none:

- a set or a vector gives a property several values, and nil none;
- a nested map is a subject of its own;
- a predicate such as `:ex/_knows` links the other way.

The function converts all the data before it writes, so an error leaves
the db as it was. To write to a named graph of a Dataset, pass its IRI
after the data. A Jena Graph or Model works as data too.

Remove triples with `theo/retract!`, where `_` in a pattern matches
anything, and run a SPARQL Update with `theo/update!`:

```clojure
(theo/retract! db [[:ex/alice :ex/age '_]])

(theo/update! db "DELETE WHERE { ?p ex:age ?age }" {'?p :ex/bob})
```

Read RDF files with `theo/read!` and write one with `theo/write`. The file
name gives the syntax, e.g. Turtle for .ttl:

```clojure
(theo/write db "people.ttl")

(theo/read! db ["schema.ttl" "people.ttl"])
```

A syntax error in any of the files leaves the db as it was.

For an RDF list, use `theo/rdf-list` in a map, and for a container
`theo/rdf-bag`, `theo/rdf-seq` or `theo/rdf-alt`:

```clojure
(theo/transact! db {:rdf/about   :ex/alice
                    :ex/projects (theo/rdf-list [:ex/one :ex/two])})
```

Queries
-------

Run a SPARQL query with `theo/q`, the query first as in Datomic. It parses
a string with the prefixes of the db, so the query needs no PREFIX lines
for them.

A binding gives a variable one value or several, or gives several
variables rows of values, e.g. `{'[?p ?name] [[:ex/bob nil]]}` with nil for
unbound. The values go in as RDF terms in a VALUES block at the start of
the WHERE clause:

```clojure
(theo/q "SELECT ?p ?name WHERE { ?p rdfs:label ?name }"
        db
        {'?p [:ex/bob :ex/carol]})
;; => [{?p :ex/bob ?name "Bob"} {?p :ex/carol ?name "Carol"}]
```

A SELECT returns a vector of maps of variable to value, and an ASK a
boolean. A CONSTRUCT or DESCRIBE returns a new db of the triples, in
memory.

To parse a query once, e.g. one you run often, use `theo/query` with the
prefixes and pass the result to `theo/q`. After the bindings, an options map
can set a `:timeout` in milliseconds. Jena only checks it when the query
moves on to its next result, so for a hard limit, see
`txn/with-hard-timeout`.

To write queries as data, add [Flint](https://github.com/yetanalytics/flint)
to your deps and pass the string it formats to `theo/q`:

```clojure
(require '[com.yetanalytics.flint :as flint])

(def labels
  (flint/format-query {:prefixes prefixes
                       :select   '[?name]
                       :where    '[[?p :rdfs/label ?name]]}))

(theo/q labels db {'?p :ex/alice})
;; => [{?name "Alice"}]
```

Keep values from users in the bindings rather than in the Flint data, so
that they go in as RDF terms.

Entities
--------

Read the properties of a subject as a map with `theo/entity`:

```clojure
(theo/entity db :ex/alice)
;; => {:rdfs/label  "Alice"
;;     :ex/knows    #{:ex/bob :ex/carol}
;;     :ex/projects {:rdf/first :ex/one
;;                   :rdf/rest  {:rdf/first :ex/two :rdf/rest :rdf/nil}}}
```

A property has one value, or a set when the data has several. A blank node
among the values becomes a map of its own properties, as in a
[Concise Bounded Description](https://www.w3.org/submissions/CBD/). The
metadata of each map has its `:subject`. For a db of an inference Model,
the metadata of the entity also has the `:inferred` properties (see
[Rules](#rules-functions-and-shacl)).

Pull a pattern with `theo/pull`, in Datomic's order:

```clojure
(theo/pull db [:rdfs/label {:ex/knows [:rdfs/label]}] :ex/alice)
;; => {:rdf/about  :ex/alice
;;     :rdfs/label "Alice"
;;     :ex/knows   #{{:rdf/about :ex/bob :rdfs/label "Bob"}
;;                   {:rdf/about :ex/carol :rdfs/label "Carol"}}}
```

A pattern can also have `'*` for every predicate. A map such as
`{:ex/knows '...}` pulls the same pattern again from each value, and a
number in place of `'...` limits the depth.

Transactions and TDB2
---------------------

The functions that end in `!` each write in one transaction, and `theo/q`
and `theo/entity` each read in one. To run several functions in one
transaction, use the txn namespace:

```clojure
(require '[dk.cst.theophrastus.txn :as txn])

(txn/calculate-write
  (:dataset db)
  #(-> db
       (theo/retract! [[:ex/alice :rdfs/label '_]])
       (theo/transact! [[:ex/alice :rdfs/label "Alice Smith"]])))
```

A plain in-memory Model has no transactions, so other threads see a write
as it happens, and a failure partway through doesn't undo it. For a store
in memory with transactions, use `DatasetFactory/createTxnMem` as above.

Connect to a TDB2 database with `tdb2/connect`, which creates it when the
directory doesn't exist:

```clojure
(require '[dk.cst.theophrastus.tdb2 :as tdb2])

(def tdb2-db
  (tdb2/connect "db/example" {:prefixes prefixes}))
```

TDB2 only frees the space of deleted and changed data when you compact the
database with `tdb2/compact!`.

Rules, functions and SHACL
--------------------------

Build a reasoner from Jena rules with the rules namespace, and make a db of
its inference Model:

```clojure
(require '[dk.cst.theophrastus.rules :as rules])
(import '[org.apache.jena.rdf.model ModelFactory])

(def reasoner
  (rules/->reasoner
    (rules/parse "@prefix ex: <http://example.com/>.
                  [(?a ex:knows ?b) -> (?b ex:knows ?a)]")))

(def inf-db
  (theo/db (rules/->inf-model reasoner (ModelFactory/createDefaultModel))
           {:prefixes prefixes}))

(theo/transact! inf-db [[:ex/alice :ex/knows :ex/bob]])

(meta (theo/entity inf-db :ex/bob))
;; => {:subject :ex/bob :inferred {:ex/knows :ex/alice}}
```

For backward rules, add `rules/table-all` to the rules, so that a
symmetric rule such as this one doesn't loop. Jena's own OWL rules are
`rules/owl-rules` and `rules/mini-rules`.

Register a Clojure function for SPARQL with `function/register!`. It
takes and returns the same values as a query, and a nil from it leaves the
result unbound:

```clojure
(require '[dk.cst.theophrastus.function :as function]
         '[clojure.string :as str])

(function/register! :ex/upper str/upper-case prefixes)

(theo/q "SELECT (ex:upper(?n) AS ?u) WHERE { ex:bob rdfs:label ?n }" db)
;; => [{?u "BOB"}]
```

Jena has one registry of functions for the whole JVM, so every query can
call the function.

Validate a db against SHACL shapes with `shacl/validate`. The report is
data, with the same values as a query and the sh prefix for SHACL's terms:

```clojure
(require '[dk.cst.theophrastus.shacl :as shacl])

(def report
  (shacl/validate db (shacl/parse "shapes.ttl")))

(:conforms? report)
;; => false

(-> report :entries first (select-keys [:focus-node :path :severity]))
;; => {:focus-node :ex/alice :path :ex/age :severity :sh/Violation}
```

Not yet
-------

- triple terms and base direction from RDF 1.2, which throw for now
- TriG, N-Quads and other syntaxes with several graphs, for reading and
  writing files
- a query that returns a set of tuples
- one value or a set by a schema, e.g. SHACL's sh:maxCount 1
- queries in Datomic's syntax
- a release on Clojars

Development
-----------

```bash
clojure -X:test                # the tests on the JVM
npm install                    # once, for the Node tests
clojure -M:cljs compile test   # the tests of the term namespace in Node
clojure -M:test:nrepl          # an nREPL with the tests on the classpath
clojure -T:build jar           # a jar in target
```

CI also lints `src` and `test` with clj-kondo.

The public namespaces are the root, `term`, `txn`, `tdb2`, `rules`,
`function` and `shacl`. The internal one is `impl.convert`, which converts
between Jena nodes and Clojure values.

License
-------

The Theophrastus project is licensed under the
[Apache License 2.0](LICENSE.txt). It began as a fork of Aristotle, which
the [NOTICE](NOTICE) credits.
