(ns dk.cst.theophrastus.function
  "SPARQL functions written in Clojure, which any query can call by their
  IRI once they're registered with Jena's ARQ.

  See https://jena.apache.org/documentation/query/writing_functions.html"
  (:require [dk.cst.theophrastus.convert :as convert]
            [dk.cst.theophrastus.term :as term])
  (:import [org.apache.jena.graph Node]
           [org.apache.jena.sparql.engine.binding Binding]
           [org.apache.jena.sparql.expr Expr ExprEvalException ExprList
            NodeValue]
           [org.apache.jena.sparql.function Function FunctionEnv
            FunctionFactory FunctionRegistry]
           [org.apache.jena.sparql.util Context]))

(defn- ->function
  "The ARQ Function of the Clojure function `f`. Its keywords use the
  prefixes of the query's db, or `prefixes` when the query has none."
  ^Function [f prefixes]
  (reify Function
    (build [_ _uri _args _context])
    (exec [_ binding args _uri env]
      (let [^Context ctx (.getContext ^FunctionEnv env)
            prefixes     (or (some-> ctx (.get convert/prefixes-symbol))
                             prefixes)
            value        #(-> ^Expr %
                              (.eval ^Binding binding ^FunctionEnv env)
                              (.asNode)
                              (convert/data prefixes))
            r            (apply f (map value (.getList ^ExprList args)))]
        (if (nil? r)
          (throw (ExprEvalException. "No value"))
          (NodeValue/makeNode ^Node (convert/node r prefixes)))))))

(defn factory
  "The ARQ FunctionFactory of the Clojure function `f`, which takes and
  returns values as theo/q returns them. Its keywords use the prefixes of
  the db that theo/q runs the query on, or `prefixes` for a query run
  another way (term/default-prefixes when none are given).

  When `f` returns nil, the result is unbound, as after an error in SPARQL.
  An exception from `f` fails the query."
  ([f]
   (factory f term/default-prefixes))
  ([f prefixes]
   (let [function (->function f prefixes)]
     (reify FunctionFactory
       (create [_ _uri]
         function)))))

(defn register!
  "Register the Clojure function `f` with ARQ as `iri`, so that any query
  can call it, and return `iri`. The IRI is a string in angle brackets or a
  keyword, with `prefixes` for keywords (term/default-prefixes when none
  are given). The function takes and returns values, as for factory."
  ([iri f]
   (register! iri f term/default-prefixes))
  ([iri f prefixes]
   (if-let [full-iri (term/iri iri prefixes)]
     (do
       (.put (FunctionRegistry/get) ^String full-iri
             ^FunctionFactory (factory f prefixes))
       iri)
     (throw (ex-info (str "Not an IRI: " (pr-str iri)) {:iri iri})))))
