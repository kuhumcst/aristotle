(ns dk.cst.theophrastus.term
  "RDF terms as values that work the same in Clojure and ClojureScript.

  An IRI is a keyword when its prefix is known, e.g. :rdfs/label, and a
  string in angle brackets when it isn't, e.g. \"<https://cst.dk>\". A
  language-tagged string is a LangString. A Literal holds any other literal
  as its lexical form and datatype IRI.

  Both types print with a reader tag and read back from it:

      #rdf/langstring [\"hund\" \"da\"]
      #rdf/literal [\"1.50\" \"<http://www.w3.org/2001/XMLSchema#decimal>\"]

  The function value turns a Literal into a type of the platform where one
  holds it exactly, and literal turns such a value back into a Literal."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.time Duration Instant LocalDate LocalDateTime LocalTime
                    MonthDay OffsetDateTime OffsetTime Period Year YearMonth
                    ZoneOffset]
                   [java.time.format DateTimeFormatter])))

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

(defn blank-node?
  "Whether `x` is a blank node, a symbol such as _b1."
  [x]
  (and (symbol? x)
       (nil? (namespace x))
       (str/starts-with? (name x) "_")))

(defprotocol AsIri
  "A value that stands for an IRI. Extend it only to a type that keeps the
  IRI exactly as written. java.net.URL, js/URL and goog.Uri all change
  some IRIs, e.g. by lower-casing the scheme or percent-encoding Æ."
  (as-iri [x prefixes]
    "The IRI that `x` stands for, as iri returns it."))

(defn iri
  "The IRI that `x` stands for, without angle brackets, or nil when there is
  none. A keyword's prefix is looked up in `prefixes`, a map in the shape of
  default-prefixes."
  [x prefixes]
  (as-iri x prefixes))

(extend-protocol AsIri
  #?(:clj clojure.lang.Keyword :cljs cljs.core/Keyword)
  (as-iri [k prefixes]
    (when-let [ns-iri (some->> (namespace k) keyword (get prefixes))]
      (some-> (iri ns-iri prefixes) (str (name k)))))

  #?(:clj String :cljs string)
  (as-iri [s _]
    (when (iri-string? s)
      (subs s 1 (dec (count s)))))

  #?@(:clj [java.net.URI
            (as-iri [uri _]
              (when (.isAbsolute uri)
                (str uri)))])

  #?(:clj Object :cljs default)
  (as-iri [_ _]
    nil)

  nil
  (as-iri [_ _]
    nil))

(defn lower-case-lang
  "The language tag `lang` in lower case, which is how two tags compare."
  [lang]
  ;; the root locale, since e.g. a Turkish one turns IT into ıt
  #?(:clj  (.toLowerCase ^String lang java.util.Locale/ROOT)
     :cljs (.toLowerCase lang)))

;; RDF 1.2 Concepts, section 3.4: the lexical forms are compared
;; case-sensitively, the language tags case-insensitively
(deftype LangString [s lang]
  Object
  (toString [_]
    s)

  #?@(:clj  [(equals [_ x]
               (and (instance? LangString x)
                    (= s (.-s ^LangString x))
                    (= (lower-case-lang lang)
                       (lower-case-lang (.-lang ^LangString x)))))
             (hashCode [_]
               (hash [s (lower-case-lang lang)]))

             clojure.lang.IHashEq
             (hasheq [this]
               (.hashCode this))]
      :cljs [IEquiv
             (-equiv [_ x]
               (and (instance? LangString x)
                    (= s (.-s ^LangString x))
                    (= (lower-case-lang lang)
                       (lower-case-lang (.-lang ^LangString x)))))

             IHash
             (-hash [_]
               (hash [s (lower-case-lang lang)]))

             IPrintWithWriter
             (-pr-writer [_ writer opts]
               (-write writer "#rdf/langstring ")
               (-pr-writer [s lang] writer opts))]))

(defn lang
  "The language tag of the LangString `x`, as written."
  [^LangString x]
  (.-lang x))

#?(:clj
   (defmethod print-method LangString
     [^LangString x ^java.io.Writer writer]
     (.write writer "#rdf/langstring ")
     (print-method [(.-s x) (.-lang x)] writer)))

(defn read-langstring
  "The LangString of the vector `form`, [s lang], for the tag #rdf/langstring."
  [[s lang :as form]]
  (->LangString s lang))

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

#?(:clj
   (defmethod print-method Literal
     [^Literal x ^java.io.Writer writer]
     (.write writer "#rdf/literal ")
     (print-method [(.-s x) (.-datatype x)] writer)))

(defn read-literal
  "The Literal of the vector `form`, [s datatype], for the tag #rdf/literal."
  [[s datatype :as form]]
  (->Literal s datatype))

(defprotocol AsLiteral
  "A value that has a Literal: the inverse of value."
  (as-literal [x]
    "The Literal of the value `x`, or an exception when it has none."))

(defn literal
  "The Literal of the value `x`, or of the lexical form `s` with the
  datatype `dt`. The datatype is a keyword or an IRI in angle brackets, and
  a keyword's prefix is looked up in `prefixes`, default-prefixes unless
  given.

      (literal \"<urn:isbn:123>\")
      ;; => #rdf/literal [\"<urn:isbn:123>\"
      ;;                  \"<http://www.w3.org/2001/XMLSchema#string>\"]

      (literal (java.time.LocalDate/of 2024 1 2))
      ;; => #rdf/literal [\"2024-01-02\"
      ;;                  \"<http://www.w3.org/2001/XMLSchema#date>\"]"
  ([x]
   (as-literal x))
  ([s dt]
   (literal s dt default-prefixes))
  ([s dt prefixes]
   (if-let [dt-iri (iri dt prefixes)]
     (->Literal s (str "<" dt-iri ">"))
     (throw (ex-info (str "Not a datatype IRI: " (pr-str dt))
                     {:datatype dt})))))

;; XSD 1.1 Part 2, sections 3.4.13 to 3.4.25
(def integer-bounds
  "The smallest and largest value of xsd:integer and each type derived from
  it, by local name, with nil for no bound."
  {"integer"            [nil nil]
   "nonPositiveInteger" [nil 0]
   "negativeInteger"    [nil -1]
   "long"               [-9223372036854775808 9223372036854775807]
   "int"                [-2147483648 2147483647]
   "short"              [-32768 32767]
   "byte"               [-128 127]
   "nonNegativeInteger" [0 nil]
   "unsignedLong"       [0 18446744073709551615]
   "unsignedInt"        [0 4294967295]
   "unsignedShort"      [0 65535]
   "unsignedByte"       [0 255]
   "positiveInteger"    [1 nil]})

;; XSD 1.1 Part 2: the regular expressions of sections 3.3.2.1 (boolean),
;; 3.3.3.1 (decimal), 3.3.4.2 and 3.3.5.2 (float and double), 3.3.6.2
;; (duration), 3.3.7.2 to 3.3.12.2 (dateTime to gMonthDay), 3.4.13
;; (integer) and 3.4.26.1 to 3.4.28.1 (yearMonthDuration, dayTimeDuration
;; and dateTimeStamp), built from the fragments they share
(def xsd-lexical-spaces
  "The lexical space of each XSD datatype that value knows, as a regular
  expression, by the datatype's local name."
  (let [year     "-?([1-9][0-9]{3,}|0[0-9]{3})"
        month    "(0[1-9]|1[0-2])"
        day      "(0[1-9]|[12][0-9]|3[01])"
        hms      (str "(([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\\.[0-9]+)?"
                      "|(24:00:00(\\.0+)?))")
        tz       "(Z|(\\+|-)((0[0-9]|1[0-3]):[0-5][0-9]|14:00))"
        du-time  (str "T(([0-9]+H)([0-9]+M)?([0-9]+(\\.[0-9]+)?S)?"
                      "|([0-9]+M)([0-9]+(\\.[0-9]+)?S)?"
                      "|([0-9]+(\\.[0-9]+)?S))")
        floating (str "(\\+|-)?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)"
                      "([Ee](\\+|-)?[0-9]+)?|(\\+|-)?INF|NaN")]
    (update-vals
      (merge
        (zipmap (keys integer-bounds) (repeat "(\\+|-)?[0-9]+"))
        {"boolean"           "true|false|1|0"
         "double"            floating
         "float"             floating
         "decimal"           "(\\+|-)?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)"
         "dateTime"          (str year "-" month "-" day "T" hms tz "?")
         "dateTimeStamp"     (str year "-" month "-" day "T" hms tz)
         "date"              (str year "-" month "-" day tz "?")
         "time"              (str hms tz "?")
         "gYearMonth"        (str year "-" month tz "?")
         "gYear"             (str year tz "?")
         "gMonthDay"         (str "--" month "-" day tz "?")
         "dayTimeDuration"   (str "-?P(([0-9]+D)(" du-time ")?|" du-time ")")
         "yearMonthDuration" "-?P((([0-9]+Y)([0-9]+M)?)|([0-9]+M))"})
      re-pattern)))

(defmulti value
  "The value of the Literal `x` as a type of the platform, or `x` itself
  when no type there holds it exactly or its lexical form isn't valid. A
  value that isn't a Literal comes back as it is.

  On both platforms, xsd:boolean, xsd:double, xsd:float, and xsd:integer
  with the types derived from it become booleans and numbers. In
  ClojureScript, an integer past the largest safe integer stays a Literal.
  On the JVM, an xsd:decimal also becomes a BigDecimal, and the XSD date,
  time and duration types become java.time values, e.g. an xsd:dateTime
  with a time zone an OffsetDateTime and one without a LocalDateTime. In
  ClojureScript, no type holds these exactly. For a datatype of your own,
  add a method for its IRI in angle brackets."
  (fn [x]
    (when (instance? Literal x)
      (datatype x))))

(defmethod value :default
  [x]
  x)

(def native-values
  "The function that reads a valid lexical form of each XSD datatype whose
  values are native on both platforms, by local name. It returns nil for
  an integer out of the type's bounds, or past the largest safe integer in
  ClojureScript."
  (let [special {"INF" ##Inf "+INF" ##Inf "-INF" ##-Inf "NaN" ##NaN}
        integer (fn [[lo hi]]
                  (fn [s]
                    (let [n #?(:clj (bigint s) :cljs (js/Number s))]
                      (when (and (or (nil? lo) (<= lo n))
                                 (or (nil? hi) (<= n hi))
                                 #?(:cljs (js/Number.isSafeInteger n)))
                        #?(:clj  (if (<= Long/MIN_VALUE n Long/MAX_VALUE)
                                   (long n)
                                   n)
                           :cljs n)))))]
    (merge
      (update-vals integer-bounds integer)
      {"boolean" #(contains? #{"true" "1"} %)
       "double"  #(or (special %)
                      #?(:clj (Double/parseDouble %) :cljs (js/Number %)))
       ;; float refuses the infinities, unchecked-float doesn't
       "float"   #(or (some-> (special %) #?(:clj unchecked-float))
                      #?(:clj (Float/parseFloat %) :cljs (js/Number %)))})))

(def native-datatypes
  "The IRIs, in angle brackets, of the datatypes whose values are native on
  both platforms. A query returns their values, not Literals."
  (set (map #(str "<http://www.w3.org/2001/XMLSchema#" % ">")
            (keys native-values))))

#?(:clj
   (def xsd-values
     "The function that reads a valid lexical form of each XSD datatype that
     value converts on the JVM, by local name. It returns nil when no type
     holds the value, e.g. for a date with a time zone."
     (let [tz? #(re-find #"(Z|[+-][0-9]{2}:[0-9]{2})$" %)]
       {"decimal"           #(BigDecimal. ^String %)
        "dateTime"          #(if (tz? %)
                               (OffsetDateTime/parse %)
                               (LocalDateTime/parse %))
        "dateTimeStamp"     #(OffsetDateTime/parse %)
        "date"              #(when-not (tz? %) (LocalDate/parse %))
        "time"              #(if (tz? %)
                               (OffsetTime/parse %)
                               (LocalTime/parse %))
        "gYearMonth"        #(when-not (tz? %) (YearMonth/parse %))
        "gYear"             #(when-not (tz? %) (Year/parse %))
        "gMonthDay"         #(when-not (tz? %) (MonthDay/parse %))
        "dayTimeDuration"   #(Duration/parse %)
        "yearMonthDuration" #(Period/parse %)})))

(doseq [[local-name parse] (merge native-values #?(:clj xsd-values))
        :let [re (xsd-lexical-spaces local-name)]]
  (defmethod value (str "<http://www.w3.org/2001/XMLSchema#" local-name ">")
    [x]
    ;; these types collapse white space before reading (whiteSpace =
    ;; collapse), and java.time refuses some valid forms, e.g. 24:00:00
    (let [s (str/trim (str x))
          v (when (re-matches re s)
              (try
                (parse s)
                (catch #?(:clj Exception :cljs :default) _)))]
      (if (some? v) v x))))

(extend-protocol AsLiteral
  Literal
  (as-literal [x]
    x)

  #?(:clj String :cljs string)
  (as-literal [s]
    (literal s :xsd/string))

  #?(:clj Boolean :cljs boolean)
  (as-literal [b]
    (literal (str b) :xsd/boolean))

  ;; JavaScript has no integer type, so a whole number is an xsd:integer
  #?@(:cljs [number
             (as-literal [n]
               (if (js/Number.isSafeInteger n)
                 (literal (str n) :xsd/integer)
                 (literal (get {##Inf "INF" ##-Inf "-INF"} n (str n))
                          :xsd/double)))])

  #?(:clj Object :cljs default)
  (as-literal [x]
    (throw (ex-info (str "No literal for " (pr-str x)) {:value x})))

  nil
  (as-literal [_]
    (throw (ex-info "No literal for nil" {:value nil}))))

#?(:clj
   (def java-time-datatypes
     "The XSD datatype of each java.time class that value returns, and the
     ISO 8601 form of its values. That form is the XSD lexical form, apart
     from the plus sign that ISO 8601 puts before a year past 9999."
     {LocalDate      [:xsd/date str]
      LocalDateTime  [:xsd/dateTime
                      #(.format DateTimeFormatter/ISO_LOCAL_DATE_TIME
                                ^LocalDateTime %)]
      OffsetDateTime [:xsd/dateTime
                      #(.format DateTimeFormatter/ISO_OFFSET_DATE_TIME
                                ^OffsetDateTime %)]
      LocalTime      [:xsd/time
                      #(.format DateTimeFormatter/ISO_LOCAL_TIME ^LocalTime %)]
      OffsetTime     [:xsd/time
                      #(.format DateTimeFormatter/ISO_OFFSET_TIME
                                ^OffsetTime %)]
      YearMonth      [:xsd/gYearMonth
                      #(.format (DateTimeFormatter/ofPattern "uuuu-MM")
                                ^YearMonth %)]
      Year           [:xsd/gYear
                      #(.format (DateTimeFormatter/ofPattern "uuuu") ^Year %)]
      MonthDay       [:xsd/gMonthDay str]
      Duration       [:xsd/dayTimeDuration
                      #(if (.isNegative ^Duration %)
                         (str "-" (.negated ^Duration %))
                         (str %))]}))

#?(:clj
   (doseq [[c [dt iso]] java-time-datatypes]
     (extend c
       AsLiteral
       {:as-literal (fn [x]
                      (literal (str/replace-first (iso x) #"^\+" "") dt))})))

#?(:clj
   (extend-protocol AsLiteral
     BigDecimal
     (as-literal [x]
       (literal (.toPlainString x) :xsd/decimal))

     ;; XSD has a sign for the whole duration, not for each part
     Period
     (as-literal [p]
       (let [p' (if (some neg? [(.getYears p) (.getMonths p) (.getDays p)])
                  (.negated p)
                  p)]
         (if (some neg? [(.getYears p') (.getMonths p') (.getDays p')])
           (throw (ex-info (str "No XSD duration has parts of both signs: " p)
                           {:value p}))
           (literal (str (when-not (identical? p p') "-")
                         (if (.isZero p) "P0M" (str p')))
                    (if (zero? (.getDays p))
                      :xsd/yearMonthDuration
                      :xsd/duration)))))

     Instant
     (as-literal [x]
       (as-literal (OffsetDateTime/ofInstant x ZoneOffset/UTC)))

     java.util.Date
     (as-literal [x]
       (as-literal (.toInstant x)))

     Double
     (as-literal [x]
       (literal (get {##Inf "INF" ##-Inf "-INF"} x (str x)) :xsd/double))

     Float
     (as-literal [x]
       (literal (get {##Inf "INF" ##-Inf "-INF"} (double x) (str x))
                :xsd/float))))

#?(:clj
   (doseq [c [Long Integer Short Byte clojure.lang.BigInt BigInteger]]
     (extend c
       AsLiteral
       {:as-literal (fn [n]
                      (literal (str n) :xsd/integer))})))
