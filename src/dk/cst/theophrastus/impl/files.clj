(ns dk.cst.theophrastus.impl.files
  "RDF files of one graph, for read! and write: their syntax by name, and
  parsing them."
  (:require [clojure.java.io :as io])
  (:import [org.apache.jena.graph Graph GraphMemFactory]
           [org.apache.jena.riot Lang RDFLanguages RDFParser]))

(defn syntax
  "The Jena Lang of the syntax that the name of the `file` gives, e.g.
  Turtle for .ttl, which must be a syntax of one graph."
  ^Lang [file]
  (let [lang (RDFLanguages/filenameToLang (str file))]
    (cond
      (nil? lang)
      (throw (ex-info (str "No RDF syntax for the name " file) {:file file}))

      (RDFLanguages/isQuads lang)
      (throw (ex-info (str "Not a syntax for one graph: " lang) {:file file}))

      :else
      lang)))

(defn parse
  "A new in-memory Graph of the triples in the RDF files of the `sources`,
  each a File, a URL, a URI or a file path."
  ^Graph [sources]
  (let [g (GraphMemFactory/createDefaultGraph)]
    (doseq [s sources
            :let [url  (io/as-url (if (string? s) (io/file s) s))
                  lang (syntax url)]]
      (with-open [in (io/input-stream url)]
        (-> (RDFParser/create)
            (.source in)
            (.lang lang)
            (.base (str url))
            (.parse g))))
    g))
