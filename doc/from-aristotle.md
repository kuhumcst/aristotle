From Aristotle
==============

Theophrastus began as a fork of
[Aristotle](https://github.com/arachne-framework/aristotle). This page lists
what changed for code that used Aristotle. The namespaces are now under
`dk.cst.theophrastus`, and the library needs Java 21 and Jena 6.

In the table, `aa` is `arachne.aristotle` and `theo` is
`dk.cst.theophrastus`. The other namespaces go by the last part of their
name:

| Aristotle                                | Theophrastus                          |
|------------------------------------------|---------------------------------------|
| `(aa/graph :simple)`                     | `(theo/db)`                           |
| `(aa/graph :jena-rules rules)`           | `theo/db` of a `rules/->inf-model`    |
| `(aa/graph :jena-mini)`                  | the same, with `rules/mini-rules`     |
| `aa/add`                                 | `theo/transact!`                      |
| `aa/read`                                | `theo/read!`                          |
| `(aa/write graph file format)`           | `(theo/write db file)`                |
| `query/run` with the SPARQL algebra      | `theo/q` with SPARQL, the query first |
| `query/build` and `query/parse`          | `theo/query`                          |
| `(query/pull graph subject pattern)`     | `(theo/pull db pattern subject)`      |
| `graph/data`                             | `convert/data`                        |
| `registry/prefix`, `registry/with`       | the `:prefixes` option of `theo/db`   |
| `inference/rule`                         | Jena rule text and `rules/parse`      |
| `validation/validate`                    | `shacl/validate`                      |

Aristotle turned on the OWL translation and transitive closure caching of
its rule reasoner, which `rules/->reasoner` only does when you ask.

Queries
-------

- A query returns a vector of maps, never a set of tuples.
- The bindings keep their forms, but go in as a VALUES block at the start
  of the WHERE clause.
- A property in `theo/pull` has one value or a set as the data has it,
  rather than by OWL cardinality.

Data
----

- A long becomes an xsd:integer rather than an xsd:long, as a 1 does in
  SPARQL and Turtle.
- A query returns an xsd:decimal, date or time as a Literal, rather than as
  a BigDecimal, Date or XSDDateTime. On the JVM, `term/value` turns it into
  a BigDecimal or a java.time value.
- A string with a language tag is the LangStr of the term namespace, not
  the one of ont-app.
- A java.net.URL is no longer an IRI, since it can change one. Use a
  java.net.URI or a string in angle brackets.
- A symbol that isn't a variable or a blank node throws, rather than
  becoming a urn:clojure: IRI.
- A map with no properties adds nothing, rather than an rdf:type
  rdfs:Resource.
- An RDF list has no rdf:type rdf:List, and a container starts at rdf:_1,
  as RDF 1.1 Turtle and RDF Schema 1.1 have them.
- The default prefixes no longer have owl2, a namespace of an OWL 2 draft.

Gone
----

- wildcard prefixes, aliases and the `#rdf/prefix` tags
- the RDF/EDN syntax for .edn files
- `graph/reify` and `query/sparql`
- the min-cardinality validator, which SHACL's sh:minCount replaces
