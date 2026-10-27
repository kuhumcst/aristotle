(ns arachne.aristotle.term-test
  "RDF terms as values, in Clojure and ClojureScript."
  (:require [clojure.test :refer [deftest is]]
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
