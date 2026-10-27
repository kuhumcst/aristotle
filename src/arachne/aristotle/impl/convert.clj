(ns arachne.aristotle.impl.convert
  "Jena nodes to Clojure values and back, with the RDF terms of the term
  namespace."
  (:require [arachne.aristotle.term :as term]
            [clojure.string :as str]
            [dk.simongray.langtag :as langtag])
  (:import [arachne.aristotle.term LangStr Literal]
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

(defn prefix-map
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
    (.setNsPrefixes ^Map (prefix-map prefixes))))

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
  "The Clojure value of the literal `node`: a string, a LangStr, the value
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
      (term/->LangStr lex lang)

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
  "The Clojure value of the Jena `node`, with an IRI in one of the
  namespaces of `prefixes` as a keyword."
  [^Node node prefixes]
  (cond
    (nil? node)          nil
    (.isURI node)        (iri->data (.getURI node) prefixes)
    (.isLiteral node)    (literal->data node)
    (.isBlank node)      (symbol (str "_" (.getBlankNodeLabel node)))
    (.isVariable node)   (symbol (str "?" (.getName node)))
    (.isTripleTerm node) (throw (ex-info (str "No triple terms yet: " node)
                                         {:node node}))
    :else                (throw (ex-info (str "No Clojure value for " node)
                                         {:node node}))))

(defprotocol AsNode
  "A Clojure value that stands for an RDF term."
  (node [x prefixes]
    "The Jena node of `x`, with a keyword's prefix looked up in `prefixes`."))

(extend-protocol AsNode
  Node
  (node [n _]
    n)

  Keyword
  (node [k prefixes]
    (if-let [iri (term/iri k prefixes)]
      (NodeFactory/createURI iri)
      (throw (ex-info (str "No namespace for the prefix of " k)
                      {:keyword k}))))

  String
  (node [s prefixes]
    (if-let [iri (term/iri s prefixes)]
      (NodeFactory/createURI iri)
      (NodeFactory/createLiteralString s)))

  URI
  (node [uri prefixes]
    (if-let [iri (term/iri uri prefixes)]
      (NodeFactory/createURI iri)
      (throw (ex-info (str "Not an absolute IRI: " uri) {:uri uri}))))

  Symbol
  (node [sym _]
    (let [s (name sym)]
      (or (when-not (namespace sym)
            (cond
              (= "_" s)                (NodeFactory/createBlankNode)
              (str/starts-with? s "_") (NodeFactory/createBlankNode (subs s 1))
              (str/starts-with? s "?") (Var/alloc (subs s 1))))
          (throw (ex-info (str "Not a blank node or variable: " sym)
                          {:symbol sym})))))

  ;; RDF 1.2 Concepts, section 3.4: a language tag is well-formed by BCP 47
  LangStr
  (node [x _]
    (let [lang (term/lang x)]
      (if (langtag/well-formed? lang)
        (NodeFactory/createLiteralLang (str x) lang)
        (throw (ex-info (str "Not a well-formed language tag: " (pr-str lang))
                        {:lang lang})))))

  Literal
  (node [x prefixes]
    (let [dt (term/iri (term/datatype x) prefixes)]
      (NodeFactory/createLiteralDT
        (str x)
        (.getSafeTypeByName (TypeMapper/getInstance) dt))))

  ;; numbers, booleans, java.time values, BigDecimal, Date and anything
  ;; else with a Literal
  Object
  (node [x prefixes]
    (node (term/literal x) prefixes))

  nil
  (node [_ _]
    (throw (ex-info "No RDF term for nil" {}))))
