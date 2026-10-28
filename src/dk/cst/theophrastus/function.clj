(ns dk.cst.theophrastus.function
  "SPARQL functions written in Clojure, registered with Jena's ARQ, so that
  any query can call them by their IRI.

  See https://jena.apache.org/documentation/query/writing_functions.html"
  (:require [dk.cst.theophrastus.convert :as convert]
            [dk.cst.theophrastus.term :as term])
  (:import [org.apache.jena.graph Node]
           [org.apache.jena.sparql.engine.binding Binding]
           [org.apache.jena.sparql.expr Expr ExprEvalException ExprList
            NodeValue]
           [org.apache.jena.sparql.function Function FunctionEnv
            FunctionFactory FunctionRegistry]))

(defn- ->function
  "The ARQ Function of the Clojure function `f`, with values under
  `prefixes`."
  ^Function [f prefixes]
  (reify Function
    (build [_ _uri _args _context])
    (exec [_ binding args _uri env]
      (let [value #(-> ^Expr %
                       (.eval ^Binding binding ^FunctionEnv env)
                       (.asNode)
                       (convert/data prefixes))
            r     (apply f (map value (.getList ^ExprList args)))]
        (if (nil? r)
          (throw (ExprEvalException. "No value"))
          (NodeValue/makeNode ^Node (convert/node r prefixes)))))))

(defn factory
  "The ARQ FunctionFactory of the Clojure function `f`, whose arguments and
  result are values under `prefixes`, default-prefixes unless given.

  A nil from `f` leaves the result unbound, as an error in SPARQL does. An
  exception from `f` fails the query."
  ([f]
   (factory f term/default-prefixes))
  ([f prefixes]
   (let [function (->function f prefixes)]
     (reify FunctionFactory
       (create [_ _uri]
         function)))))

(defn register!
  "Register the Clojure function `f` with ARQ under the IRI `iri`, so that
  any query can call it, and return `iri`. The IRI is a keyword under
  `prefixes`, default-prefixes unless given, or a string in angle brackets.
  The arguments and result of `f` are values, as for factory."
  ([iri f]
   (register! iri f term/default-prefixes))
  ([iri f prefixes]
   (if-let [full-iri (term/iri iri prefixes)]
     (do
       (.put (FunctionRegistry/get) ^String full-iri
             ^FunctionFactory (factory f prefixes))
       iri)
     (throw (ex-info (str "Not an IRI: " (pr-str iri)) {:iri iri})))))
