Taking over Aristotle
=====================

Research done on 13 October 2026, on kuhumcst/aristotle master at 15f0cb6,
upstream main at d7ee879 and DanNet master at 2bd10c5.

This report covers what it takes to move Aristotle to a current Jena,
replace its query language with Flint, and move DanNet's generic Jena code
into it. It ends with a plan and the questions I need answered before
changing any code.

Where it says "tested", the code was run on Java 26 in throwaway copies of
Aristotle and DanNet. Those scripts aren't part of this repository.


Summary
-------

- **Go straight to Jena 6.2. The API changes are small.** Aristotle needs
  ten small fixes (upstream's Jena 5.3 commit has five of them), after
  which its tests pass on 6.2. DanNet needs four fixes of its own, and its
  shape, stats and CSV tests give the same results on 4.5 and 6.2. DanNet
  already runs Java 21, which Jena 6 needs. Only 6.2 gets security fixes.
- **The risks are in behaviour.** Jena 5 and later write language tags in
  canonical case, match literals by term in in-memory graphs, and store
  xsd:decimal in canonical form. Aristotle's tests miss a break that would
  hit DanNet everywhere: with upstream's port, every LangStr write throws
  on 6.2.
- **TDB2 opens across versions, but DanNet should rebuild its database.**
  In a database written by 4.5, Jena 6.2 can't find a literal with a
  non-canonical language tag or decimal by a constant lookup, and adding
  it again creates a duplicate. Jena recommends a reload, and DanNet can
  rebuild from source.

- **Flint is a good fit, but values from users must not go through it as
  strings.** It covers SPARQL 1.1 query and update, with property paths,
  n-ary UNION and aggregates that Aristotle's DSL lacks. It checks strings
  but doesn't escape them, so `"C:\temp"` reaches Jena as `C:<tab>emp`. The
  safe pattern is to parse each query once and bind values as Jena nodes,
  which is also the fastest: 13 µs per run against 160 µs to format and
  parse.
- **flint-jena is close to abandoned.** It doesn't compile on Jena 5 or 6.
  About 10 lines fix that, after which its 103 equivalence tests pass.
- **Aristotle has more bugs than the known `:union` one.** Seven algebra
  operators drop branches past the second, `:slice` and `sample` throw,
  two specs don't exist, `read` ignores input it doesn't recognise, and one
  ill-formed literal makes a whole query throw.
- **DanNet uses a small part of Aristotle.** In algebra data it uses only
  `:bgp`, `:union` and `:filter`. It never uses inference, validation, pull
  or the RDF/EDN reader. The deep coupling is in the shape of results:
  rows as maps of `?var` symbols, IRIs as keywords, LangStr, and blank
  nodes as `_label` symbols.
- **Most of DanNet's candidate code belongs in the library**: transactions,
  ARQ functions, reading and removing, TDB2 helpers, SHACL reports as data
  and the core of the entity maps. The display pruning in `entity.clj`
  doesn't.


Part 1: Jena 4.5 to 6.2
-----------------------

Sources: Jena's CHANGES.txt, release announcements, issues and source,
public signatures compared with javap on the 4.5, 4.10, 5.6 and 6.2 jars,
and runs of Aristotle and a copy of DanNet on 6.2. Jena has no migration
guide; the [Jena5](https://github.com/apache/jena/issues?q=label%3AJena5)
and [Jena6](https://github.com/apache/jena/issues?q=label%3AJena6) issue
labels come closest.

### Versions and Java

| Line | Java | Releases | Maintained |
|---|---|---|---|
| 4.x | 11 | last: 4.10.0, November 2023 | no |
| 5.x | 17 | 5.0.0 (March 2024) to 5.6.0 (October 2025) | no |
| 6.x | 21 | 6.0.0 (February 2026) to 6.2.0 (31 July 2026) | yes |

- **Jena maintains only its latest release.** CVE-2026-61372 (SPARQL
  Update URLs can escape file restrictions) is fixed only in 6.2.0.
- **Avoid 6.0.0.** Its new cancellation code broke property paths on
  TDB2, which 6.1 fixed.
- **6.x also fixes TDB2 bugs that 4.5 has.** After an aborted write, the
  next write could leave a gap of zeros in `nodes-data`, which corrupts
  the database for good (fixed in 6.1). Any exception inside a write
  transaction aborts it, so this can happen in normal use. Queries also
  failed after an OutOfMemoryError until a restart (fixed in 6.2).
- **DanNet runs OpenJDK 21** in Docker (`docker/dannet/Dockerfile:10`) and
  in CI, which is enough for 6.x.

Going through 5.x would only help as a stage for compiling, so I suggest
going straight to 6.2 and running the tests on 5.6 as well.

### API changes in Aristotle

Tested: all 37 tests pass on 6.2 after these changes.

1. `graph.Factory` is gone (5.0); use `GraphMemFactory`.
2. `createLiteralByValue(lex, lang, XSDstring)` is gone (5.0), and
   upstream's replacement `createLiteral(lex, lang, XSDstring)` throws on
   6.2. Use `createLiteralLang`.
3. `createLiteral(lex, datatype)` is gone (6.0); use `createLiteralDT`.
4. The `Triple` constructor is protected (5.0); use `Triple/create`.
5. `getBlankNodeId` is gone (5.0); use `getBlankNodeLabel`. Without a type
   hint, this only fails at run time.
6. `E_Conditional` is gone (6.0); use `E_If`.
7. `OpDiff` is now `OpAntiJoin` (5.4).
8. The `VarExprList(List)` constructor is gone (5.0).
9. `Lang/JSONLD10` is gone (5.0).
10. `E_BNode` is no longer an `Expr` (5.0); use `E_BNode/create`. The
    tests don't cover the `bnode` expression, so they pass without this.

The test alias also needs a newer logback, since 1.2 doesn't bind to
SLF4J 2 and logging silently goes to NOP.

### TDB2

- **The format didn't change.** Jena 6.2 opens, queries, writes to and
  compacts a TDB2 database written by 4.5, and 4.5 opens one written by
  6.2 (tested both ways). The node table only gained an optional field for
  base direction.
- **Some old literals can't be found any more.** TDB2 finds a node by a
  hash of its lexical form, language tag and datatype as Jena writes them.
  Two of those changed:
  - language tags are in canonical case since 5.0, so a literal stored as
    `"Hej"@DA` is found neither as `@da` nor as `@DA` (tested);
  - xsd:decimal is canonical since 6.0, so a stored `1.50` is found
    neither as `1.50` nor as `1.5` (tested).

  A FILTER still finds them, adding them again creates a duplicate, and a
  lookup succeeds once a scan has loaded the node into the cache, so the
  results depend on what ran before.
- **Integers, doubles, dateTime and booleans are unchanged.** TDB2 has
  always matched by term, so a SPARQL `2` never matched a stored
  `"2"^^xsd:long`, in 4.5 or in 6.2.
- **Reload.** Jena recommends reloading for both changes. DanNet can
  rebuild from source, which is the clean way. (Compacting under 6.2 also
  rewrote every term and merged the duplicates in a test, but Jena doesn't
  document that.)
- **Rolling back isn't safe.** Jena 4.5 opens a database written by 6.2,
  but its lookups fail in the same way in reverse (tested). Once DanNet's
  database is rebuilt on 6.2, going back to 4.5 means rebuilding again.
- **Compaction** has the same API. It is atomic since 5.0, `deleteOld`
  frees disk space since 5.4, and since 5.5 each release of a database
  triggers a full GC. Issue #3488 (compaction can hang) is open.
- **Base direction isn't in the hash**, so `"x"@en--ltr` and `"x"@en--rtl`
  collapse into one node (tested; I found no upstream issue).

### Literals and language tags

- **Tags are in canonical case** when created, parsed or read back, with
  no option to turn it off: `en-gb` becomes `en-GB`, `DA` becomes `da`.
  LangStr compares tags as strings, so a LangStr written as `en-gb` won't
  equal a result. DanNet's own tags are already canonical.
- **Base direction.** `"abc"@en--ltr` has the language `en`, a separate
  direction and the datatype rdf:dirLangString. LangStr has no field for
  the direction, so Aristotle would drop it silently.
- **Triple terms.** `<<( s p o )>>` is a triple term, and `<< s p o >>` now
  means a reifier, so the same Turtle reads differently in 4.5 and 6.2.
  Aristotle's `data` has no case for triple terms and throws.

### In-memory graphs

- **The default graph matches by term since 5.0**, so a SPARQL `1` no
  longer matches a Clojure `1` that Aristotle stored as xsd:long, though
  `FILTER(?o = 1)` still does (tested). `createDefaultGraphSameValue` keeps
  the old behaviour, through a deprecated class.
- `ModelFactory/createDefaultModel` still returns a value graph in 6.2, on
  purpose.
- The rule reasoner's forward and hybrid deductions are term graphs since
  5.5.
- Plain memory graphs and inference graphs still don't support
  transactions.

### The rule reasoner

- `GenericRuleReasoner`, `Rule` and `ReasonerRegistry` are unchanged and
  not deprecated in 6.2. Aristotle's inference tests pass.
- The `jena.rulesys.lp.max_cached_tabled_goals` property that DanNet's
  `start.sh` sets is still read; only the cache behind it changed.
- 6.2 fixes `filterFunctors` in backward mode. HYBRID mode is unaffected.
- `OntModel` is deprecated for removal since 5.6 in favour of jena-ontapi.
  Neither Aristotle nor DanNet uses it.

### Functions, timeouts and results

- **`Function.build` takes a context since 5.0**, so DanNet's `reify
  Function` in `function.clj` doesn't compile.
- **`QueryExecution.setTimeout` and `setInitialBinding` are gone since
  5.0.** DanNet's endpoint calls `setTimeout` by reflection
  (`sparql.clj:122`), so it compiles and fails at run time. The builder's
  `timeout` replaces it, and it already exists in 4.5, so DanNet can
  switch before the upgrade.
- **Initial bindings are gone everywhere since 6.0**, replaced by
  substitution. Known problems: `SELECT *` dropped bound variables until
  5.3, a bound variable on the right of a BIND doesn't work, and blank
  nodes substituted into templates become fresh nodes (#3267). DanNet binds
  blank nodes to look up their entities, so this needs a test.
- Cancellation was improved in 5.1, 5.3, 5.4 and 6.0. Nothing in the
  release notes says the rule engine now checks for cancellation, so
  DanNet's hard timeout is still needed.
- `ResultSet.materialise`, `ResultSetMem`, `ResultSetMgr` and
  `QueryCancelledException` are unchanged.

### SHACL

The API is unchanged apart from `Shapes.parseTargets`, and DanNet's shape
tests give the same results on 6.2. SPARQL constraints use substitution
since 6.0, and 6.1 and 6.2 add SHACL 1.2 list constraints.

### Other changes DanNet would see

- Turtle and TriG output writes `PREFIX` instead of `@prefix` since 5.0.
  The `ttl:directiveStyle` option set to "at" brings the old form back.
- The RDF/XML parser was replaced (5.0), and JSON-LD 1.0 is gone.
- Java 25 and later have stricter limits on XML entities, which can break
  RDF/XML parsing (issue #4328, open). Production runs Java 21, but the
  development machine runs Java 26, and DanNet loads its schemas as
  RDF/XML by default.
- `RDFLanguages` no longer maps `application/turtle`, `text/plain` and
  `text/xml` (6.0).
- `Node.toString` changed format, e.g. `"y"@da-DK` and
  `"true"^^xsd:boolean`, so code that calls `str` on nodes may see new
  text.
- DanNet's SPARQL strings parse on 6.2: the namespaces that parse them at
  load time all loaded, except `wn_lmf.clj`, which failed in that test
  because of load order.
- Mixed Jena versions on the classpath fail with `NoClassDefFoundError`,
  so all Jena artifacts should come from one version, e.g. through
  jena-bom.

### Performance

The term graphs are faster than the old in-memory graph but use 6-35%
more memory. Joins on several variables use hash joins since 5.2, and
parsing is about 5% faster in 6.0. I found no reported regressions,
apart from the full GC on each release of a TDB2 database.


Part 2: Flint in place of the algebra DSL
-----------------------------------------

An agent tested [Flint](https://github.com/yetanalytics/flint) 0.3.1 and
flint-jena 0.1.3 by formatting each feature and parsing the result with
Jena 6.2.

### Coverage

Flint covers SPARQL 1.1 query and update almost completely: all four query
forms, property paths, VALUES (in and after WHERE), OPTIONAL, MINUS, UNION
with any number of branches, FILTER EXISTS and NOT EXISTS, BIND,
aggregates including GROUP_CONCAT with a separator, subqueries, SERVICE,
GRAPH, RDF lists, and every update form (INSERT/DELETE DATA, DELETE/INSERT
WHERE, LOAD, CLEAR, DROP, CREATE, ADD, MOVE, COPY, WITH, USING).

Gaps and bugs:

- **HAVING with ORDER BY gives invalid SPARQL.** Flint writes ORDER BY
  before HAVING and Jena refuses to parse it. This isn't reported upstream.
- **Custom functions in SELECT count as aggregates.** `SELECT ?x
  (dnf:norm(?l) AS ?n)` fails validation. A BIND works around it.
- **Only one FROM** per query, and only one USING per update.
- **No negated property set with inverse members**, e.g. `!(^p)`.
- **No RDF 1.2**: no triple terms and no `@en--ltr`. The maintainer says
  in issue #40 (August 2026) that 1.2 is planned.
- **Numbers:** a Double is written bare, so Jena reads 3.14 as
  xsd:decimal, and a BigDecimal `1M` is written as `1` (xsd:integer, issue
  #46).

Custom function IRIs work as `(:dnf/startsWith ?l "hu")`. Flint checks
that the prefix is declared and nothing else. Language-tagged strings are
one-entry maps, e.g. `{:da "hund"}`. Typed literals with your own datatype
need a type that implements Flint's `Literal` protocol.

### Prefixes, validation and errors

Prefixes go in a `:prefixes` map, and Flint writes them as a PREFIX block.
An undeclared prefix is an error. Validation is clojure.spec plus checks of
scope, aggregates and blank nodes; `:validate? false` turns off the latter
and halves the time to format. Errors are ex-info with a `:kind`, but a
syntax error only names the clause, not the form that failed.

### Values from users

Flint validates strings but doesn't escape them:

- a raw `"`, a newline or a trailing `\` is refused, so a value like a
  definition with quotes in it can't go into a query at all;
- `\t`, `\n` and `\"` written as text pass through and Jena reads them as
  escapes, so `"C:\temp"` changes value;
- a string shaped like `"<http://…>"` becomes an IRI, as it does in
  Aristotle today.

So the injection that DanNet fixed in `5a48408` can't happen through Flint
(the hostile string is refused), but correct values can be refused or
changed. The safe way to put values into a query is as Jena nodes after
parsing, which works the same for queries written as Flint data and as
SPARQL strings:

- `QueryExec ... (.substitution var node)` for one value per variable. It
  copies the query, leaves the original alone and refuses a variable that
  BIND, VALUES or AS sets. A hostile literal stayed one literal (tested).
- `(.setValuesDataBlock (.cloneQuery q) vars bindings)` for a list of
  values, also as nodes.

`ParameterizedSparqlString` escapes text, needs a full parse each time,
and its own Javadoc says it is no complete defence. `initialBinding` is
gone from the Jena 6.2 builders.

### Strings or objects

On Jena 6.2, for a selective query with five patterns, an OPTIONAL, a
FILTER and ten VALUES rows over 600,000 triples:

| Step | µs |
|---|---|
| Flint `format-query` (validation on / off) | 67 / 38 |
| `QueryFactory/create` on that string | 94 |
| flint-jena `create-query` | 57 |
| Running the parsed query | 42 |
| Cached query + `substitution` + run | 13 |

Building and parsing cost four times the run of a small lookup, and
nothing next to a scan or an aggregate (30-100 ms). Since DanNet already
parses 49 of its queries once at load, the cost only matters for the few
that are built per request, e.g. `resource-labels-query` on every entity
page. With values bound as nodes, those become parse-once too.

### flint-jena

flint-jena turns Flint data into a Jena `Query` or `UpdateRequest` without
a string. It has one author, its last real change was in February 2024, it
has 659 downloads and no issues. It uses Flint's internal namespaces.

- It doesn't compile on Jena 5 (`Triple` constructor) or Jena 6 (removed
  `XSDQName`, `createLiteral` overloads and `E_Conditional`). A patch of
  about 10 lines fixes both, and all 103 tests that compare it with Jena's
  parser then pass. The failures left only compare `.toString` text.
- It drops datatypes it doesn't know: `"abc"^^ex:Type` becomes `"abc"`.
- It gets HAVING with ORDER BY and BigDecimal right, unlike Flint's string
  output.

Both projects are Apache 2.0, so the library could carry a patched copy.

### What the DSL can do that Flint can't

- **It builds nodes directly**, so string values are never escaped or
  unescaped. Flint data with string constants needs the same care as a
  SPARQL string.
- **Algebra operators with no SPARQL syntax**, e.g. `:sequence`, `:top-n`,
  `:reduced`, `:label` and the quad forms. Jena's optimiser produces these
  from SPARQL on its own, and DanNet uses none of them.
- **The map form of a BGP**, `{:rdf/about ?e :ds/zip ?zip}`. Flint has
  `{?e {:ds/zip #{?zip}}}`, which is close.
- **Keyword IRIs from the global registry**, with no `:prefixes` per query.
  The library can pass the registry to Flint as its `:prefixes`.

Everything else goes the other way. The DSL has no property paths, SERVICE,
n-ary UNION, CONSTRUCT, ASK, DESCRIBE or update, and its `:slice`,
`sample`, `:table` and `:left-join` are broken (Part 3).

### Recommendation

Accept both SPARQL strings and Flint data, compile either into a Jena
`Query` once, and bind values as nodes when running it. Start with Flint's
string output plus Jena's parser, which uses Flint's validation and Jena's
own parser as the reference. Bring in a patched flint-jena only if building
queries per request turns out to matter. Other Clojure SPARQL libraries
(grafter, matcha, ont-app/igraph and sparql-client) have had no commits
since 2024.


Part 3: Aristotle today
-----------------------

| Namespace | Lines | What it does | DanNet uses |
|---|---|---|---|
| `arachne.aristotle` | 104 | graph constructors, `add`, `read`, `write` | `add`, `read` |
| `graph` | 289 | Clojure data to nodes and triples and back, the map form, RDF lists and containers, reification | `data` once, and through `q/run` |
| `registry` | 192 | global keyword-IRI registry with nested and wildcard namespaces, aliases and reader tags | `prefix`, and the internals in `export/rdf.clj` |
| `query` | 177 | `build`, `run` (with `Algebra/exec` on a Graph), `parse`, `sparql`, `pull` | `build`, `parse`, `run` |
| `query.compiler` | 289 | the DSL to Jena `Op` and `Expr` | through `build` |
| `query.spec` | 276 | specs for the DSL, checked only by `run` | indirectly |
| `inference` | 106 | rules written as Clojure data, OWL rule sets | loaded, not used |
| `validation` | 74 | OWL reasoner reports and a min-cardinality check | no |
| `rdf-edn` | 51 | registers RDF/EDN as a Jena language on load | loaded, not used |

The test suite has 37 tests and 86 assertions. It passes on Jena 4.5 and on
upstream's 5.3, but no test uses LangStr, which is how it missed the Jena 6
break in Part 1.

### Bugs

All of these were confirmed by running them on master.

- **Seven operators drop branches past the second.** `:union`,
  `:disjunction`, `:sequence`, `:join`, `:conditional`, `:minus` and
  `:diff` destructure two arguments, so `q/build` silently drops the rest
  (the compiler's `op` at `compiler.clj:136`). Through `q/run`, the spec
  rejects a third `:union` branch with a long error.
- **`:slice` throws** a ClassCastException: it uses its first argument both
  as the sub-operation and as the offset (`compiler.clj:168`).
- **`(sample ?x)` throws**: it passes the symbol instead of an expression
  (`compiler.clj:109`).
- **`:table` and `:left-join` fail in `q/run`**: their specs refer to
  `::bindings-map` and `::expr-list`, which don't exist.
- **No property paths or SERVICE**: the compiler throws on both.
- **One ill-formed literal fails a whole query.** `data` calls
  `getLiteralValue`, so `"abc"^^xsd:integer` anywhere in the result throws
  DatatypeFormatException.
- **Jena types leak out.** `data` returns `XSDDateTime` for xsd:date and
  xsd:gYear and `BaseDatatype$TypedValue` for unknown datatypes. DanNet
  handles both by hand in three places. xsd:dateTime becomes a
  `java.util.Date` and loses its offset.
- **Strings shaped like `"<…>"` are IRIs**, in both directions. A value from
  a user bound into a query this way becomes an IRI.
- **`read` ignores input it doesn't recognise**: an InputStream or a
  Reader falls through the `cond` and nothing is read, with no error.
  `write` returns nil for an unknown format.
- **A projection makes `q/run` return a set**, so duplicate rows collapse.

### Dead code

- `query.clj` imports the JDK-internal
  `com.sun.org.apache.xpath.internal.operations.Mod` and conforms a pull
  pattern at the top level on every load.
- `graph.clj` depends on `javax.xml.bind` (and the jaxb-api dependency) for
  one call that java.time can do.
- The registry pre-registers `owl2` for a namespace from a 2006 draft.
- `compiler.clj` requires `graph` twice and imports `Var` twice, and
  `defd` in `query.spec` throws its docstrings away.
- `validation` builds a query and `inference` builds two OWL reasoners when
  they load. `arachne.aristotle` requires `inference`, so DanNet pays for
  it.

### ont-app/vocabulary

Aristotle uses ont-app/vocabulary only for its LangStr record. DanNet uses
it as well for keywords and IRIs on the server and in ClojureScript, and
for PREFIX blocks (`voc/prepend-prefix-declarations`). That is why DanNet
registers every prefix twice in `prefix/register` and pins 0.2.1. If the
library keeps one registry and writes the PREFIX block, Flint's
`:prefixes` and Jena's `PrefixMapping` from it, DanNet only needs
ont-app/vocabulary for LangStr.


Part 4: How DanNet uses Aristotle
---------------------------------

Seven namespaces require Aristotle: `db`, `bootstrap`, `db.query`,
`db.query.operation`, `prefix`, `export.rdf` and `web.response`.

| Use | Sites |
|---|---|
| `q/build` | 9, all in `operation.clj`: 7 BGPs, one nested `:union`, one `:filter` |
| `op/sparql` (`q/parse` plus ont-app's PREFIX block) | 61 call sites, 47 of them in `operation.clj` |
| BGPs as data passed straight to DanNet's `q/run` | 37, 23 of them in `bootstrap.clj` |
| DanNet's `q/run` wrapper | 111 calls in 12 files |
| initial bindings (`{'?s subject}`) | 4 |
| projection, returning a set | 2 |
| `aristotle/add` | 5, and 14 more through `db/safe-add!` |
| `aristotle/read` | 1 |
| `graph/data` | 1 (the SPARQL endpoint's results) |
| `reg/prefix` | 1, plus the registry internals read in `export/rdf.clj:26-31` |

Values reach SPARQL strings by splicing in eight places, none of them from
HTTP input any more: `resource-labels-query` (on every entity page),
`relation-usage-query`, `relation-query`, and sites in `stats.clj`,
`wn_lmf.clj` and `dmlex.clj`. None escapes anything.

The results are the deep coupling:

- **Rows are maps keyed by `?var` symbols**, destructured with `{:syms
  [...]}` all over DanNet.
- **IRIs are keywords** when a prefix is registered, and code calls
  `namespace` and `name` on them. Unregistered IRIs come back as `"<…>"`
  strings, which `prefix/rdf-resource?` checks for.
- **Blank nodes are `_label` symbols**, and DanNet mints its own the same
  way and attaches entity maps to them as metadata.
- **LangStr** is checked with `instance?`, read with `str` and `lang`, and
  sent to the browser with the transit tag "lstr".
- **XSDDateTime and `BaseDatatype$TypedValue`** have their own transit tags.

### What a migration touches

- If the library returns rows in the same shape, the 111 `q/run` calls
  stay as they are, apart from the require.
- The 61 `op/sparql` sites become one call to compile a query, with the
  PREFIX block from the library's registry.
- The 9 `q/build` forms and 37 inline BGPs become Flint maps or SPARQL
  strings. They are all simple, but the 23 in `bootstrap.clj` run during
  the build of the database, so they need the most care.
- The 4 initial bindings become node bindings. That also removes the
  caveat in `operation.clj:43-44` that `?lemma` must stay in the SELECT.
- The 8 splicing sites become VALUES blocks of nodes.
- `db/transaction.clj` and the other code in Part 5 is replaced by the
  library's, in the 11 namespaces that require it.
- **DanNet code that breaks on Jena 5 or 6 regardless of Aristotle.** A
  copy of DanNet loads on 6.2 with these fixed (tested):
  - the `org.apache.jena.tdb.TDBFactory` import in `bootstrap.clj:30`
    (TDB1 moved to `org.apache.jena.tdb1`), for the `:tdb1` branch;
  - `build` in the `reify Function` of `function.clj`, which needs a
    context argument;
  - `.setTimeout` in `sparql.clj:122`, which fails at run time;
  - `Factory/createDefaultGraph` in `test_util.clj`.

  The TDB2 database needs a rebuild, the Turtle exports will use `PREFIX`
  unless the old style is set, and the jackson pins in deps.edn can go.

Unrelated to the upgrade: `response.clj:86` and `json_ld.clj:59` call
`.getLexicalValue` on `BaseDatatype$TypedValue`, which only has public
fields (checked with javap). Both throw whenever a literal with an unknown
datatype reaches them.


Part 5: DanNet code that could move into the library
----------------------------------------------------

| DanNet code | Lines | Verdict |
|---|---|---|
| `db/transaction.clj` | 125 | **Move.** Add the missing `:else` (an unsupported object silently skips `f` today). `:read-only?` only works for Datasets. Keep DanNet's two thread pools in DanNet and let `with-hard-timeout` take an executor. |
| `db/query/function.clj` | 58 | **Move, generalised.** Return any value through `node` rather than only doubles, convert arguments with `data`, and register under a prefix from the library's registry. |
| `db.clj` `->schema-model` | 20 | **Move into `read`**, which should take an InputStream, URL or resource and pick the language with `RDFLanguages/filenameToLang`. |
| `db.clj` `remove!` | 35 | **Move**, rebuilt on `node` with `_` as the wildcard. |
| `db.clj` `compact!` | 3 | **Move** into a TDB2 namespace. |
| `db.clj` `import-files` | 25 | **Move the idea**: parse into a temporary graph, then add it in one write transaction, so a parse error never leaves half a database. Logging and memoisation stay. |
| `db.clj` `get-model`, `get-graph` | 12 | Maybe. Small named-model helpers. |
| `db.clj` `safe-add!` | 12 | **Stay.** It's a logging policy. |
| `db.clj` `update-triples!` | 30 | **Replace** with SPARQL Update. DanNet's version removes and adds in two separate transactions, so it isn't atomic. |
| `db/shapes.clj` report as data | ~100 | **Move**: loading shapes, `validate`, `validate-node`, each report entry as a map (through `data`), `blocking?` and `by-severity`. |
| `db/shapes.clj` baseline | ~30 | Maybe. It's small, pure and generic, but it is a policy. |
| `db/shapes.clj` the rest | | **Stay**: DanNet's shapes, logging and `undefined-predicates`. |
| `db/query.clj` entity maps | ~150 | **Move the core**: a subject's property map with sets for many values, the split into asserted and inferred triples, and blank nodes expanded one level as nested maps. DanNet keeps its symbols with metadata, labels, weighting and `table-query`. `q/pull` is rebuilt on it, without the OWL cardinality inference it needs today. |
| `entity.clj` pruning | ~135 | **Stay.** It works on Clojure maps for display and never touches Jena. It belongs with a future Linked Data pages library. |

That is about 450 lines into the library and about 200 that stay.


Part 6: Proposed plan
---------------------

### Name

The name waits until the changes are made (see Decisions). Every name
below is free on Clojars (in any group), on Maven Central and on
GitHub, apart from small repositories with a handful of stars.

| Name | Why |
|---|---|
| **theophrastus** | Aristotle's successor at the Lyceum, who took over his school and his library. |
| **isagoge** | Porphyry's introduction to Aristotle's *Categories*, the source of the Porphyrian tree of genus and species, the first class hierarchy. |
| **brondal** | Viggo Brøndal, the Copenhagen linguist who wrote on logic and word classes. The ASCII spelling loses the ø. |
| **hjelmslev** | The best-known Danish linguist. kuhumcst/glossematics is about him, so the name may confuse within the same organisation. |
| **stagirite** | Aristotle's epithet. Obscure, but a direct link to the old name. |

I'd pick theophrastus, which tells the story of the fork, or isagoge,
which is shorter. Avoid frege and lyceum (taken on Clojars), quine (a JVM
graph database) and wilkins (a Clojure library).

The coordinates would follow your other libraries, e.g.
`dk.simongray/theophrastus` with the root namespace
`dk.simongray.theophrastus`. The licence stays Apache 2.0, since the code
comes from Aristotle, with a NOTICE that credits Aristotle and says the
library was changed. Your other libraries are MIT.

### Design

A Clojure library for Jena: RDF terms as Clojure data, graphs and
datasets, SPARQL as strings or Flint data, transactions and timeouts, TDB2,
rules, ARQ functions and SHACL. The decisions of 22 and 27 October
shape it:

- **Close to the standards and to Clojure idioms.** Code that follows a
  rule from a standard cites the standard.
- **An API like Datomic's**, which Datalevin and Asami share: a db value,
  `q`, `pull`, `entity` and `transact`. Datalevin isn't immutable either,
  so a live Jena graph is no obstacle.
  - The db value wraps a Model or a Dataset and carries the prefixes and
    the conversion options, much as a Datomic db carries its schema.
  - `q` takes Flint data or a SPARQL string, compiles it once and binds
    values as nodes. Datomic-shaped queries (`:find`, `:in`, `:where`)
    translated into Flint may come later.
  - `pull` keeps Datomic's pattern syntax but is rebuilt on `entity`
    without OWL cardinality: a property with several values gives a set.
  - `entity` comes from DanNet's entity maps.
- **Models at the API, graphs inside.** Jena keeps prefixes on the graph,
  a Model exposes them as a `PrefixMapping`, and TDB2 stores them on disk.
  So the library has no registry of its own. The prefixes come from a
  `default-prefixes` map, which an option can replace or extend, and the
  library sets them on the Model. When they differ from the prefixes
  stored in a TDB2 database, the ones passed in win. Turning IRIs into
  keywords needs a cached index of the longest namespaces, since that
  happens for millions of nodes.
- **Every value works in CLJ and CLJS.** One cljc namespace holds the
  value types and the rules for converting RDF terms:

  | RDF term | CLJ | CLJS |
  |---|---|---|
  | IRI with a known prefix | keyword | keyword |
  | IRI without a known prefix | `"<…>"` string | `"<…>"` string |
  | blank node | `_label` symbol | symbol |
  | xsd:string | string | string |
  | language-tagged string | `LangStr` | `LangStr` |
  | xsd:boolean | boolean | boolean |
  | xsd:integer and its subtypes | Long (BigInteger when large) | number |
  | xsd:double, xsd:float | Double | number |
  | everything else | `Literal` | `Literal` |

  `LangStr` and `Literal` are deftypes of our own, with reader tags and
  printing. They aren't records, since a record is a map and the map form
  of triples would take it for a nested resource. A `Literal` holds the
  lexical form and the datatype IRI, and a `value` function turns it into
  a platform value on demand. That covers decimals, dates, times,
  durations, custom datatypes and ill-formed literals, which then no
  longer throw, and it ends the leaking of Jena's `XSDDateTime` and
  `BaseDatatype$TypedValue`.

  One rule tells a `"<…>"` string from text: SPARQL's IRIREF rule, plus
  a scheme as RFC 3987 defines it. IRIREF bans the few characters that an
  IRI can't contain, such as spaces and angle brackets, so every absolute
  IRI passes the rule. Text that passes it, e.g. `"<urn:isbn:123>"`, has
  to be wrapped in a `Literal` and reads back as one, so data makes the
  round trip without loss. A string that fails it is text, even an IRI
  with a space in it.
- **A cljc protocol for resources.** It returns the full IRI that a value
  stands for: a keyword through the prefixes, a `"<…>"` string, a
  `java.net.URI` or a type of your own. Writing data uses it, so any type
  that extends it can stand in a triple. In the browser, DanNet's
  `rdf-resource?` and similar helpers can use it too.
- **Public namespaces for separate concerns**: `shacl`, `rules` (reasoners
  and the asserted/inferred split), `tdb2` (connecting, compacting and
  importing) and `function` (ARQ functions), with `impl.*` for the rest.

### What goes

DanNet pins a commit, so nothing needs deprecating: it moves over in one
go when it's ready.

- The algebra DSL: `q/build`, the compiler and its specs. Flint replaces it.
- `validation`, which wraps the OWL reasoner's checks. SHACL replaces it.
- The rule DSL in `inference`. Rules come as Jena rule text, as in DanNet.
- The RDF/EDN reader, the `#rdf/prefix` reader tags, and the registry's
  wildcard namespaces, aliases and `reg/with`. A plain prefix map replaces
  them.
- `reify`, which is RDF 1.1 reification, and its tests. RDF 1.2 triple
  terms and reifiers replace it, and the library has to handle triple
  terms anyway.
- The jaxb-api dependency, a 2018 beta of an API that the JDK dropped in
  Java 11, used for one call that java.time can make.
- ont-app/vocabulary, once `LangStr` is our own.

The map form of triples (`{:rdf/about ...}`) and `rdf-list` stay.

### Order of work

Each step ends with green tests and a short report to you. Steps 1 to 4
are done on the branch `feature/jena-6`, and Part 7 tests the first three
on DanNet's data.

1. **Fast-forward to upstream main** (Jena 5.3 and Clojure 1.12). The only
   fix is the test alias's logback, which no longer binds under SLF4J 2.
2. **Tests that pin down today's behaviour**, before anything else
   changes: the conversion of every kind of literal DanNet stores, IRIs
   with and without a prefix, blank nodes, `q/run` with bindings and
   projections, `add` and `read`.
3. **Jena 6.2 with the old API.** The ten API fixes in Part 1 and the
   choice of in-memory graph. DanNet can try this version with only its own
   four Jena fixes, which tests the upgrade on real data before the API
   changes.
4. **Tooling**: deps.edn aliases, GitHub Actions on Java 21 and clj-kondo.
   Until step 7, only errors fail the lint in CI.
5. **The API in the Design section**: the cljc value types, the db value
   with its prefixes, `q` with Flint data or SPARQL strings and values
   bound as nodes, `pull`, `entity` and `transact`. The algebra DSL goes.
   The tests must cover binding a blank node, which DanNet does to look up
   blank node entities.
6. **The code from DanNet** in Part 5.
7. **Cleanup and docs**: the README in the style of your other libraries,
   docstrings, and a clean clj-kondo run.
8. **The new name and layout**: the root namespace with `impl`, build.clj,
   coordinates and NOTICE. Until then, the work stays a fork of Aristotle
   with kuhumcst/aristotle as origin.

### DanNet's migration

This happens later, in two stages:

1. **After step 3**: point DanNet at the Jena 6 version of Aristotle, fix
   the Jena breaks listed in Part 4, rebuild TDB2 from source and compare.
   A fair comparison would build a database with each version into a
   scratch directory and diff entity maps, query results and the RDF
   export.
2. **After step 8**: move to the new library. The requires change, the 61
   `op/sparql` sites and 46 algebra forms are rewritten, the 8 splicing
   sites become VALUES blocks, and `db/transaction.clj` and the code from
   Part 5 are deleted. Most of the 111 `q/run` calls don't change.


Part 7: Jena 6.2 on DanNet's data
---------------------------------

On 22 October, steps 1 to 3 went onto the branch `feature/jena-6`, and
DanNet's branch of the same name pins it and has its four Jena fixes.
DanNet's tests pass and all its namespaces load on 6.2, as on 4.5.

Then a full DanNet database was built twice from the same release files:
once with master on Jena 4.5 and once with `feature/jena-6` on Jena 6.2.

- **The build** took about as long: 229 s on 4.5 and 207 s on 6.2.
- **10,304,231 quads without blank nodes** came out of each, and 175 of
  them differ on each side:
  - 6 counts in the dataset metadata, now xsd:integer, as decided;
  - 169 quads on three FrameNet IRIs with a space in them, e.g.
    `frame:Social relation_semType`. The stored data is the same, but Jena
    6.2 writes the space as ` ` where 4.5 wrote it raw. An IRI can't
    contain a space, so this is a DanNet bug in `premon.clj` (`st-kw`),
    which the upgrade only exposes.
- **Identical**: the counts per graph and predicate, blank nodes included;
  the entity maps of 300 sampled synsets, words and senses, read through
  the inference graph; the results of 51 of DanNet's queries; and the
  SHACL report.
- **Faster on 6.2**: `eq-sense-match-query` over the whole graph took half
  a second on 6.2 and still hadn't finished after 15 minutes on 4.5. DanNet
  itself runs it on the small COR.SEM graph.


Decisions
---------

Answered by Simon on 13 October 2026:

- **No implementation yet.** Simon says when the work starts, and where it
  happens then.
- **A Clojure Long becomes xsd:integer**, so that data and queries match.
- **In-memory graphs match by term**, as Jena's default graph does. FILTER
  still compares values, and TDB2 has always matched terms.
- **The name waits until the changes are made.** Until then the work is a
  fork of Aristotle, pushed to kuhumcst/aristotle as origin.

Answered by Simon on 22 October 2026:

- **Start the work**, tested with DanNet on a feature branch, with
  surgical changes.
- **A `LangStr` of our own**, the simplest thing that fits a cljc codebase.
- **Every value type needs a CLJS equivalent**, kept in one cljc namespace.
- **An API close to Datomic, Datalevin and Asami.** `pull` stays if it is
  generic.
- **`q` takes Flint data or SPARQL strings.** Datomic-shaped queries may
  come later.
- **Model-driven**, with Jena's own prefix mapping and defaults that an
  option can replace.
- **The list under "What goes"** stands, `reify` included.
- **Tooling before the API**, on `feature/jena-6`. The build script waits
  for the new name in step 8.
- **No test aliases for older Jena versions.** DanNet goes straight to
  6.2, and the code no longer compiles on 4.5.

Answered by Simon on 27 October 2026:

- **The prefixes passed in win** over those stored in a TDB2 database.
- **IRIs without a known prefix stay `"<…>"` strings**, as DanNet expects.
  SPARQL's IRIREF rule with a scheme tells them from text, and a string
  literal that passes the rule reads back as a `Literal`.
- **A cljc protocol for resources**, so that any type can stand for an
  IRI.
- **Close to the standards and to Clojure idioms**, with the standard
  cited in the code that follows it.


Open questions
--------------

None before step 5.
