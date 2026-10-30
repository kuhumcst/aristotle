(ns dk.cst.theophrastus.convert
  "Jena nodes to Clojure values and back, with the RDF terms of the term
  namespace. It also turns the data that theo/transact! and theo/retract!
  take into Jena triples and patterns."
  (:require [dk.cst.theophrastus.term :as term]
            [clojure.string :as str]
            [dk.simongray.langtag :as langtag])
  (:import [dk.cst.theophrastus.term LangString Literal]
           [clojure.lang Keyword Symbol]
           [java.net URI]
           [java.util Map]
           [org.apache.jena.datatypes TypeMapper]
           [org.apache.jena.datatypes.xsd XSDDatatype]
           [org.apache.jena.graph Node NodeFactory Triple]
           [org.apache.jena.shared PrefixMapping PrefixMapping$Factory]
           [org.apache.jena.sparql.core Var]
           [org.apache.jena.sys JenaSystem]))

;; Jena's TypeMapper is nil until Jena has initialised itself
(JenaSystem/init)

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
  "The key that holds the prefixes of the db in a query's context, so that a
  SPARQL function can convert values as the query does."
  (org.apache.jena.sparql.util.Symbol/create "dk.cst.theophrastus/prefixes"))

(def namespace-index
  "The namespaces of `prefixes` as [prefix namespace] pairs, without angle
  brackets and longest first. It's memoized, since a db reads every IRI
  with the same prefixes."
  (memoize
    (fn [prefixes]
      (->> (for [[prefix ns-iri] prefixes
                 :let [iri (term/iri ns-iri prefixes)]
                 :when iri]
             [prefix iri])
           (sort-by (comp - count second))
           (vec)))))

(defn iri->data
  "The IRI `s` as a keyword, by the longest namespace in `prefixes` that it
  starts with, or in angle brackets when there is none. It also stays in
  angle brackets when the rest is empty or has a slash, since that keyword
  couldn't be read back."
  [^String s prefixes]
  ;; reduce and nth rather than some and second, which allocate a seq for
  ;; each namespace, since this runs for every IRI that a query returns
  (or (when-let [[prefix ^String ns-iri]
                 (reduce (fn [_ entry]
                           (when (.startsWith s ^String (nth entry 1))
                             (reduced entry)))
                         nil
                         (namespace-index prefixes))]
        (let [local (subs s (count ns-iri))]
          (when-not (or (= "" local) (str/includes? local "/"))
            (keyword (name prefix) local))))
      (str "<" s ">")))

(defn literal->data
  "The Clojure value of the literal `node`: a string, a LangString, the value
  of a native datatype, or a Literal."
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
  "The Clojure value of the Jena `node`, as theo/q returns it, with
  `prefixes` for keywords (term/default-prefixes when none are given). It
  returns nil for a nil node, e.g. an unbound variable. Use it on the
  results of Jena's own query API."
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
  IRI or has a Literal only needs term/AsIri or term/AsLiteral, which also
  work in ClojureScript. Extend AsNode for any other term, e.g. Jena's own
  RDFNode or a type of yours for blank nodes."
  (as-node [x prefixes]
    "The Jena node of `x`, as node returns it."))

(defn node
  "The Jena node of `x`, with `prefixes` for keywords (term/default-prefixes
  when none are given)."
  (^Node [x]
   (node x term/default-prefixes))
  (^Node [x prefixes]
   (as-node x prefixes)))

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

;; RDF Schema 1.1, section 5.1.5: rdf:_1, rdf:_2 and so on are the
;; container membership properties, not reversed ones
(defn reverse-property
  "The predicate that the keyword `p` names the other way round, e.g.
  :ex/knows for :ex/_knows, or nil when it doesn't."
  [p]
  (when (keyword? p)
    (let [n (name p)]
      (when (and (str/starts-with? n "_")
                 (not (re-matches #"_[1-9][0-9]*" n)))
        (keyword (namespace p) (subs n 1))))))

(defn triple?
  "Whether `x` is an [s p o] triple of Clojure values, rather than a coll of
  them."
  [x]
  (and (vector? x) (= 3 (count x)) (not-any? coll? x)))

(defn- subject-node
  "The Jena node for the subject of the map `m`: its :rdf/about, or a new
  blank node when there is none."
  ^Node [m prefixes]
  (if-let [about (:rdf/about m)]
    (node about prefixes)
    (NodeFactory/createBlankNode)))

(defn- conj-map-triples!
  "Add the Jena triples of the map `m`, whose subject is the node `s`, to
  the transient `acc`, with `prefixes` for keywords."
  [acc m s prefixes]
  (reduce-kv
    (fn [acc p v]
      (if (= :rdf/about p)
        acc
        (let [reverse (reverse-property p)
              pred    (node (or reverse p) prefixes)]
          (reduce (fn [acc x]
                    (if (nil? x)
                      acc
                      (let [o (if (map? x)
                                (subject-node x prefixes)
                                (node x prefixes))]
                        (cond-> (conj! acc (if reverse
                                             (Triple/create o pred s)
                                             (Triple/create s pred o)))
                          (map? x) (conj-map-triples! x o prefixes)))))
                  acc
                  (if (or (set? v) (sequential? v)) v [v])))))
    acc
    m))

(defn- conj-triples!
  "Add the Jena triples of `tx-data` to the transient `acc`, with
  `prefixes` for keywords."
  [acc tx-data prefixes]
  (cond
    (map? tx-data)
    (conj-map-triples! acc tx-data (subject-node tx-data prefixes) prefixes)

    (triple? tx-data)
    (let [[s p o] tx-data]
      (conj! acc (Triple/create (node s prefixes)
                                (node p prefixes)
                                (node o prefixes))))

    (coll? tx-data)
    (reduce #(conj-triples! %1 %2 prefixes) acc tx-data)

    :else
    (throw (ex-info (str "Not a triple or a map: " (pr-str tx-data))
                    {:tx-data tx-data}))))

;; one transient vector for all of them, since tx-data can have millions
(defn triples
  "The Jena triples of `tx-data` (as theo/transact! takes it), with
  `prefixes` for keywords (term/default-prefixes when none are given)."
  ([tx-data]
   (triples tx-data term/default-prefixes))
  ([tx-data prefixes]
   (persistent! (conj-triples! (transient []) tx-data prefixes))))

(defn pattern->nodes
  "The Jena nodes of the [s p o] `pattern` (as theo/retract! takes it), with
  Node/ANY for _ and `prefixes` for keywords."
  [pattern prefixes]
  (if (triple? pattern)
    (mapv #(if (= '_ %) Node/ANY (node % prefixes)) pattern)
    (throw (ex-info (str "Not a pattern: " (pr-str pattern))
                    {:pattern pattern}))))
