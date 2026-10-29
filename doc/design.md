Design
======

These are the decisions that shape Theophrastus, and the reasons for them.

- **An API like Datomic's.** Datomic, Datalevin and Asami share a small API
  that Clojure developers know. A db wraps a live Jena store, so unlike
  Datomic's it isn't a value, and the functions that change it end in !.
- **One value or a set, as the data has it.** RDF data seldom says how many
  values a property may have, so a property has one value or a set of them,
  rather than following a schema or OWL cardinality.
- **SPARQL as the query language.** SPARQL is the standard, so a query is a
  SPARQL string. Flint stays outside the library for queries as data, so
  its bugs and its dependencies stay out too.
- **Values go in as terms.** A bound value goes into a query as an RDF term,
  never as text, so a value from a user can't change the query, and a
  query can be parsed once and run many times.
- **Terms that also work in ClojureScript.** The terms are plain values and
  two types of the library's own, in one namespace that compiles to both
  platforms. They aren't records, since a record is a map, which the map
  form would read as a subject.
- **IRIs without a prefix stay strings.** One rule from SPARQL tells such a
  string from text, and text that passes the rule becomes a typed literal,
  so data makes the round trip without loss.
- **An integer is an xsd:integer**, as a 1 is in SPARQL and Turtle, so data
  and queries match.
- **A literal that no native type holds exactly stays a literal**, the same
  on both platforms. A function turns it into a JVM value on demand, so an
  ill-formed literal no longer fails a whole query.
- **Matching by term.** The in-memory graphs that the library makes match
  by term, as TDB2 does, so a query finds the same data in memory and on
  disk. FILTER still compares values.
- **The store keeps the prefixes.** Jena stores prefixes with the data, so
  the library has no registry of its own. The prefixes you pass in win
  over the stored ones.
- **One write, one transaction.** Each function that writes converts all
  its data first and then writes in one transaction, so an error leaves the
  store as it was.
- **Close to the standards.** Where RDF, SPARQL or XSD has a rule, the code
  follows it and cites it.
- **Public namespaces by concern.** Every part that a user may call has a
  public namespace, and only what no user needs lives in an impl namespace.
