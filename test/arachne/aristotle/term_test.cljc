(ns arachne.aristotle.term-test
  "RDF terms as values, in Clojure and ClojureScript."
  (:require #?(:clj [clojure.string :as str])
            [clojure.test :refer [deftest is]]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [arachne.aristotle.term :as term]))

(def readers
  "The readers of the reader tags, for reading EDN."
  {'rdf/langstring term/read-langstring
   'rdf/literal    term/read-literal})

(def xsd
  "The XSD namespace, for datatype IRIs."
  "http://www.w3.org/2001/XMLSchema#")

(deftest iri-string?
  (is (term/iri-string? "<https://cst.dk>"))
  (is (term/iri-string? "<urn:isbn:123>"))
  (is (term/iri-string? "<mailto:a@b.dk>"))
  (is (term/iri-string? "<https://da.wikipedia.org/wiki/Æble>"))
  (is (term/iri-string? "<xsl:template>")
      "text with a word and a colon passes, and needs term/literal")
  (is (not (term/iri-string? "<b>bold</b>")))
  (is (not (term/iri-string? "<3")))
  (is (not (term/iri-string? "<http://ex/Social relation>")))
  (is (not (term/iri-string? "<hello>")) "a relative IRI isn't one in RDF")
  (is (not (term/iri-string? "<>")))
  (is (not (term/iri-string? "<æble:x>")) "a scheme is ASCII")
  (is (not (term/iri-string? "<http://ex/a\\b>")))
  (is (not (term/iri-string? "https://cst.dk")))
  (is (not (term/iri-string? :rdfs/label))))

(deftest blank?
  (is (term/blank? '_b1))
  (is (term/blank? '_))
  (is (not (term/blank? '?x)))
  (is (not (term/blank? 'a/_b1)))
  (is (not (term/blank? "_b1"))))

(deftest iri
  (let [prefixes (assoc term/default-prefixes :dn "<https://wordnet.dk/dannet/data/>")]
    (is (= "https://wordnet.dk/dannet/data/synset-1"
           (term/iri :dn/synset-1 prefixes)))
    (is (= (str xsd "string") (term/iri :xsd/string term/default-prefixes)))
    (is (nil? (term/iri :dn/synset-1 term/default-prefixes))
        "a keyword whose prefix isn't known")
    (is (nil? (term/iri :label prefixes)))
    (is (= "https://cst.dk" (term/iri "<https://cst.dk>" prefixes)))
    (is (nil? (term/iri "<b>bold</b>" prefixes)))
    (is (nil? (term/iri "hund" prefixes)))
    #?@(:clj  [(is (= "https://cst.dk"
                      (term/iri (java.net.URI. "https://cst.dk") prefixes)))
               (is (nil? (term/iri (java.net.URI. "hello") prefixes))
                   "a relative URI isn't an IRI in RDF")
               (is (nil? (term/iri (.toURL (java.net.URI. "https://cst.dk"))
                                   prefixes))
                   "a URL changes some IRIs")]
        :cljs [(is (nil? (term/iri (js/URL. "https://cst.dk") prefixes))
                   "a URL changes some IRIs")])
    (is (nil? (term/iri 1 prefixes)))
    (is (nil? (term/iri nil prefixes)))))

(deftest langstring
  (let [x (term/->LangStr "hund" "da")]
    (is (= "hund" (str x)))
    (is (= "da" (term/lang x)))
    (is (= x (term/->LangStr "hund" "da")))
    (is (= (hash x) (hash (term/->LangStr "hund" "da"))))
    (is (= 1 (count (set [x (term/->LangStr "hund" "da")]))))
    (is (not= x (term/->LangStr "hund" "nb")))
    (is (not= x (term/->LangStr "Hund" "da"))
        "the lexical form is case-sensitive")
    (is (not= x "hund"))
    (is (nil? (term/iri x term/default-prefixes)))
    (is (= "#rdf/langstring [\"hund\" \"da\"]" (pr-str x)))
    (is (= x (edn/read-string {:readers readers} (pr-str x))))
    (let [quoted (term/->LangStr "\"hund\"\n" "da")]
      (is (= quoted (edn/read-string {:readers readers} (pr-str quoted)))))))

(deftest langstring-case
  (let [x (term/->LangStr "colour" "en-gb")
        y (term/->LangStr "colour" "en-GB")]
    (is (= x y) "the language tag is case-insensitive, as in RDF 1.2")
    (is (= (hash x) (hash y)))
    (is (= 1 (count (set [x y]))))
    (is (= "en-gb" (term/lang x)) "the tag stays as written")
    (is (= "#rdf/langstring [\"colour\" \"en-gb\"]" (pr-str x))))
  #?(:clj
     (let [default (java.util.Locale/getDefault)]
       (try
         (java.util.Locale/setDefault (java.util.Locale/forLanguageTag "tr"))
         (is (= (term/->LangStr "ciao" "IT") (term/->LangStr "ciao" "it"))
             "a Turkish default locale doesn't turn IT into ıt")
         (finally
           (java.util.Locale/setDefault default))))))

(deftest literal
  (let [x (term/literal "1.50" :xsd/decimal)]
    (is (= "1.50" (str x)))
    (is (= (str "<" xsd "decimal>") (term/datatype x)))
    (is (= x (term/literal "1.50" (str "<" xsd "decimal>"))))
    (is (= x (term/->Literal "1.50" (str "<" xsd "decimal>"))))
    (is (= (hash x) (hash (term/literal "1.50" :xsd/decimal))))
    (is (not= x (term/literal "1.5" :xsd/decimal))
        "a Literal is a term, so 1.50 and 1.5 differ")
    (is (= (str "#rdf/literal [\"1.50\" \"<" xsd "decimal>\"]") (pr-str x)))
    (is (= x (edn/read-string {:readers readers} (pr-str x)))))
  (is (= (str "<" xsd "string>") (term/datatype (term/literal "<urn:isbn:123>"))))
  (is (= "<https://cst.dk/type>"
         (term/datatype (term/literal "x" :cst/type {:cst "<https://cst.dk/>"}))))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (term/literal "x" :cst/type))))

#?(:clj
   (deftest reader-tags
     (is (= (term/->LangStr "hund" "da")
            (read-string "#rdf/langstring [\"hund\" \"da\"]")))
     (is (= (term/literal "1.50" :xsd/decimal)
            (read-string (str "#rdf/literal [\"1.50\" \"<" xsd "decimal>\"]"))))))

(deftest value
  (is (= "hund" (term/value "hund")) "a value that isn't a Literal")
  (is (= 5 (term/value 5)))
  (is (= :rdfs/label (term/value :rdfs/label)))
  (let [x (term/literal "x" "<http://example.com/datatype>")]
    (is (= x (term/value x)) "a datatype that value doesn't know"))
  #?(:cljs
     (let [x (term/literal "1.50" :xsd/decimal)]
       (is (= x (term/value x)) "ClojureScript has no type that holds it")))
  #?(:clj
     (let [value-of #(term/value (term/literal %1 %2))]
       (is (= "1.50" (str (value-of "1.50" :xsd/decimal))))
       (is (instance? BigDecimal (value-of " 1.50 " :xsd/decimal))
           "XSD collapses white space before reading")
       (is (= (java.time.OffsetDateTime/parse "2024-01-02T03:04:05+02:00")
              (value-of "2024-01-02T03:04:05+02:00" :xsd/dateTime)))
       (is (= (java.time.LocalDateTime/parse "2024-01-02T03:04:05")
              (value-of "2024-01-02T03:04:05" :xsd/dateTime)))
       (is (= (java.time.OffsetDateTime/parse "2024-01-02T03:04:05Z")
              (value-of "2024-01-02T03:04:05Z" :xsd/dateTimeStamp)))
       (is (= (java.time.LocalDate/of 2024 1 2) (value-of "2024-01-02" :xsd/date)))
       (is (= (java.time.LocalDate/of -44 3 15) (value-of "-0044-03-15" :xsd/date)))
       (is (= (java.time.LocalTime/of 3 4 5 500000000) (value-of "03:04:05.5" :xsd/time)))
       (is (= (java.time.OffsetTime/parse "03:04:05+01:00")
              (value-of "03:04:05+01:00" :xsd/time)))
       (is (= (java.time.Year/of 2024) (value-of "2024" :xsd/gYear)))
       (is (= (java.time.YearMonth/of 2024 5) (value-of "2024-05" :xsd/gYearMonth)))
       (is (= (java.time.MonthDay/of 2 29) (value-of "--02-29" :xsd/gMonthDay)))
       (is (= (java.time.Duration/ofHours 26) (value-of "P1DT2H" :xsd/dayTimeDuration)))
       (is (= (java.time.Period/of -1 -2 0) (value-of "-P1Y2M" :xsd/yearMonthDuration)))
       (doseq [[s dt why]
               [["1e5" :xsd/decimal "not a valid decimal"]
                ["2024-01-02T03:04Z" :xsd/dateTime "no seconds"]
                ["2024-02-30T03:04:05Z" :xsd/dateTime "no such day"]
                ["2024-01-02T03:04:05" :xsd/dateTimeStamp "no time zone"]
                ["2024-01-02T24:00:00Z" :xsd/dateTime
                 "valid, but java.time refuses 24:00:00"]
                ["2024-01-02T03:04:05.1234567891Z" :xsd/dateTime
                 "more digits than java.time holds"]
                ["12024-01-02T03:04:05Z" :xsd/dateTime
                 "valid, but java.time wants a plus sign"]
                ["2024-01-02Z" :xsd/date
                 "no java.time type has a date with a time zone"]
                ["P1Y2M3D" :xsd/duration
                 "no java.time type has years and days"]]]
         (let [x (term/literal s dt)]
           (is (= x (term/value x)) why)))
       (try
         (defmethod term/value "<http://example.com/upper>"
           [x]
           (str/upper-case (str x)))
         (is (= "HUND" (value-of "hund" "<http://example.com/upper>"))
             "a method for a datatype of your own")
         (finally
           (remove-method term/value "<http://example.com/upper>"))))))

(deftest native-value
  (let [value-of #(term/value (term/literal %1 %2))]
    (is (= true (value-of "true" :xsd/boolean)))
    (is (= true (value-of "1" :xsd/boolean)))
    (is (= false (value-of "false" :xsd/boolean)) "false isn't a missing value")
    (is (= false (value-of "0" :xsd/boolean)))
    (is (= 5 (value-of "05" :xsd/integer)))
    (is (= 5 (value-of " +5 " :xsd/long)))
    (is (= -128 (value-of "-128" :xsd/byte)))
    (is (= 1.5 (value-of "1.5e0" :xsd/double)))
    (is (= 0.5 (value-of ".5" :xsd/double)))
    (is (= ##Inf (value-of "+INF" :xsd/double)) "XSD 1.1 allows +INF")
    (is (= ##-Inf (value-of "-INF" :xsd/float)))
    (is (NaN? (value-of "NaN" :xsd/double)))
    #?(:clj (is (instance? Long (value-of "5" :xsd/unsignedByte))))
    #?(:clj (is (instance? Float (value-of "1.5" :xsd/float))))
    #?(:clj  (is (= 18446744073709551615N
                    (value-of "18446744073709551615" :xsd/unsignedLong)))
       :cljs (let [x (term/literal "9007199254740993" :xsd/integer)]
               (is (= x (term/value x))
                   "past the largest safe integer in JavaScript")))
    (doseq [[s dt] [["yes" :xsd/boolean]
                    ["5.0" :xsd/integer]
                    ["128" :xsd/byte]
                    ["-1" :xsd/nonNegativeInteger]
                    ["0" :xsd/positiveInteger]
                    ["18446744073709551616" :xsd/unsignedLong]
                    ["1.5.5" :xsd/double]
                    ["Infinity" :xsd/double]]]
      (let [x (term/literal s dt)]
        (is (= x (term/value x)) (str s " isn't a valid " dt)))))
  (doseq [x [5 -5 true false 1.5 ##Inf ##-Inf
             #?@(:clj [(float 1.5) 18446744073709551615N])]]
    (is (= x (term/value (term/literal x))) (str "a round trip of " x))))

(deftest literal-of-value
  (is (= ["5" (str "<" xsd "integer>")]
         ((juxt str term/datatype) (term/literal 5))))
  (is (= ["true" (str "<" xsd "boolean>")]
         ((juxt str term/datatype) (term/literal true))))
  (is (= ["1.5" (str "<" xsd "double>")]
         ((juxt str term/datatype) (term/literal 1.5))))
  (is (= ["-INF" (str "<" xsd "double>")]
         ((juxt str term/datatype) (term/literal ##-Inf))))
  #?(:clj  (is (= ["1.5" (str "<" xsd "float>")]
                  ((juxt str term/datatype) (term/literal (float 1.5)))))
     :cljs (is (= (str "<" xsd "integer>") (term/datatype (term/literal 5.0)))
               "JavaScript can't tell 5.0 from 5"))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (term/literal :rdfs/label))
      "an IRI isn't a literal")
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) (term/literal nil)))
  (let [x (term/literal "x" "<http://example.com/datatype>")]
    (is (identical? x (term/literal x))))
  #?(:clj
     (let [lexical #(str (term/literal %))
           dt      #(term/datatype (term/literal %))]
       (is (= "1.50" (lexical 1.50M)))
       (is (= (str "<" xsd "decimal>") (dt 1.50M)))
       (is (= "2024-01-02T03:04:00" (lexical (java.time.LocalDateTime/of 2024 1 2 3 4)))
           "seconds even when they are zero")
       (is (= "2024-01-02T03:04:05+02:00"
              (lexical (java.time.OffsetDateTime/parse "2024-01-02T03:04:05+02:00"))))
       (is (= "1970-01-01T00:00:00Z" (lexical (java.util.Date. 0))))
       (is (= (str "<" xsd "dateTime>") (dt (java.util.Date. 0))))
       (is (= "1970-01-01T00:00:00.000000005Z"
              (lexical (java.time.Instant/ofEpochSecond 0 5))))
       (is (= "0044" (lexical (java.time.Year/of 44))))
       (is (= "-0044" (lexical (java.time.Year/of -44))))
       (is (= "12024-01-02" (lexical (java.time.LocalDate/of 12024 1 2)))
           "no plus sign before a year past 9999")
       (is (= "-PT0.5S" (lexical (java.time.Duration/ofMillis -500))))
       (is (= "P0M" (lexical java.time.Period/ZERO)))
       (is (= "-P1Y2M" (lexical (java.time.Period/of -1 -2 0))))
       (is (= (str "<" xsd "duration>") (dt (java.time.Period/ofDays 3))))
       (is (thrown? clojure.lang.ExceptionInfo
                    (term/literal (java.time.Period/of 1 -2 0)))
           "XSD has no duration with parts of both signs")
       (doseq [x [1.50M
                  (java.time.OffsetDateTime/parse "2024-01-02T03:04:05.5+02:00")
                  (java.time.LocalDateTime/of 2024 1 2 3 4)
                  (java.time.LocalDate/of 2024 1 2)
                  (java.time.LocalTime/of 3 4 5)
                  (java.time.OffsetTime/parse "03:04:05Z")
                  (java.time.Year/of 2024)
                  (java.time.YearMonth/of 2024 5)
                  (java.time.MonthDay/of 2 29)
                  (java.time.Duration/ofSeconds -90)
                  (java.time.Period/of 1 2 0)]]
         (is (= x (term/value (term/literal x))) (str "a round trip of " x))))))
