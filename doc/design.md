Design
======

These principles shape Theophrastus. The docstrings hold the details.

- **Familiar to Clojure developers.** The API borrows its conventions from
  the databases that Clojure developers already use.
- **Close to the standards.** Where a standard settles a question, the
  library follows it rather than inventing an answer of its own.
- **Lossless round trips.** Data comes back exactly as it went in, never
  rounded or reshaped to fit the language's own types.
- **The same everywhere.** The same code gives the same results whatever
  store or platform it runs on.
- **Sane defaults, many options.** The common case needs no configuration,
  yet anything that varies can be configured. An argument always means one
  thing.
- **No global state.** Each db carries its own settings, so one part of a
  program can't change another's.
