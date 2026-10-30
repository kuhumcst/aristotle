(ns dk.cst.theophrastus.endpoint.pedestal
  "Pedestal interceptors for a SPARQL endpoint, one for each step of
  endpoint/handle, so that an app can replace any of them with its own.

  Each interceptor keeps the operation in the request, under
  :dk.cst.theophrastus.endpoint/operation, and negotiate keeps its decision
  where conneg-clj does, under :dk.simongray.conneg/decision. They take the
  options of endpoint/handle over endpoint/defaults, and throw at once when
  one is wrong, as endpoint/config does.

  The interceptors are maps, so the library doesn't depend on Pedestal. A
  route takes them as they are. Elsewhere, pass each to
  io.pedestal.interceptor/interceptor first."
  (:require [dk.cst.theophrastus.endpoint :as endpoint]
            [dk.simongray.conneg :as conneg]))

(defn operation
  "An interceptor that reads the operation of the request, as
  endpoint/operation does, with the `opts`."
  [opts]
  (let [config (endpoint/config opts)]
    {:name  ::operation
     :enter (fn [{:keys [request] :as ctx}]
              (assoc-in ctx [:request ::endpoint/operation]
                        (endpoint/operation request config)))}))

(defn prepare
  "An interceptor that prepares the operation of the request, as
  endpoint/prepare does, with the `opts`."
  [opts]
  (let [config (endpoint/config opts)]
    {:name  ::prepare
     :enter (fn [{:keys [request] :as ctx}]
              (let [prepare-opts (endpoint/prepare-opts config request)]
                (update-in ctx [:request ::endpoint/operation]
                           endpoint/prepare prepare-opts)))}))

(defn negotiate
  "An interceptor that negotiates the media type of the result of the
  operation, with the `opts`. It answers 406 when no media type is
  acceptable, which ends the chain."
  [opts]
  (let [config (endpoint/config opts)]
    {:name  ::negotiate
     :enter (fn [{:keys [request] :as ctx}]
              (let [op (::endpoint/operation request)]
                (if (:error op)
                  ctx
                  (let [negotiation (endpoint/negotiation op config)
                        decision    (conneg/negotiate negotiation request)
                        request     (assoc request ::conneg/decision decision)]
                    (cond-> (assoc ctx :request request)
                      (:not-acceptable decision)
                      (assoc :response (endpoint/respond request op decision
                                                         config)))))))}))

(defn run
  "An interceptor that runs the query of the operation, as endpoint/run
  does, with the `opts`."
  [opts]
  (let [config (endpoint/config opts)]
    {:name  ::run
     :enter (fn [{:keys [request] :as ctx}]
              (let [op       (::endpoint/operation request)
                    db       (endpoint/request-db config request)
                    run-opts (endpoint/run-opts config op)]
                (assoc-in ctx [:request ::endpoint/operation]
                          (endpoint/run op db run-opts))))}))

(defn respond
  "An interceptor that sets the response, as endpoint/respond does, with
  the `opts`."
  [opts]
  (let [config (endpoint/config opts)]
    {:name  ::respond
     :enter (fn [{:keys [request] :as ctx}]
              (assoc ctx :response
                (endpoint/respond request
                                  (::endpoint/operation request)
                                  (::conneg/decision request)
                                  config)))}))

(defn interceptors
  "The interceptors of a SPARQL endpoint, in order, with the `opts`."
  [opts]
  [(operation opts) (prepare opts) (negotiate opts) (run opts)
   (respond opts)])
