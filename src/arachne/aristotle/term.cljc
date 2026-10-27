(ns arachne.aristotle.term
  "RDF terms as values that work the same in Clojure and ClojureScript.

  An IRI is a keyword when its prefix is known, e.g. :rdfs/label, and a
  string in angle brackets when it isn't, e.g. \"<https://cst.dk>\". A
  language-tagged string is a LangStr. A Literal holds any other literal
  as its lexical form and datatype IRI.

  Both types print with a reader tag and read back from it:

      #rdf/langstring [\"hund\" \"da\"]
      #rdf/literal [\"1.50\" \"<http://www.w3.org/2001/XMLSchema#decimal>\"]")

(def default-prefixes
  "The prefixes used when none are given: each prefix as a keyword, mapped
  to its namespace IRI in angle brackets, as in Flint's :prefixes."
  {:rdf  "<http://www.w3.org/1999/02/22-rdf-syntax-ns#>"
   :rdfs "<http://www.w3.org/2000/01/rdf-schema#>"
   :xsd  "<http://www.w3.org/2001/XMLSchema#>"
   :owl  "<http://www.w3.org/2002/07/owl#>"})

;; SPARQL 1.1 Query Language, section 19.8, rule [139] IRIREF, starting
;; with a scheme as in RFC 3987, section 2.2, since an IRI in RDF is
;; absolute (RDF 1.1 Concepts, section 3.2)
(def iri-re
  "Matches an absolute IRI in angle brackets."
  #"<[A-Za-z][A-Za-z0-9+.\-]*:[^<>\"{}|^`\\\x00-\x20]*>")

(defn iri-string?
  "Whether `x` is an absolute IRI in angle brackets, e.g.
  \"<https://cst.dk>\" but not \"<b>bold</b>\" or \"<hello>\"."
  [x]
  (and (string? x)
       (some? (re-matches iri-re x))))

(defprotocol Resource
  "A value that stands for an IRI. Extend it only to a type that keeps the
  IRI exactly as written. java.net.URL, js/URL and goog.Uri all change
  some IRIs, e.g. by lower-casing the scheme or percent-encoding Æ."
  (iri [x prefixes]
    "The IRI that `x` stands for, without angle brackets, or nil when there
    is none. A keyword's prefix is looked up in `prefixes`, a map in the
    shape of default-prefixes."))

(extend-protocol Resource
  #?(:clj clojure.lang.Keyword :cljs cljs.core/Keyword)
  (iri [k prefixes]
    (when-let [ns-iri (some->> (namespace k) keyword (get prefixes))]
      (some-> (iri ns-iri prefixes) (str (name k)))))

  #?(:clj String :cljs string)
  (iri [s _]
    (when (iri-string? s)
      (subs s 1 (dec (count s)))))

  #?@(:clj [java.net.URI
            (iri [uri _]
              (when (.isAbsolute uri)
                (str uri)))])

  #?(:clj Object :cljs default)
  (iri [_ _]
    nil)

  nil
  (iri [_ _]
    nil))

(defn lower-case-lang
  "The language tag `lang` in lower case, which is how two tags compare."
  [lang]
  ;; the root locale, since e.g. a Turkish one turns IT into ıt
  #?(:clj  (.toLowerCase ^String lang java.util.Locale/ROOT)
     :cljs (.toLowerCase lang)))

;; RDF 1.2 Concepts, section 3.4: the lexical forms are compared
;; case-sensitively, the language tags case-insensitively
(deftype LangStr [s lang]
  Object
  (toString [_]
    s)

  #?@(:clj  [(equals [_ x]
               (and (instance? LangStr x)
                    (= s (.-s ^LangStr x))
                    (= (lower-case-lang lang)
                       (lower-case-lang (.-lang ^LangStr x)))))
             (hashCode [_]
               (hash [s (lower-case-lang lang)]))

             clojure.lang.IHashEq
             (hasheq [this]
               (.hashCode this))]
      :cljs [IEquiv
             (-equiv [_ x]
               (and (instance? LangStr x)
                    (= s (.-s ^LangStr x))
                    (= (lower-case-lang lang)
                       (lower-case-lang (.-lang ^LangStr x)))))

             IHash
             (-hash [_]
               (hash [s (lower-case-lang lang)]))

             IPrintWithWriter
             (-pr-writer [_ writer opts]
               (-write writer "#rdf/langstring ")
               (-pr-writer [s lang] writer opts))]))

(defn lang
  "The language tag of the LangStr `x`, as written."
  [^LangStr x]
  (.-lang x))

(deftype Literal [s datatype]
  Object
  (toString [_]
    s)

  #?@(:clj  [(equals [_ x]
               (and (instance? Literal x)
                    (= s (.-s ^Literal x))
                    (= datatype (.-datatype ^Literal x))))
             (hashCode [_]
               (hash [s datatype]))

             clojure.lang.IHashEq
             (hasheq [_]
               (hash [s datatype]))]
      :cljs [IEquiv
             (-equiv [_ x]
               (and (instance? Literal x)
                    (= s (.-s ^Literal x))
                    (= datatype (.-datatype ^Literal x))))

             IHash
             (-hash [_]
               (hash [s datatype]))

             IPrintWithWriter
             (-pr-writer [_ writer opts]
               (-write writer "#rdf/literal ")
               (-pr-writer [s datatype] writer opts))]))

(defn datatype
  "The datatype IRI of the Literal `x`, in angle brackets."
  [^Literal x]
  (.-datatype x))

(defn literal
  "The Literal of the lexical form `s` with the datatype `dt`, xsd:string
  unless given. The datatype is a keyword or an IRI in angle brackets, and
  a keyword's prefix is looked up in `prefixes`, default-prefixes unless
  given."
  ([s]
   (literal s :xsd/string))
  ([s dt]
   (literal s dt default-prefixes))
  ([s dt prefixes]
   (if-let [dt-iri (iri dt prefixes)]
     (->Literal s (str "<" dt-iri ">"))
     (throw (ex-info (str "Not a datatype IRI: " (pr-str dt))
                     {:datatype dt})))))

#?(:clj
   (defmethod print-method LangStr
     [^LangStr x ^java.io.Writer writer]
     (.write writer "#rdf/langstring ")
     (print-method [(.-s x) (.-lang x)] writer)))

#?(:clj
   (defmethod print-method Literal
     [^Literal x ^java.io.Writer writer]
     (.write writer "#rdf/literal ")
     (print-method [(.-s x) (.-datatype x)] writer)))

(defn read-langstring
  "The LangStr of the vector `form`, [s lang], for the tag #rdf/langstring."
  [[s lang :as form]]
  (->LangStr s lang))

(defn read-literal
  "The Literal of the vector `form`, [s datatype], for the tag #rdf/literal."
  [[s datatype :as form]]
  (->Literal s datatype))
