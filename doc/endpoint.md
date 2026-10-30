SPARQL endpoint
===============

Theophrastus can serve a db as a read-only SPARQL endpoint that follows
the [SPARQL 1.1 Protocol]. It passes the query tests of the W3C's
[protocol test suite].

DanNet's endpoint at [wordnet.dk/dannet/sparql] is a live example. Open it
in a browser to get a query editor.

The examples below use the db from the [README](../README.md).

Making one
----------

A Ring handler is the quickest way to get started:

```clojure
(require '[dk.cst.theophrastus.endpoint.ring :as endpoint.ring]
         '[dk.cst.theophrastus.txn :as txn])

(def handler
  (endpoint.ring/handler {:db       db
                          :limit    1000
                          :executor (txn/executor)}))
```

For [Pedestal], put the interceptors in a route instead:

```clojure
(require '[dk.cst.theophrastus.endpoint.pedestal :as endpoint.pedestal])

(def routes
  #{["/sparql" :any (endpoint.pedestal/interceptors {:db db})
     :route-name ::sparql]})
```

The interceptors are plain maps, so Theophrastus doesn't depend on
Pedestal.

Options
-------

The options you pass replace the ones in `endpoint/defaults`. These are the
ones you're most likely to need:

- `:db` is the db to query. It can also be a function that takes the
  request and returns a db.
- `:limit` is the most results that a query may return.
- `:timeout` is the most milliseconds that a query may run. The default is
  30 seconds. A client can ask for less with `?timeout=` in seconds (as in
  Fuseki).
- `:executor` is a thread pool that runs the queries. The timeout then also
  holds where Jena doesn't check it (e.g. in a rule reasoner). The pool
  from `(txn/executor)` turns queries down when all its threads are busy.
- `:max-bytes` is the most that the body of a request may hold. The default
  is 1 MB.
- `:forms` is the set of query forms that may run (e.g. `#{:select :ask}`).
- `:endpoint` is the IRI of the endpoint. Set it when the server sits
  behind a proxy.

The handler throws right away if an option has the wrong kind of value.
The docstring of `endpoint/handle` lists every option.

Requests and results
--------------------

A client can send the query in the query string of a GET or in a form in a
POST. It can also send it as the body of a POST or QUERY request with the
type `application/sparql-query`. The client picks the format of the result
with the Accept header or a parameter such as `?format=csv`:

- a SELECT gives SPARQL results in JSON, XML, CSV or TSV
- an ASK gives SPARQL results in JSON or XML
- a CONSTRUCT or DESCRIBE gives Turtle, N-Triples, RDF/XML or JSON-LD

A GET with no query returns the [service description] of the endpoint.

A request can name its own dataset with `default-graph-uri` and
`named-graph-uri` or with FROM and FROM NAMED in the query. As in Fuseki,
the graphs then come from the db. Without them the query uses the default
graph of the db (see the `:default-graph` option of `theo/db`).

When a request breaks the protocol or the policy, the response is a 4xx
with the reason in plain text.

Steps
-----

The endpoint handles a request in steps that each take and return data, so
you can also run them yourself:

```clojure
(require '[dk.cst.theophrastus.endpoint :as endpoint])

(def request
  {:request-method :post
   :headers        {"content-type" "application/sparql-query"}
   :body           "ASK { ex:alice ex:knows ex:bob }"})

(-> (endpoint/operation request)
    (endpoint/prepare {:prefixes prefixes :forms #{:select :ask}})
    (endpoint/run db {:timeout 30000})
    (:result))
;; => true
```

Each step adds to the map that `endpoint/operation` returns. A map with an
`:error` passes through the remaining steps unchanged.

Each Pedestal interceptor runs one step, so you can replace a step with one
of your own. For example, a run step could cache results or a respond step
could show them as HTML. Your step can still use `endpoint/write` to write
a result and `endpoint/respond` to make the response.

To page through a query that has no LIMIT of its own, prepare it without
`:limit` and `:offset`. Then restrict its `:sparql` with
`endpoint/restrict`.

Choices the standards leave open
--------------------------------

- **SERVICE and java: IRIs are off.** SERVICE makes the server send a
  request to wherever the query says. A java: IRI makes Jena load whatever
  class it names (even when that isn't a function). Turn them on with
  `:service? true` and `:java? true`.
- **Every response has the headers in `endpoint/safety-headers`.** They
  tell the browser not to guess the type of the body or run anything in it
  because clients may have written the data.
- **Without an Accept header**, results come as JSON (Fuseki uses XML) and
  graphs as Turtle.
- **RDF/XML is written plain** because Jena's pretty writer overflows the
  stack on long chains and lists.
- **Jena's message for a failed query stays out of the response.** It goes
  in the `:details` of the error instead. It can tell a client more about
  the server than it needs to know.
- **A refused query gets a 403** when the policy forbids its form, SERVICE
  or a java: IRI. It gets a 503 when it runs out of time or the pool is
  busy. The protocol names 500 for a refusal but allows other codes.
- **The base IRI of a query is the endpoint's.** Section 2.3 of the
  protocol allows this.
- **A dataset can only pick graphs from the db.** Nothing is fetched from
  the web. A db of a Model has no named graphs, so a named graph is empty
  there. With `:dataset? false`, a request that names graphs gets a 400.
- **SPARQL 1.2 adds a version parameter and the QUERY method** from
  [RFC 10008]. The endpoint supports both and accepts versions 1.1 and 1.2.

[SPARQL 1.1 Protocol]: https://www.w3.org/TR/sparql11-protocol/
[protocol test suite]: https://w3c.github.io/rdf-tests/sparql/sparql11/protocol/
[wordnet.dk/dannet/sparql]: https://wordnet.dk/dannet/sparql
[Pedestal]: https://pedestal.io/pedestal/0.7/guides/what-is-an-interceptor.html
[service description]: https://www.w3.org/TR/sparql11-service-description/
[RFC 10008]: https://www.rfc-editor.org/rfc/rfc10008
