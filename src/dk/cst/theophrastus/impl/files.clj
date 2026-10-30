(ns dk.cst.theophrastus.impl.files
  "The syntax and parsing of RDF of one graph, for read! and write."
  (:require [clojure.java.io :as io])
  (:import [java.io InputStream Reader]
           [org.apache.jena.graph Graph GraphMemFactory]
           [org.apache.jena.riot Lang RDFLanguages RDFParser]))

(def aliases
  "The media type of each keyword for an RDF syntax of one graph: the name
  of the syntax (with or without a hyphen) and its file extension."
  {:turtle    "text/turtle"
   :ttl       "text/turtle"
   :n-triples "application/n-triples"
   :ntriples  "application/n-triples"
   :nt        "application/n-triples"
   :rdf-xml   "application/rdf+xml"
   :rdfxml    "application/rdf+xml"
   :rdf       "application/rdf+xml"
   :json-ld   "application/ld+json"
   :jsonld    "application/ld+json"
   :n3        "text/n3"})

(defn lang
  "The Jena Lang of the :syntax option `x`, or of the name of the `file`
  when there is no `x`, e.g. Turtle for .ttl. Without either, as for a
  stream, it's Turtle. The option is a media type, a key of aliases or a
  Lang. It throws when there is no syntax, or when it's a syntax of several
  graphs."
  ^Lang [x file]
  (let [media-type (if (keyword? x) (aliases x) x)
        l          (cond
                     (instance? Lang x)   x
                     (string? media-type) (RDFLanguages/contentTypeToLang
                                            ^String media-type)
                     (nil? x)             (if file
                                            (RDFLanguages/filenameToLang
                                              (str file))
                                            Lang/TURTLE))]
    (cond
      (nil? l)
      (throw (ex-info (if (some? x)
                        (str "No RDF syntax for " (pr-str x))
                        (str "No RDF syntax for the name " file))
                      {:syntax x :file file}))

      (RDFLanguages/isQuads l)
      (throw (ex-info (str "Not a syntax for one graph: " l)
                      {:syntax x :file file}))

      :else
      l)))

(defn parse
  "A new in-memory Graph of the triples in the RDF `sources`, with the
  :syntax and :base of the `opts`. A source is a File, URL, URI or file
  path, or an InputStream or Reader. Without a :syntax, the name of a
  source gives the syntax and a stream is read as Turtle. Without a :base,
  relative IRIs resolve against the URL of the source."
  ^Graph [sources {:keys [syntax base]}]
  (let [g (GraphMemFactory/createDefaultGraph)]
    (doseq [s sources]
      (if (or (instance? InputStream s) (instance? Reader s))
        (-> (if (instance? Reader s)
              (.source (RDFParser/create) ^Reader s)
              (.source (RDFParser/create) ^InputStream s))
            (.lang (lang syntax nil))
            (.base ^String base)
            (.parse g))
        (let [url (io/as-url (if (string? s) (io/file s) s))]
          (with-open [in (io/input-stream url)]
            (-> (RDFParser/create)
                (.source in)
                (.lang (lang syntax url))
                (.base (or base (str url)))
                (.parse g))))))
    g))
