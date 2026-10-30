(ns dk.cst.theophrastus.endpoint.ring
  "A Ring handler for a SPARQL endpoint."
  (:require [dk.cst.theophrastus.endpoint :as endpoint]))

(defn handler
  "A Ring handler that answers SPARQL queries as endpoint/handle does, with
  the `opts` over endpoint/defaults. It throws at once when an option is
  wrong, as endpoint/config does.

  It works as a synchronous and an asynchronous handler, and runs the query
  on the thread that calls it either way. Handing it to the :executor
  instead would leave that thread waiting on a query in the same pool,
  which can deadlock a small pool."
  [opts]
  (let [config (endpoint/config opts)]
    (fn
      ([request]
       (endpoint/handle request config))
      ([request respond raise]
       (let [response (try
                        (endpoint/handle request config)
                        (catch Throwable t
                          (raise t)
                          nil))]
         (when response
           (respond response)))))))
