(ns dk.cst.theophrastus.endpoint-test
  "A read-only SPARQL endpoint: the query tests of the W3C's protocol test
  suite, the policy of prepare, the formats of write, the service
  description, and the Ring handler and Pedestal interceptors."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.endpoint :as endpoint]
            [dk.cst.theophrastus.endpoint.pedestal :as endpoint.pedestal]
            [dk.cst.theophrastus.endpoint.ring :as endpoint.ring]
            [dk.cst.theophrastus.term :as term]
            [dk.simongray.whatwg-url.params :as params])
  (:import [java.io ByteArrayInputStream]
           [java.util.concurrent SynchronousQueue ThreadPoolExecutor
            TimeUnit]))

(def prefixes
  "The default prefixes and one for the examples."
  (assoc term/default-prefixes :ex "<http://example.com/>"))

(defn- data
  "The IRI of the data file `n` of the W3C's protocol tests."
  [n]
  (str "http://kasei.us/2009/09/sparql/data/data" n ".rdf"))

(defn test-db
  "A db with a label for :ex/a and :ex/b in its default graph, and a named
  graph for each data file of the W3C's protocol tests, in which the file
  is a foaf:Document, as it says of itself."
  []
  (let [db (theo/db {:prefixes prefixes})]
    (theo/transact! db [[:ex/a :rdfs/label "A"]
                        [:ex/b :rdfs/label "B"]])
    (doseq [n (range 4)
            :let [iri (str "<" (data n) ">")]]
      (theo/transact! (theo/named-graph db iri)
                      [[iri :rdf/type "<http://xmlns.com/foaf/0.1/Document>"]]))
    db))

(defn- get-request
  "A GET with the parameter `pairs` and the `headers`."
  ([pairs]
   (get-request pairs {}))
  ([pairs headers]
   {:request-method :get
    :headers        headers
    :query-string   (params/serialize pairs)}))

(defn- post-request
  "A POST of the `body` of the `content-type`, with the parameter `pairs`
  in its URL."
  [content-type pairs body]
  {:request-method :post
   :headers        (cond-> {} content-type (assoc "content-type" content-type))
   :query-string   (params/serialize pairs)
   :body           body})

(defn- form-request
  "A POST of a form of the `form-pairs`, with the `url-pairs` in its URL."
  [url-pairs form-pairs]
  (post-request "application/x-www-form-urlencoded" url-pairs
                (params/serialize form-pairs)))

(def ask-1-2
  (str "ASK { <" (data 1) "> a ?type . <" (data 2) "> a ?type . }"))

(def ask-graphs
  (str "ASK { GRAPH ?g1 { <" (data 1) "> a ?type } "
       "GRAPH ?g2 { <" (data 2) "> a ?type } }"))

(def named-1-2
  [["named-graph-uri" (data 1)] ["named-graph-uri" (data 2)]])

;; https://w3c.github.io/rdf-tests/sparql/sparql11/protocol/, the query
;; tests of manifest.ttl, with 2 for 2xx and 3xx and 4 for 4xx
(def w3c-tests
  "The query tests of the W3C's SPARQL 1.1 protocol test suite: a request,
  the class of status it expects, and the result: true for an ASK, or
  :tabular or :rdf for the kind of format."
  [["query_post_form"
    (form-request [["default-graph-uri" (data 0)]] [["query" "ASK {}"]])
    2 true]
   ["query_dataset_default_graphs_get"
    (get-request [["query" ask-1-2]
                  ["default-graph-uri" (data 1)]
                  ["default-graph-uri" (data 2)]])
    2 true]
   ["query_dataset_default_graphs_post"
    (post-request "application/sparql-query"
                  [["default-graph-uri" (data 1)]
                   ["default-graph-uri" (data 2)]]
                  ask-1-2)
    2 true]
   ["query_dataset_named_graphs_post"
    (post-request "application/sparql-query" named-1-2 ask-graphs)
    2 true]
   ["query_dataset_named_graphs_get"
    (get-request (into [["query" ask-graphs]] named-1-2))
    2 true]
   ["query_dataset_full"
    (post-request "application/sparql-query"
                  (into [["default-graph-uri" (data 3)]] named-1-2)
                  (str "ASK { <" (data 3) "> a ?type "
                       "GRAPH ?g1 { <" (data 1) "> a ?type } "
                       "GRAPH ?g2 { <" (data 2) "> a ?type } }"))
    2 true]
   ["query_multiple_dataset"
    (post-request "application/sparql-query" named-1-2
                  (str/replace ask-graphs "ASK {"
                               (str "ASK FROM <" (data 3) "> {")))
    2 true]
   ["query_get"
    (get-request [["query" "ASK {}"] ["default-graph-uri" (data 0)]])
    2 true]
   ["query_post_direct"
    (post-request "application/sparql-query" [] "ASK {}")
    2 true]
   ["query_content_type_select"
    (post-request "application/sparql-query" [["default-graph-uri" (data 0)]]
                  "SELECT (1 AS ?value) {}")
    2 :tabular]
   ["query_content_type_ask"
    (post-request "application/sparql-query" [["default-graph-uri" (data 0)]]
                  "ASK {}")
    2 true]
   ["query_content_type_describe"
    (post-request "application/sparql-query" [["default-graph-uri" (data 0)]]
                  "DESCRIBE <http://example.org/>")
    2 :rdf]
   ["query_content_type_construct"
    (post-request "application/sparql-query" [["default-graph-uri" (data 0)]]
                  "CONSTRUCT { <s> <p> 1 } WHERE {}")
    2 :rdf]
   ["bad_query_method"
    (assoc (form-request [["query" "ASK {}"] ["default-graph-uri" (data 0)]]
                         [])
      :request-method :put)
    4]
   ["bad_multiple_queries"
    (get-request [["query" "ASK {}"] ["query" "SELECT * {}"]])
    4]
   ["bad_query_wrong_media_type"
    (post-request "text/plain" [] "ASK {}")
    4]
   ["bad_query_missing_form_type"
    (post-request nil [] "query=ASK%20%7B%7D")
    4]
   ["bad_query_missing_direct_type"
    (post-request nil [] "ASK {}")
    4]
   ["bad_query_non_utf8"
    (post-request "application/sparql-query; charset=UTF-16" []
                  (ByteArrayInputStream. (.getBytes "ASK {}" "UTF-16")))
    4]
   ["bad_query_syntax"
    (get-request [["query" "ASK {"]])
    4]])

(def config
  "The endpoint of the test db."
  {:db (test-db) :endpoint "http://example.com/sparql"})

(defn- content-type
  "The media type of the `response`, without parameters."
  [response]
  (some-> (get-in response [:headers "Content-Type"])
          (str/split #";")
          (first)))

(defn- text
  "The `body` of a response as text: an error's is text already, and a
  result's is bytes."
  [body]
  (if (bytes? body) (String. ^bytes body "UTF-8") body))

(deftest w3c-protocol
  (doseq [[test-name request status result] w3c-tests
          :let [response (endpoint/handle request config)]]
    (testing test-name
      (is (= status (quot (:status response) 100)))
      (cond
        (true? result)
        (is (re-find #"\"boolean\"\s*:\s*true" (text (:body response))))

        (= :tabular result)
        (is (some #{(content-type response)} (:select endpoint/media-types)))

        (= :rdf result)
        (is (some #{(content-type response)}
                  (:construct endpoint/media-types)))))))

(defn- error-type
  "The type of the error of the operation of the `request`, prepared with
  the `opts`."
  [request opts]
  (-> (endpoint/operation request opts)
      (endpoint/prepare (merge {:prefixes prefixes} opts))
      (get-in [:error :type])))

(deftest operation
  (is (= {:query             "ASK {}"
          :default-graph-uri []
          :named-graph-uri   []
          :params            {:timeout "5" :x ["1" "2"]}}
         (endpoint/operation
           (get-request [["query" "ASK {}"] ["timeout" "5"] ["x" "1"]
                         ["x" "2"]])))
      "parameters beside the protocol's")
  (is (= "ASK {}"
         (:query (endpoint/operation
                   (assoc (form-request [] [])
                     :form-params {"query" "ASK {}"}))))
      "a form that middleware has read")
  (is (= "1.2"
         (:version (endpoint/operation
                     (post-request "application/sparql-query; version=1.2" []
                                   "ASK {}"))))
      "the version of the media type")
  (is (= :method-not-allowed
         (error-type {:request-method :delete :headers {}} {})))
  (is (= :no-query
         (error-type (form-request [] [["timeout" "5"]]) {})))
  (is (= :empty-query (error-type (get-request [["query" " "]]) {})))
  (let [body (str "ASK {} # " (apply str (repeat 100 "x")))]
    (is (= :too-large
           (error-type (post-request "application/sparql-query" []
                                     (ByteArrayInputStream. (.getBytes body)))
                       {:max-bytes 100}))
        "a body over :max-bytes")
    (is (= :too-large
           (error-type (assoc-in (post-request "application/sparql-query" []
                                               "ASK {}")
                                 [:headers "content-length"] "1000")
                       {:max-bytes 100}))
        "a Content-Length over :max-bytes")
    (is (nil? (error-type (post-request "application/sparql-query" [] body)
                          {:max-bytes 1000}))))
  (is (= :not-utf-8
         (error-type (post-request "application/sparql-query" []
                                   (ByteArrayInputStream.
                                     (byte-array [65 83 75 32 123 125 -1])))
                     {}))
      "a body that isn't UTF-8"))

(deftest prepare
  (let [select (get-request [["query" "SELECT * { ?s ?p ?o }"]])
        sparql #(:sparql (endpoint/prepare (endpoint/operation %1) %2))]
    (is (= 10 (.getLimit (sparql select {:limit 10}))) "a LIMIT")
    (is (= 5 (.getLimit (sparql (get-request [["query" "SELECT * {} LIMIT 5"]])
                                {:limit 10})))
        "a lower LIMIT stays")
    (is (= 10 (.getLimit (sparql (get-request
                                   [["query" "SELECT * {} LIMIT 50"]])
                                 {:limit 10})))
        "a higher LIMIT doesn't")
    (is (= 20 (.getOffset (sparql select {:offset 20}))))
    (is (.isDistinct (sparql select {:distinct? true}))))
  (is (= :form-not-allowed
         (error-type (get-request [["query" "ASK {}"]]) {:forms #{:select}})))
  (is (= :query-too-long
         (error-type (get-request [["query" "ASK {}"]]) {:max-length 5})))
  (doseq [query ["SELECT * { { SELECT * { SERVICE <http://x/> {} } } }"
                 "ASK { FILTER EXISTS { SERVICE <http://x/> {} } }"
                 "SELECT (EXISTS { SERVICE <http://x/> {} } AS ?e) {}"]]
    (is (= :service-not-allowed
           (error-type (get-request [["query" query]]) {}))
        query))
  (is (nil? (error-type (get-request [["query" "ASK { SERVICE <x:> {} }"]])
                        {:service? true})))
  (doseq [query [(str "PREFIX j: <java:org.apache.jena.sparql.function."
                      "library.> SELECT ?x { BIND(j:sqrt(4) AS ?x) }")
                 "SELECT * { ?x <java:clojure.data__init> ?y }"
                 "ASK { FILTER EXISTS { BIND(<java:x.Y>(1) AS ?z) } }"]]
    (is (= :java-not-allowed (error-type (get-request [["query" query]]) {}))
        query))
  (is (nil? (error-type (get-request [["query" "ASK { FILTER(<java:x.Y>()) }"]])
                        {:java? true})))
  (is (= :dataset-not-allowed
         (error-type (get-request [["query" "ASK FROM <http://x> {}"]])
                     {:dataset? false})))
  (is (= :update-not-allowed
         (error-type (get-request [["query" "CLEAR DEFAULT"]]) {})))
  (is (= :parse-error (error-type (get-request [["query" "ASK {"]]) {})))
  (is (= :version-not-supported
         (error-type (get-request [["query" "ASK {}"] ["version" "1.3"]])
                     {}))))

(deftest run
  (let [db  (test-db)
        run #(-> (endpoint/operation (get-request [["query" %1]]))
                 (endpoint/prepare (merge {:prefixes prefixes} %2))
                 (endpoint/run db %2))]
    (is (= '[?l] (:vars (run "SELECT ?l { ?s rdfs:label ?l }" {}))))
    (is (true? (:result (run "ASK { ex:a ?p ?o }" {}))))
    (is (= 2 (.size (:result (run "CONSTRUCT WHERE { ?s rdfs:label ?l }" {})))))
    (is (= {:status  500
            :type    :execution-failed
            :message "The query failed to run."}
           (-> (endpoint/operation
                 (get-request [["query" "ASK { SERVICE <http://x/> {} }"]]))
               (endpoint/prepare {:prefixes prefixes :service? true})
               (endpoint/run db {})
               :error
               (dissoc :details)))
        "SERVICE stays off in run, with Jena's message in the :details")
    (let [executor (ThreadPoolExecutor. 1 1 0 TimeUnit/MILLISECONDS
                                        (SynchronousQueue.))
          blocker  (promise)]
      (try
        (.submit executor ^Callable #(deref blocker))
        (is (= {:status 503 :type :timeout}
               (-> (run "ASK {}" {:executor executor :timeout 1000})
                   :error
                   (select-keys [:status :type])))
            "a busy executor")
        (finally
          (deliver blocker nil)
          (.shutdown executor))))))

(defn- chain-db
  "A db where each of `n` resources links to the next, in a list of `n`
  numbers: a graph that a writer that recurses into each resource can't
  write."
  [n]
  (let [db (theo/db {:prefixes prefixes})]
    (theo/transact! db (for [i (range n)]
                         [(keyword "ex" (str "r" i)) :ex/next
                          (keyword "ex" (str "r" (inc i)))]))
    (theo/transact! db (into [[:ex/list :ex/items (symbol "_b0")]]
                             (mapcat (fn [i]
                                       [[(symbol (str "_b" i)) :rdf/first i]
                                        [(symbol (str "_b" i)) :rdf/rest
                                         (if (= i (dec n))
                                           :rdf/nil
                                           (symbol (str "_b" (inc i))))]]))
                             (range n)))
    db))

(deftest write
  (let [db (test-db)
        op #(-> (endpoint/operation (get-request [["query" %]]))
                (endpoint/prepare {:prefixes prefixes})
                (endpoint/run db {}))]
    (doseq [[query form] [["SELECT ?l { ?s rdfs:label ?l }" :select]
                          ["ASK { ex:a ?p ?o }" :ask]
                          ["CONSTRUCT WHERE { ?s rdfs:label ?l }" :construct]]
            media-type (get endpoint/media-types form)]
      (is (not (str/blank? (text (endpoint/write (op query) media-type))))
          (str form " in " media-type)))
    (let [select (op "SELECT ?l { ?s rdfs:label ?l }")]
      (is (= (text (endpoint/write select "text/csv"))
             (text (endpoint/write select "text/csv")))
          "a result can be written again")
      (is (nil? (endpoint/write select "text/turtle")))))
  (let [db    (chain-db 20000)
        graph (-> (endpoint/operation
                    (get-request [["query" "CONSTRUCT WHERE { ?s ?p ?o }"]]))
                  (endpoint/prepare {:prefixes prefixes})
                  (endpoint/run db {}))]
    (doseq [media-type (:construct endpoint/media-types)]
      (is (pos? (alength (endpoint/write graph media-type)))
          (str "a long chain and list in " media-type)))))

(deftest handle
  (let [response (endpoint/handle (get-request []) config)]
    (is (= "text/turtle" (content-type response)) "the service description")
    (is (str/includes? (text (:body response)) "<http://example.com/sparql>")))
  (is (= 400 (:status (endpoint/handle (get-request [])
                                       (assoc config
                                         :service-description false)))))
  (is (= 400 (:status (endpoint/handle (assoc (get-request [])
                                         :headers {"host" "a b"}
                                         :uri "/sparql")
                                       (dissoc config :endpoint))))
      "a URL that isn't an IRI")
  (is (= 406 (:status (endpoint/handle
                        (get-request [["query" "ASK {}"]]
                                     {"accept" "text/csv"})
                        config))))
  (is (= "text/csv"
         (content-type (endpoint/handle (get-request [["query" "SELECT * {}"]
                                                      ["format" "csv"]])
                                        config))))
  (is (nil? (:body (endpoint/handle (assoc (get-request [["query" "ASK {}"]])
                                      :request-method :head)
                                    config))))
  (doseq [request [(get-request [["query" "ASK {}"]])
                   (get-request [["query" "ASK {"]])]]
    (is (= endpoint/safety-headers
           (select-keys (:headers (endpoint/handle request config))
                        (keys endpoint/safety-headers)))
        "the safety headers"))
  (is (= 1000 (:timeout (endpoint/run-opts {:timeout 5000}
                                           {:params {:timeout "1"}}))))
  (is (= 5000 (:timeout (endpoint/run-opts {:timeout 5000}
                                           {:params {:timeout "60"}})))
      "never more than the config's")
  (is (nil? (:timeout (endpoint/run-opts {:timeout nil}
                                          {:params {:timeout "Infinity"}})))
      "a timeout that isn't finite counts as none"))

;; named so as not to clash with the config above
(deftest checked-config
  (is (= (merge endpoint/defaults {:db 1}) (endpoint/config {:db 1})))
  (doseq [opts [[] {} {:db 1 :timeout -1} {:db 1 :forms #{:update}}
                {:db 1 :limit 1.5} {:db 1 :executor :pool}]]
    (is (thrown? clojure.lang.ExceptionInfo (endpoint/config opts))
        (pr-str opts))))

(defn- chain
  "The context after the enter functions of the `interceptors` on the
  `request`, stopping at a response, as Pedestal does."
  [interceptors request]
  (reduce (fn [ctx {:keys [enter]}]
            (if (:response ctx) (reduced ctx) (enter ctx)))
          {:request request}
          interceptors))

(defn- readable
  "The `response` with its body as text, so that responses compare."
  [response]
  (update response :body text))

(deftest adapters
  (doseq [request [(get-request [["query" "SELECT * { ?s ?p ?o }"]])
                   (get-request [["query" "ASK {}"]] {"accept" "text/csv"})
                   (get-request [["query" "ASK {"]])]
          :let [response (readable (endpoint/handle request config))
                async    (promise)]]
    ((endpoint.ring/handler config) request #(deliver async %)
                                    #(deliver async %))
    (is (= response (readable ((endpoint.ring/handler config) request))))
    (is (= response (readable @async)))
    (is (= response (readable (:response
                                (chain (endpoint.pedestal/interceptors config)
                                       request)))))))
