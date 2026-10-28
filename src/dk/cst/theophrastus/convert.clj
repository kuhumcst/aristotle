(ns dk.cst.theophrastus.convert
  "Jena nodes to Clojure values and back, with the RDF terms of the term
  namespace."
  (:require [dk.cst.theophrastus.term :as term]
            [clojure.string :as str]
            [dk.simongray.langtag :as langtag])
  (:import [dk.cst.theophrastus.term LangString Literal]
           [clojure.lang Keyword Symbol]
           [java.net URI]
           [java.util Map]
           [org.apache.jena.datatypes TypeMapper]
           [org.apache.jena.datatypes.xsd XSDDatatype]
           [org.apache.jena.graph Node NodeFactory]
           [org.apache.jena.shared PrefixMapping PrefixMapping$Factory]
           [org.apache.jena.sparql.core Var]
           [org.apache.jena.sys JenaSystem]))

;; Jena's TypeMapper is nil until Jena has initialised itself
(JenaSystem/init)

(def namespace-index
  "The namespaces of `prefixes`, longest first, each as [prefix namespace]
  without angle brackets. It's memoized, since a db reads every IRI with
  the same prefixes."
  (memoize
    (fn [prefixes]
      (->> (for [[prefix ns-iri] prefixes
                 :let [iri (term/iri ns-iri prefixes)]
                 :when iri]
             [prefix iri])
           (sort-by (comp - count second))
           (vec)))))

;; named after PrefixMapping's getNsPrefixMap, which returns this map
(defn ns-prefix-map
  "The `prefixes` as Jena takes them: each prefix as a string, mapped to its
  namespace IRI without angle brackets."
  [prefixes]
  (into {}
        (for [[prefix ns-iri] prefixes]
          (if-let [iri (term/iri ns-iri prefixes)]
            [(name prefix) iri]
            (throw (ex-info (str "Not a namespace IRI: " (pr-str ns-iri))
                            {:prefix prefix :namespace ns-iri}))))))

(defn prefix-mapping
  "The Jena PrefixMapping of the `prefixes`."
  ^PrefixMapping [prefixes]
  (doto (PrefixMapping$Factory/create)
    (.setNsPrefixes ^Map (ns-prefix-map prefixes))))

(def ^org.apache.jena.sparql.util.Symbol prefixes-symbol
  "The key under which the context of a query holds the prefixes of its db,
  so that a SPARQL function can convert values as the query does."
  (org.apache.jena.sparql.util.Symbol/create "dk.cst.theophrastus/prefixes"))

(defn iri->data
  "The IRI `s` as a keyword, when the longest namespace of `prefixes` that
  it starts with leaves a name, without a slash, or else in angle brackets.
  A keyword with an empty name, e.g. :owl/, can't be read back."
  [^String s prefixes]
  (or (when-let [[prefix ^String ns-iri]
                 (some #(when (.startsWith s ^String (second %)) %)
                       (namespace-index prefixes))]
        (let [local (subs s (count ns-iri))]
          (when-not (or (= "" local) (str/includes? local "/"))
            (keyword (name prefix) local))))
      (str "<" s ">")))

(defn literal->data
  "The Clojure value of the literal `node`: a string, a LangString, the value
  of a native datatype, or else a Literal."
  [^Node node]
  (let [lex  (.getLiteralLexicalForm node)
        dt   (.getLiteralDatatypeURI node)
        lang (.getLiteralLanguage node)]
    (cond
      (.getLiteralBaseDirection node)
      (throw (ex-info (str "No base direction yet: " node)
                      {:node node}))

      (not (str/blank? lang))
      (term/->LangString lex lang)

      ;; text that passes the IRI rule would become an IRI when written
      (and (= dt (.getURI XSDDatatype/XSDstring))
           (not (term/iri-string? lex)))
      lex

      :else
      (let [x (term/->Literal lex (str "<" dt ">"))]
        (if (term/native-datatypes (term/datatype x))
          (term/value x)
          x)))))

(defn data
  "The value of the Jena `node` as a query returns it, with IRIs under the
  `prefixes` as keywords, default-prefixes unless given, e.g. for the rows of
  a ResultSet from Jena's own query API. A nil node, e.g. an unbound
  variable, is nil."
  ([node]
   (data node term/default-prefixes))
  ([^Node node prefixes]
   (cond
     (nil? node)          nil
     (.isURI node)        (iri->data (.getURI node) prefixes)
     (.isLiteral node)    (literal->data node)
     (.isBlank node)      (symbol (str "_" (.getBlankNodeLabel node)))
     (.isVariable node)   (symbol (str "?" (.getName node)))
     (.isTripleTerm node) (throw (ex-info (str "No triple terms yet: " node)
                                          {:node node}))
     :else                (throw (ex-info (str "No Clojure value for " node)
                                          {:node node})))))

(defprotocol AsNode
  "A Clojure value that stands for an RDF term. A type that stands for an
  IRI or has a Literal only needs term/AsIri or term/AsLiteral, which
  ClojureScript has too. Extend AsNode for any other term, e.g. Jena's own
  RDFNode or a type of yours for blank nodes."
  (as-node [x prefixes]
    "The Jena node of `x`, as node returns it."))

(defn node
  "The Jena node of `x`, with a keyword's prefix looked up in `prefixes`."
  ^Node [x prefixes]
  (as-node x prefixes))

(extend-protocol AsNode
  Node
  (as-node [n _]
    n)

  Keyword
  (as-node [k prefixes]
    (if-let [iri (term/iri k prefixes)]
      (NodeFactory/createURI iri)
      (throw (ex-info (str "No namespace for the prefix of " k)
                      {:keyword k}))))

  String
  (as-node [s prefixes]
    (if-let [iri (term/iri s prefixes)]
      (NodeFactory/createURI iri)
      (NodeFactory/createLiteralString s)))

  URI
  (as-node [uri prefixes]
    (if-let [iri (term/iri uri prefixes)]
      (NodeFactory/createURI iri)
      (throw (ex-info (str "Not an absolute IRI: " uri) {:uri uri}))))

  Symbol
  (as-node [sym _]
    (let [s (name sym)]
      (or (when-not (namespace sym)
            (cond
              (= "_" s)                (NodeFactory/createBlankNode)
              (str/starts-with? s "_") (NodeFactory/createBlankNode (subs s 1))
              (str/starts-with? s "?") (Var/alloc (subs s 1))))
          (throw (ex-info (str "Not a blank node or variable: " sym)
                          {:symbol sym})))))

  ;; RDF 1.2 Concepts, section 3.4: a language tag is well-formed by BCP 47
  LangString
  (as-node [x _]
    (let [lang (term/lang x)]
      (if (langtag/well-formed? lang)
        (NodeFactory/createLiteralLang (str x) lang)
        (throw (ex-info (str "Not a well-formed language tag: " (pr-str lang))
                        {:lang lang})))))

  Literal
  (as-node [x prefixes]
    (let [dt (term/iri (term/datatype x) prefixes)]
      (NodeFactory/createLiteralDT
        (str x)
        (.getSafeTypeByName (TypeMapper/getInstance) dt))))

  ;; any other type of term/AsIri, or else numbers, booleans, java.time
  ;; values, BigDecimal, Date and anything else with a Literal
  Object
  (as-node [x prefixes]
    (if-let [iri (term/iri x prefixes)]
      (NodeFactory/createURI iri)
      (as-node (term/literal x) prefixes)))

  nil
  (as-node [_ _]
    (throw (ex-info "No RDF term for nil" {}))))
