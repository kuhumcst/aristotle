SPARQL endpoint
===============

The endpoint serves a db read-only, by the
[SPARQL 1.1 Protocol](https://www.w3.org/TR/sparql11-protocol/). Its tests
include the query tests of the W3C's
[protocol test suite](https://w3c.github.io/rdf-tests/sparql/sparql11/protocol/).

Requests and results
--------------------

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
by one. The :db can also be a function of the request that returns a db.
The handler throws at once when an option has a value of the wrong kind.

Steps
-----

The interceptors are maps, so the library doesn't depend on Pedestal.
There's one for each step of the endpoint, and a step passes on a map that
has an :error as it is, so the steps thread. To change one step, replace
its interceptor with your own, e.g. a run that caches results, or a
respond that shows them as HTML. Such a step can still write a result with
`endpoint/write`, or make the response with `endpoint/respond`. To page
through a query that has no LIMIT of its own, prepare it without :limit
and :offset, and then restrict its :sparql with `endpoint/restrict`.

Choices the standards leave open
--------------------------------

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
