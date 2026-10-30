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

Make a db in memory and write some data to it:

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

Then query it, or read a subject as a map:

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

Unlike a Datomic db, a db isn't a value, since later writes change it. The
functions that write to it end in `!` and return it.

RDF terms
---------

An IRI is a keyword when the db has its prefix, e.g. `:rdfs/label`, and
otherwise a string in angle brackets, e.g. `"<https://cst.dk>"`. Only a
string that matches SPARQL's IRIREF rule, with a scheme, is an IRI, so
`"<b>bold</b>"` stays a string. A blank node is a symbol that starts with
`_`, e.g. `_b1`.

The prefixes map each prefix (a keyword) to its namespace in angle
brackets, as Flint's do, and `term/default-prefixes` has rdf, rdfs, xsd and
owl. Jena stores them with the data, so a TDB2 database keeps them and the
files you write have them. The prefixes you pass to `theo/db` replace the
stored ones.

A literal is a Clojure value when a type holds it exactly:

- a string for an xsd:string;
- a boolean or a number for xsd:boolean, xsd:double, xsd:float, and
  xsd:integer with the types derived from it;
- a LangString for a string with a language tag;
- a Literal for any other literal, with its lexical form and datatype IRI.

LangString and Literal print with a tag and read back from it:

```clojure
(term/->LangString "hund" "da")
;; => #rdf/langstring ["hund" "da"]

(term/literal "1.50" :xsd/decimal)
;; => #rdf/literal ["1.50" "<http://www.w3.org/2001/XMLSchema#decimal>"]
```

Two LangStrings are equal when their tags only differ in case, as in RDF 1.2.
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

To write values of a type of your own, extend `term/AsIri` when the type
stands for an IRI, or `term/AsLiteral` when it has a literal. Both also work
in ClojureScript. For any other RDF term, extend `convert/AsNode`, which
turns a value into a Jena node.

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

The data is all converted before anything is written, so an error leaves
the db as it was. Another db works as data too, e.g. the result of a
CONSTRUCT, and so does a Jena Graph or Model.

Remove triples with `theo/retract!`, where `_` in a pattern matches
anything, and run a SPARQL Update with `theo/update!`:

```clojure
(theo/retract! db [[:ex/alice :ex/age '_]])

(theo/update! db "DELETE WHERE { ?p ex:age ?age }" {'?p :ex/bob})
```

To parse an update once and run it often, pass the result of
`theo/parse-update` to `theo/update!`.

Read RDF files with `theo/read!` and write one with `theo/write`. The file
name gives the syntax, e.g. Turtle for .ttl, and all the files are parsed
before anything is added:

```clojure
(theo/write db "people.ttl")

(theo/read! db ["schema.ttl" "people.ttl"])
```

For a stream, or a file whose name doesn't give the syntax, pass the
syntax as a media type or a keyword, e.g. `:turtle` or `:ttl`. Pass
`:base` too for the IRI that relative IRIs resolve against:

```clojure
(theo/read! db (:body response) {:syntax "text/turtle"
                                 :base   "https://example.com/"})

(with-out-str (theo/write db *out* {:syntax :nt}))
```

For an RDF list, use `theo/rdf-list` in a map, and for a container
`theo/rdf-bag`, `theo/rdf-seq` or `theo/rdf-alt`:

```clojure
(theo/transact! db {:rdf/about   :ex/alice
                    :ex/projects (theo/rdf-list [:ex/one :ex/two])})
```

Graphs
------

The functions read and write the default graph of a db of a Dataset, and
a query can also reach the named graphs with `GRAPH`. For the db of one
graph, use `theo/named-graph`, `theo/default-graph` or `theo/union-graph`,
the union of the named graphs:

```clojure
(def staff (theo/named-graph db :ex/staff))

(theo/transact! staff [[:ex/dave :rdfs/label "Dave"]])

(theo/entity (theo/union-graph db) :ex/dave)
;; => {:rdf/about :ex/dave :rdfs/label "Dave"}
```

Each one is a cheap view of the Dataset with the same prefixes, and the
union can only be read.

A view sees only its own graph. To keep the named graphs in reach of
`GRAPH`, give the db another default graph with the `:default-graph`
option of `theo/db`: `:union`, the IRI of a named graph, or a set of IRIs
for the union of those graphs. Writes go into a named graph, and a union
can only be read:

```clojure
(def everyone
  (theo/db (theo/jena-dataset db) {:prefixes prefixes :default-graph :union}))

(theo/entity everyone :ex/dave)
;; => {:rdf/about :ex/dave :rdfs/label "Dave"}
```

On TDB2, `:union` uses TDB2's own union default graph, which runs a query
on its indexes.

Queries
-------

Run a SPARQL query with `theo/q`. Unlike Datomic's `q`, it takes the db
first, like every other function that takes a db. It parses a string with
the prefixes of the db, so the query needs no PREFIX lines for them.

A binding gives a variable one value or several, or gives several
variables rows of values, e.g. `{'[?p ?name] [[:ex/bob nil]]}` with nil for
unbound. The values go in as RDF terms in a VALUES block at the start of
the WHERE clause:

```clojure
(theo/q db
        "SELECT ?p ?name WHERE { ?p rdfs:label ?name }"
        {'?p [:ex/bob :ex/carol]})
;; => [{?p :ex/bob ?name "Bob"} {?p :ex/carol ?name "Carol"}]
```

A SELECT returns a vector of maps of variable to value, an ASK a boolean,
and a CONSTRUCT or DESCRIBE a new db of the triples, in memory.

To parse a query once and run it often, pass the result of
`theo/parse-query` to `theo/q`. After the bindings, an options map can set
a `:timeout` in milliseconds, for `theo/update!` too. Jena only checks it
when the query moves on to its next result, so for a hard limit, see
`txn/with-hard-timeout`.

For a result too large to hold in memory, `theo/reducible` takes the same
arguments and runs a SELECT as you reduce over it, one row at a time:

```clojure
(with-open [w (clojure.java.io/writer "labels.txt")]
  (run! (fn [{:syms [?l]}] (.write w (str ?l "\n")))
        (theo/reducible db "SELECT ?l WHERE { ?s rdfs:label ?l }")))
```

Its read transaction lasts as long as the reduction. Use it with `reduce`,
`into`, `transduce` or `run!`, since `first` and `count` don't work on it.

To write queries as data, add [Flint](https://github.com/yetanalytics/flint)
to your deps and pass the string it formats to `theo/q`:

```clojure
(require '[com.yetanalytics.flint :as flint])

(def labels
  (flint/format-query {:prefixes prefixes
                       :select   '[?name]
                       :where    '[[?p :rdfs/label ?name]]}))

(theo/q db labels {'?p :ex/alice})
;; => [{?name "Alice"}]
```

Keep values from users in the bindings rather than in the Flint data, so
that they go in as RDF terms.

Entities
--------

Read the properties of a subject as a map with `theo/entity`:

```clojure
(theo/entity db :ex/alice)
;; => {:rdf/about   :ex/alice
;;     :rdfs/label  "Alice"
;;     :ex/knows    #{:ex/bob :ex/carol}
;;     :ex/projects {:rdf/about _b1
;;                   :rdf/first :ex/one
;;                   :rdf/rest  {:rdf/about _b2
;;                               :rdf/first :ex/two
;;                               :rdf/rest  :rdf/nil}}}
```

The subject is the `:rdf/about` of the map, so an entity can go back into
`transact!` as it is, and a subject with no properties is nil. A property
has one value, or a set when the data has several. A blank node among the
values becomes a map of its own properties, as in a
[Concise Bounded Description](https://www.w3.org/submissions/CBD/). For a
db of an inference Model, the metadata of the entity has the `:inferred`
properties (see [Rules](#rules-functions-and-shacl)).

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
number in place of `'...` limits the depth. A reverse predicate such as
`:ex/_knows`, as in `transact!`, pulls the subjects that link to this one.

To read the triples that match a pattern of `retract!` instead, use
`theo/triples`:

```clojure
(theo/triples db ['_ :ex/knows :ex/bob])
;; => [[:ex/alice :ex/knows :ex/bob]]
```

Transactions and TDB2
---------------------

The functions that end in `!` each write in one transaction, and `theo/q`,
`theo/triples`, `theo/entity` and `theo/pull` each read in one. To run
several functions in one transaction, use the macros of the txn namespace
with the db:

```clojure
(require '[dk.cst.theophrastus.txn :as txn])

(txn/with-write-transaction db
  (theo/retract! db [[:ex/alice :rdfs/label '_]])
  (theo/transact! db [[:ex/alice :rdfs/label "Alice Smith"]]))
```

`txn/with-read-transaction` does the same for reads. For a block that only
writes some of the time, `txn/with-transaction` starts as a read and
becomes a write at the first write:

```clojure
(txn/with-transaction db
  (when-not (theo/entity db :ex/bob)
    (theo/transact! db [[:ex/bob :rdfs/label "Bob"]])))
```

That first write fails if another one has committed in the meantime, so a
block that always writes is better off with `txn/with-write-transaction`.
Each macro also comes as a function that takes a function of no arguments,
e.g. `txn/calculate-write`.

The db that `theo/db` makes in memory has transactions. With
`:transactional? false`, it's a plain Model instead, which is faster to
fill and read, e.g. for a large import or the schema of a reasoner. Other
threads then see each write as it happens, a failure partway through isn't
undone, and the Model has only one graph.

Connect to a TDB2 database with `tdb2/connect`, which creates it when the
directory doesn't exist:

```clojure
(require '[dk.cst.theophrastus.tdb2 :as tdb2])

(def tdb2-db
  (tdb2/connect "db/example" {:prefixes prefixes}))
```

TDB2 only frees the space of deleted and changed data when you compact the
database with `tdb2/compact!`. To back it up as gzipped N-Quads, use
`tdb2/backup`, which returns the path of the file.

Rules, functions and SHACL
--------------------------

Build a reasoner from Jena rules with the rules namespace, and make a db of
its inference Model:

```clojure
(require '[dk.cst.theophrastus.rules :as rules])

(def reasoner
  (rules/reasoner
    (rules/parse "@prefix ex: <http://example.com/>.
                  [(?a ex:knows ?b) -> (?b ex:knows ?a)]")))

(def inf-db
  (theo/db (rules/inf-model reasoner) {:prefixes prefixes}))

(theo/transact! inf-db [[:ex/alice :ex/knows :ex/bob]])

(meta (theo/entity inf-db :ex/bob))
;; => {:inferred {:ex/knows :ex/alice}}
```

With no data, `rules/inf-model` infers over a new in-memory Model. For
inference over existing data, pass a db, a Model or a Graph, e.g.
`(theo/union-graph tdb2-db)`, and pass a schema before the data when it
has one.

For backward rules, add `rules/table-all` to the rules, so that a
symmetric rule such as this one doesn't loop. Jena's own OWL rules are
`rules/owl-rules` and `rules/owl-mini-rules`.

Register a Clojure function for SPARQL with `function/register!`. It
takes and returns the same values as the query that calls it, and a nil
from it leaves the result unbound:

```clojure
(require '[dk.cst.theophrastus.function :as function]
         '[clojure.string :as str])

(function/register! :ex/upper str/upper-case prefixes)

(theo/q db "SELECT (ex:upper(?n) AS ?u) WHERE { ex:bob rdfs:label ?n }")
;; => [{?u "BOB"}]
```

Jena has one registry of functions for the whole JVM, so every query can
call it.

Validate a db against SHACL shapes with `shacl/validate`. The report is
data, with the same values as a query and the sh prefix for SHACL's terms:

```clojure
(require '[dk.cst.theophrastus.shacl :as shacl])

(def report
  (shacl/validate db (shacl/parse "shapes.ttl")))

(:conforms? report)
;; => false

(-> report :results first (select-keys [:focus-node :path :severity]))
;; => {:focus-node :ex/alice :path :ex/age :severity :sh/Violation}
```

SPARQL endpoint
---------------

To serve a db as a read-only SPARQL endpoint, as the
[SPARQL 1.1 Protocol](https://www.w3.org/TR/sparql11-protocol/) defines
it, make a Ring handler:

```clojure
(require '[dk.cst.theophrastus.endpoint.ring :as endpoint.ring])

(def sparql
  (endpoint.ring/handler {:db db :limit 1000}))

(-> (sparql {:request-method :get
             :headers        {"accept" "text/csv"}
             :query-string   "query=SELECT%20%3Fname%20%7B%20%3Fp%20rdfs%3Alabel%20%3Fname%20%7D"})
    (update :body slurp))
;; => {:status  200
;;     :headers {"Content-Type"            "text/csv;charset=utf-8"
;;               "Vary"                    "Accept"
;;               "X-Content-Type-Options"  "nosniff"
;;               "Content-Security-Policy" "default-src 'none'; sandbox"}
;;     :body    "name\r\nAlice\r\nCarol\r\nBob\r\n"}
```

It reads the query from a GET, a form in a POST, or the body of a POST or
QUERY of the type application/sparql-query. The client picks the format by
Accept or e.g. ?format=csv, and gets the result as bytes:

- SPARQL results in JSON, XML, CSV or TSV for a SELECT, and in JSON or XML
  for an ASK
- Turtle, N-Triples, RDF/XML or JSON-LD for a CONSTRUCT or DESCRIBE

A GET with no query gets the
[service description](https://www.w3.org/TR/sparql11-service-description/)
of the endpoint. The graphs that a request names with default-graph-uri and
named-graph-uri, or with FROM and FROM NAMED, make up the dataset, as in
Fuseki. A request that breaks the protocol or the policy gets a 4xx with
the reason as plain text.

The options hold the policy, and replace those of `endpoint/defaults` one
by one. Beside :db, which can also be a function of the request that
returns a db, these are the ones you'll most often want:

- :limit, the most results that a query may return
- :timeout, the most milliseconds that a query may run, 30 seconds by
  default. A client can ask for fewer with ?timeout= in seconds, as in
  Fuseki.
- :executor, a thread pool that runs the queries, so that the timeout holds
  even where Jena doesn't check it, e.g. in a rule reasoner. A pool with no
  queue turns down queries when it's busy. `(txn/executor)` makes one with
  a thread for each processor, `(txn/executor 4)` one with 4 threads, and
  `(txn/executor {:queue 10})` lets 10 queries wait.
- :max-bytes, the most that the body of a request may hold, 1 MB by
  default. A larger body gets a 413 before the endpoint reads it all.
- :forms, the forms of query that may run, e.g. #{:select :ask}
- :endpoint, the endpoint's IRI, for when the server sits behind a proxy

The handler throws at once when an option has a value of the wrong kind.
The docstring of `endpoint/handle` lists every option, e.g. :error for a
response of your own to a bad request.

For Pedestal, put the interceptors in a route:

```clojure
(require '[dk.cst.theophrastus.endpoint.pedestal :as endpoint.pedestal])

(def routes
  #{["/sparql" :any (endpoint.pedestal/interceptors config)
     :route-name ::sparql]})
```

The interceptors are maps, so the library doesn't depend on Pedestal.
There's one for each step of the endpoint, each of which takes and returns
data, so you can call them yourself:

```clojure
(require '[dk.cst.theophrastus.endpoint :as endpoint])

(-> (endpoint/operation request)
    (endpoint/prepare {:prefixes prefixes :forms #{:select :ask}})
    (endpoint/run db {:timeout 30000}))
;; => {:query             "ASK { ex:alice ex:knows ex:bob }"
;;     :default-graph-uri []
;;     :named-graph-uri   []
;;     :params            {}
;;     :sparql            #object[org.apache.jena.query.Query ...]
;;     :form              :ask
;;     :result            true}
```

A step passes on a map that has an :error as it is, so the steps thread.
To change one step, replace its interceptor with your own, e.g. a run that
caches results, or a respond that shows them as HTML. Such a step can still
write a result with `endpoint/write`, or make the response with
`endpoint/respond`. To page through a query that has no LIMIT of its own,
prepare it without :limit and :offset, and then restrict its :sparql with
`endpoint/restrict`.

These are the choices that the standards leave open:

- **SERVICE and java: IRIs are off**, since SERVICE makes the server send
  a request wherever the query says, and Jena loads whatever class a java:
  IRI names, also when it isn't a function. Turn them on with :service?
  true and :java? true.
- **Every response has the headers of `endpoint/safety-headers`**, which
  tell a browser not to sniff the type of the body or run anything in it,
  since the bodies are data that clients may have written.
- **Without Accept**, results are in JSON, where Fuseki uses XML, and
  graphs in Turtle.
- **RDF/XML is plain**, since Jena's pretty writer overflows the stack on
  a long chain or list.
- **Jena's message for a query that fails to run** stays in the :details
  of the error, since it may tell more about the server than a client
  needs to know.
- **A refused query gets a 403** when the policy forbids its form or
  SERVICE, and a 503 when it runs out of time or the pool is busy. The
  protocol names 500 for a refusal, but allows other codes.
- **The base IRI of a query is the endpoint's**, which section 2.3 of the
  protocol allows.
- **A dataset picks from the graphs of the db**, and nothing is fetched
  from the web. On a db of a Model, which has no named graphs, a named
  graph is empty. With :dataset? false, a request that names graphs gets a
  400.
- **SPARQL 1.2** has a version parameter and the QUERY method of
  [RFC 10008](https://www.rfc-editor.org/rfc/rfc10008). The endpoint takes
  both, and versions 1.1 and 1.2.

The tests include the query tests of the W3C's
[protocol test suite](https://w3c.github.io/rdf-tests/sparql/sparql11/protocol/).

Jena's own API
--------------

Where you need Jena's own API, e.g. to keep a ResultSet, these convert
between the two:

- `theo/jena-model`, `theo/jena-dataset` and `theo/jena-graph`, for the
  Model, Dataset and Graph of a db;
- `tdb2/dataset-graph`, for the DatasetGraph of a db of TDB2, e.g. for
  Jena's DatabaseMgr;
- `convert/node` and `convert/triples`, for Clojure data as Jena nodes and
  triples;
- `convert/data`, for a Jena node as the value that `theo/q` returns;
- the sparql namespace, with the parts of `theo/q`: `sparql/bind` adds
  bindings to a Query, `sparql/query-exec-builder` returns a
  QueryExecBuilder for a db, and `sparql/row` turns a row of a RowSet into
  a map.

Not yet
-------

- triple terms and base direction from RDF 1.2, which throw for now
- TriG, N-Quads and other syntaxes with several graphs, for reading and
  writing files
- a query that returns a set of tuples
- one value or a set by a schema, e.g. SHACL's sh:maxCount 1
- queries in Datomic's syntax
- updates through the SPARQL endpoint, and the Graph Store Protocol

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

License
-------

The Theophrastus project is licensed under the
[Apache License 2.0](LICENSE.txt). It began as a fork of Aristotle, which
the [NOTICE](NOTICE) credits.
