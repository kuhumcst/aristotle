(ns dk.cst.theophrastus.endpoint
  "A read-only SPARQL endpoint, by the SPARQL 1.1 Protocol, in steps that
  each take and return data.

  - operation reads the query and its dataset from a Ring request.
  - prepare parses the query and checks it against a policy.
  - run runs it on a db.
  - write writes its result in a media type.

  Each step adds to the map that operation returns, and passes on a map
  that has an :error as it is, so the steps thread:

      (-> (endpoint/operation request)
          (endpoint/prepare {:prefixes prefixes :limit 100})
          (endpoint/run db {:timeout 30000}))

  handle takes a request through every step and returns its response. The
  ring and pedestal namespaces make a Ring handler and Pedestal
  interceptors of the steps. The options of every step are in defaults."
  (:require [clojure.string :as str]
            [dk.cst.theophrastus :as theo]
            [dk.cst.theophrastus.convert :as convert]
            [dk.cst.theophrastus.term :as term]
            [dk.cst.theophrastus.txn :as txn]
            [dk.simongray.conneg :as conneg]
            [dk.simongray.whatwg-mime :as mime]
            [dk.simongray.whatwg-url.params :as params])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.nio ByteBuffer]
           [java.nio.charset CharacterCodingException StandardCharsets]
           [java.util.concurrent ExecutorService TimeUnit TimeoutException]
           [org.apache.jena.graph Triple]
           [org.apache.jena.query ARQ Dataset Query QueryCancelledException
            QueryException QueryParseException ResultSet]
           [org.apache.jena.rdf.model Model ModelFactory]
           [org.apache.jena.riot Lang RDFFormat RDFLanguages RDFWriter
            RDFWriterRegistry WebContent]
           [org.apache.jena.riot.resultset ResultSetWriterRegistry]
           [org.apache.jena.sparql.algebra Algebra OpVisitorBase]
           [org.apache.jena.sparql.algebra.op OpBGP OpService]
           [org.apache.jena.sparql.algebra.walker Walker]
           [org.apache.jena.sparql.core DatasetDescription DatasetGraph
            DatasetGraphFactory DynamicDatasets]
           [org.apache.jena.sparql.exec QueryExec QueryExecBuilder]
           [org.apache.jena.sparql.expr E_Function ExprVisitorBase]
           [org.apache.jena.sparql.resultset ResultSetMem ResultsWriter]
           [org.apache.jena.sparql.util Symbol]))

(def all-forms
  "The forms of a SPARQL query."
  #{:select :ask :construct :describe})

;; SPARQL 1.1 Protocol, section 2.1.6: results come in the formats of the
;; SPARQL 1.1 Query Results documents, graphs in an RDF syntax
(def media-types
  "The media types that the result of each form of query can be written in,
  in order of preference, e.g. as the offers of content negotiation. Jena
  writes each of them. JSON-LD comes last, since its writer is by far the
  slowest."
  (let [results ["application/sparql-results+json"
                 "application/sparql-results+xml"
                 "text/csv"
                 "text/tab-separated-values"]
        graphs  ["text/turtle"
                 "application/n-triples"
                 "application/rdf+xml"
                 "application/ld+json"]]
    {:select    results
     :ask       (subvec results 0 2)
     :construct graphs
     :describe  graphs}))

(def aliases
  "The short names of the media types of each form of query, e.g. for
  ?format=csv. They are those of Fuseki."
  (let [results {"json" "application/sparql-results+json"
                 "xml"  "application/sparql-results+xml"
                 "csv"  "text/csv"
                 "tsv"  "text/tab-separated-values"}
        graphs  {"turtle"  "text/turtle"
                 "ttl"     "text/turtle"
                 "nt"      "application/n-triples"
                 "json-ld" "application/ld+json"
                 "json"    "application/ld+json"
                 "xml"     "application/rdf+xml"
                 "rdf"     "application/rdf+xml"}]
    {:select    results
     :ask       results
     :construct graphs
     :describe  graphs}))

(def safety-headers
  "The headers of every response of the endpoint. Its bodies are data, often
  made from what clients wrote, so a browser mustn't take them for another
  type, nor run anything in them."
  {"X-Content-Type-Options"  "nosniff"
   "Content-Security-Policy" "default-src 'none'; sandbox"})

(def defaults
  "The default options of the endpoint, which the options you give replace
  one by one:

  - :max-bytes, the most bytes that the body of a request may hold, 1 MB
  - :timeout, the most milliseconds that a query may run, 30 seconds
  - :forms, the forms of query that may run, all-forms
  - :dataset?, whether a request may give the dataset, true
  - :service?, whether a query may call SERVICE, false, since SERVICE sends
    a request from the server to wherever the query says
  - :java?, whether a query may call a function by a java: IRI, false,
    since Jena loads whatever class such an IRI names
  - :versions, the SPARQL versions that a request may ask for, 1.1 and 1.2
  - :media-types and :aliases, the formats of the result of each form of
    query, the vars of the same names
  - :format-param, the query parameter that names a format, format
  - :headers, the headers of every response, safety-headers

  The steps take more options that have no default, e.g. :limit, and
  handle lists them all."
  {:max-bytes    (* 1024 1024)
   :timeout      30000
   :forms        all-forms
   :dataset?     true
   :service?     false
   :java?        false
   :versions     #{"1.1" "1.2"}
   :media-types  media-types
   :aliases      aliases
   :format-param "format"
   :headers      safety-headers})

(defn- failure
  "The operation `op` with an :error of the `status`, `type` and
  `message`."
  [op status type message]
  (assoc op :error {:status status :type type :message message}))

(def protocol-params
  "The parameters of a query operation: those of section 2.1 of the SPARQL
  1.1 Protocol, and the version of SPARQL 1.2."
  #{"query" "default-graph-uri" "named-graph-uri" "version"})

(defn- other-params
  "The `pairs` that the protocol doesn't name, as a map of keyword to value,
  or to a vector of values when a name repeats, as Ring's are."
  [pairs]
  (reduce (fn [m [k v]]
            (if (protocol-params k)
              m
              (let [k (keyword k)]
                (if-let [old (get m k)]
                  (assoc m k (if (vector? old) (conj old v) [old v]))
                  (assoc m k v)))))
          {}
          pairs))

(defn- pairs->operation
  "The operation of the parameter `pairs`, with the `body` as its query
  when it was POSTed directly, and the version of the MIME type `m`."
  [pairs body m]
  (let [queries (cond-> (params/get-all pairs "query") body (conj body))
        version (or (params/get pairs "version")
                    (get-in m [:parameters "version"]))]
    (cond-> {:query             (first queries)
             :default-graph-uri (params/get-all pairs "default-graph-uri")
             :named-graph-uri   (params/get-all pairs "named-graph-uri")
             :params            (other-params pairs)}
      version
      (assoc :version version)

      (next queries)
      (failure 400 :multiple-queries "A request has one query at most.")

      (some-> (first queries) str/blank?)
      (failure 400 :empty-query "The query is empty."))))

(defn- read-bytes
  "The bytes of the InputStream `in`, or nil when it holds more than
  `max-bytes`, in which case it stops reading."
  [^InputStream in max-bytes]
  (let [out (ByteArrayOutputStream.)
        buf (byte-array 8192)]
    (loop [total 0]
      (let [n (.read in buf)]
        (cond
          (neg? n) (.toByteArray out)
          (> (+ total n) max-bytes) nil
          :else (do (.write out buf 0 n)
                    (recur (+ total n))))))))

(defn- utf-8-text
  "The text of the UTF-8 bytes `b`, or ::not-utf-8 when they aren't valid
  UTF-8, which a String would replace without a word."
  [^bytes b]
  (try
    (str (.decode (.newDecoder StandardCharsets/UTF_8) (ByteBuffer/wrap b)))
    (catch CharacterCodingException _
      ::not-utf-8)))

(defn- body-text
  "The body of the Ring `request` as UTF-8 text, nil when it has none,
  ::too-large when it holds more than `max-bytes`, or ::not-utf-8. A
  Content-Length over the limit is enough, so that such a body is never
  read."
  [{:keys [body headers]} max-bytes]
  (let [length (some-> (get headers "content-length") parse-long)]
    (cond
      (and length (> length max-bytes))
      ::too-large

      (string? body)
      (if (> (alength (.getBytes ^String body "UTF-8")) max-bytes)
        ::too-large
        body)

      (instance? InputStream body)
      (if-let [b (read-bytes body max-bytes)]
        (utf-8-text b)
        ::too-large))))

(defn- form-pairs
  "The name-value pairs of the form `text` of the Ring `request`, or of its
  :form-params when middleware has read the body already."
  [request text]
  (if (str/blank? text)
    (for [[k v] (:form-params request)
          v (if (sequential? v) v [v])]
      [(name k) v])
    (params/parse text)))

;; SPARQL 1.1 Protocol, section 2.1.3: "UTF-8 is the only valid charset"
(defn- utf-8?
  "Whether the MIME type `m` has no charset or one that names UTF-8."
  [m]
  (or (nil? (mime/charset m))
      (= "UTF-8" (mime/encoding m))))

(def allowed-methods
  "The value of the Allow field of the endpoint. QUERY is the method of RFC
  10008, which SPARQL 1.2 takes as a direct POST."
  "GET, HEAD, POST, QUERY")

(defn- body-operation
  "The operation of the POST or QUERY `request`, with its parameter `pairs`
  and the MIME type `m` of its body, which holds `max-bytes` at most."
  [request pairs m max-bytes]
  (let [essence (some-> m mime/essence)
        form?   (= "application/x-www-form-urlencoded" essence)
        query?  (= "application/sparql-query" essence)]
    (cond
      (not (or form? query?))
      (failure {} 415 :unsupported-media-type
               (str "A POST has a form or a query of the type "
                    "application/sparql-query."))

      (and query? (not (utf-8? m)))
      (failure {} 415 :unsupported-charset
               "A query in the body has to be in UTF-8.")

      :else
      (let [text (body-text request max-bytes)]
        (cond
          ;; RFC 9110, section 15.5.14
          (= ::too-large text)
          (failure {} 413 :too-large
                   (str "A body has " max-bytes " bytes at most."))

          (= ::not-utf-8 text)
          (failure {} 400 :not-utf-8 "A body has to be in UTF-8.")

          form?
          (pairs->operation (into pairs (form-pairs request text)) nil m)

          :else
          (pairs->operation pairs (or text "") m))))))

(defn operation
  "The SPARQL query operation of the Ring `request`, as section 2.1 of the
  SPARQL 1.1 Protocol defines it: a map of its :query, the IRIs of its
  :default-graph-uri and :named-graph-uri, and its other :params, with the
  :max-bytes of the `opts` over defaults.

  The query comes from the query string of a GET, from the form body of a
  POST, or from the body of a POST or QUERY of the type
  application/sparql-query. A GET may leave out the query, e.g. to get the
  service description. The other parameters are a map of keyword to value,
  or to a vector of values when a name repeats, as in Ring.

  A request that breaks the protocol gets an :error with a status, e.g.
  415 for a POST of another type and 413 for a body over :max-bytes. The
  body is read here, so a middleware that reads it first has to put a form
  in :form-params."
  ([request]
   (operation request nil))
  ([{:keys [request-method headers query-string] :as request} opts]
   (let [{:keys [max-bytes]} (merge defaults opts)
         m     (some-> (get headers "content-type") mime/parse)
         pairs (if query-string (params/parse query-string) [])]
     (case request-method
       (:get :head)
       (pairs->operation pairs nil nil)

       (:post :query)
       (let [op (body-operation request pairs m max-bytes)]
         (cond-> op
           (and (not (:error op)) (nil? (:query op)))
           (failure 400 :no-query "The request has no query.")))

       (failure {} 405 :method-not-allowed
                (str "A query comes by " allowed-methods "."))))))

(defn- form
  "The form of the Query `q`, e.g. :select, or nil for one of Jena's own."
  [^Query q]
  (cond
    (.isSelectType q) :select
    (.isAskType q) :ask
    (.isConstructType q) :construct
    (.isDescribeType q) :describe))

(defn- parse
  "The operation `op` with its query parsed as :sparql, with the `prefixes`
  and the IRI `base`, or with an :error when it doesn't parse as a query."
  [{:keys [query] :as op} prefixes base]
  (try
    (assoc op :sparql (theo/parse-query query prefixes base))
    (catch QueryParseException e
      (if (try (theo/parse-update query prefixes) (catch Exception _ nil))
        (failure op 400 :update-not-allowed "Updates aren't allowed.")
        (failure op 400 :parse-error (ex-message e))))))

(defn- java-iri?
  "Whether the `iri` has the java: scheme, which makes Jena load the class
  that it names."
  [^String iri]
  (str/starts-with? iri "java:"))

(defn- calls
  "The set of what the Query `q` calls that a policy may refuse: :service
  for a SERVICE and :java for a function or property function by a java:
  IRI, anywhere in it, also in a subquery or an EXISTS."
  [^Query q]
  (let [found (volatile! #{})]
    (Walker/walk (Algebra/compile q)
                 (proxy [OpVisitorBase] []
                   (visit [op]
                     (cond
                       (instance? OpService op)
                       (vswap! found conj :service)

                       (and (instance? OpBGP op)
                            (some #(let [p (.getPredicate ^Triple %)]
                                     (and (.isURI p) (java-iri? (.getURI p))))
                                  (.getList (.getPattern ^OpBGP op))))
                       (vswap! found conj :java))))
                 (proxy [ExprVisitorBase] []
                   (visit [e]
                     (when (and (instance? E_Function e)
                                (java-iri? (.getFunctionIRI ^E_Function e)))
                       (vswap! found conj :java)))))
    @found))

;; SPARQL 1.1 Protocol, section 2.1.4: the dataset of the protocol request
;; wins over FROM and FROM NAMED
(defn- dataset-description
  "The DatasetDescription of the protocol parameters of the operation `op`,
  or else of the FROM and FROM NAMED of its :sparql query, or nil when
  neither gives a dataset."
  ^DatasetDescription
  [{:keys [^Query sparql default-graph-uri named-graph-uri]}]
  (cond
    (or (seq default-graph-uri) (seq named-graph-uri))
    (DatasetDescription/create ^java.util.List default-graph-uri
                               ^java.util.List named-graph-uri)

    (.hasDatasetDescription sparql)
    (DatasetDescription/create sparql)))

(defn restrict
  "A copy of the Query `q`, changed by the `opts`:

  - :limit, the LIMIT of a query that has none or a higher one
  - :offset, the OFFSET of a query that has none
  - :distinct?, to make a SELECT a SELECT DISTINCT

  prepare restricts each query by the same opts. To decide them by the
  query, e.g. to page through a query that has no LIMIT of its own, prepare
  it without them and restrict its :sparql afterwards."
  ^Query [^Query q {:keys [limit offset distinct?]}]
  (let [q (.cloneQuery q)]
    (when (and limit (or (= Query/NOLIMIT (.getLimit q))
                         (> (.getLimit q) limit)))
      (.setLimit q limit))
    (when (and offset (pos? offset) (= Query/NOLIMIT (.getOffset q)))
      (.setOffset q offset))
    (when (and distinct? (.isSelectType q))
      (.setDistinct q true))
    q))

(defn prepare
  "The operation `op` with its query parsed as :sparql, a Jena Query, and
  its :form, e.g. :select, checked against the `opts` over defaults, which
  also change it:

  - :prefixes, for the query, or term/default-prefixes
  - :base, the IRI that relative IRIs in the query resolve against
  - :forms, :dataset?, :service?, :java? and :versions, as in defaults
  - :max-length, the most characters that a query may have
  - :limit, :offset and :distinct?, as restrict takes them

  A query that the opts don't allow gets an :error, e.g. 400 when it
  doesn't parse and 403 when it calls SERVICE. An operation with no query,
  or with an :error, is returned as it is."
  [{:keys [query version error] :as op} opts]
  (let [{:keys [prefixes base forms max-length dataset? service? java?
                versions]
         :or   {prefixes term/default-prefixes}
         :as   opts} (merge defaults opts)]
    (cond
      (or error (nil? query))
      op

      (and version (not (versions version)))
      (failure op 400 :version-not-supported
               (str "The SPARQL versions are "
                    (str/join ", " (sort versions)) "."))

      (and max-length (> (count query) max-length))
      (failure op 400 :query-too-long
               (str "A query has " max-length " characters at most."))

      :else
      (let [{:keys [sparql error] :as op} (parse op prefixes base)]
        (if error
          op
          (let [form   (form sparql)
                called (calls sparql)]
            (cond
              (not (forms form))
              (failure op 403 :form-not-allowed
                       (str "The query forms are "
                            (str/join ", " (map (comp str/upper-case name)
                                                (sort forms)))
                            "."))

              (and (not service?) (:service called))
              (failure op 403 :service-not-allowed "SERVICE isn't allowed.")

              (and (not java?) (:java called))
              (failure op 403 :java-not-allowed
                       "Functions by java: IRIs aren't allowed.")

              (and (not dataset?) (dataset-description op))
              (failure op 400 :dataset-not-allowed
                       "A request can't give the dataset.")

              :else
              (assoc op :sparql (restrict sparql opts) :form form))))))))

(defn- dataset-graph
  "The DatasetGraph of `db`, whose default graph is that of a Model."
  ^DatasetGraph [{:keys [^Dataset dataset ^Model model]}]
  (if dataset
    (.asDatasetGraph dataset)
    (DatasetGraphFactory/wrap (.getGraph model))))

(defn- without-dataset
  "A copy of the Query `q` with no FROM or FROM NAMED."
  ^Query [^Query q]
  (doto (.cloneQuery q)
    (-> (.getGraphURIs) (.clear))
    (-> (.getNamedGraphURIs) (.clear))))

(defn- exec-builder
  "The QueryExecBuilder of the Query `q` on the DatasetGraph `dsg`, with the
  prefixes of `db` and the `opts` of run."
  ^QueryExecBuilder [db ^Query q dsg {:keys [timeout service? context]}]
  (let [b (-> (QueryExec/dataset ^DatasetGraph dsg)
              (.query q)
              (.set convert/prefixes-symbol ^Object (:prefixes db))
              (.set ARQ/httpServiceAllowed ^Object (boolean service?)))]
    (doseq [[^Symbol k v] context]
      (.set b k ^Object v))
    (cond-> b
      timeout (.timeout (long timeout) TimeUnit/MILLISECONDS))))

(defn- execute
  "The result of the :sparql query of the operation `op` on `db`, with the
  `opts` of run, read in one read transaction."
  [db {:keys [sparql form] :as op} opts]
  (let [desc (dataset-description op)
        dsg  (if desc
               (DynamicDatasets/dynamicDataset desc (dataset-graph db) false)
               (dataset-graph db))
        q    (cond-> sparql desc (without-dataset))]
    (txn/calculate-read
      db
      #(with-open [qe (.build (exec-builder db q dsg opts))]
         (case form
           :select (ResultSetMem. (ResultSet/adapt (.select qe)))
           :ask (.ask qe)
           :construct (ModelFactory/createModelForGraph (.construct qe))
           :describe (ModelFactory/createModelForGraph (.describe qe)))))))

(defn- run-failure
  "The operation `op` with an :error of the `status`, `type` and `message`
  for the exception `e`, whose message goes in the :details, since it may
  tell more about the server than a client needs to know."
  [op status type message e]
  (-> (failure op status type message)
      (assoc-in [:error :details] (ex-message e))))

(defn run
  "The operation `op` with the :result of its :sparql query on `db`, with
  the `opts` below over defaults. The result is a ResultSetMem for a
  SELECT, with its :vars, a boolean for an ASK, and a Model for a CONSTRUCT
  or DESCRIBE.

  - :timeout, in milliseconds, as in defaults
  - :executor, an ExecutorService that runs the query, so that the wait
    ends at the timeout even when Jena doesn't check it, as
    txn/with-hard-timeout explains
  - :service?, whether SERVICE may run, as in defaults
  - :context, more settings of the query's Jena context, a map of Symbol to
    value

  The dataset of the request picks its graphs from those of `db`, as in
  Fuseki, and is all of `db` when the request gives none. A query that runs
  out of time or that the executor turns down gets an :error of 503, and
  one that Jena fails to run an :error of 500. An operation with no query,
  or with an :error, is returned as it is."
  [{:keys [sparql form error] :as op} db opts]
  (let [{:keys [timeout executor] :as opts} (merge defaults opts)]
    (if (or error (nil? sparql))
      op
      (try
        (let [f      #(execute db op opts)
              result (if (and executor timeout)
                       (txn/with-hard-timeout executor timeout (f))
                       (f))]
          (cond-> (assoc op :result result)
            (= :select form)
            (assoc :vars (mapv #(symbol (str "?" %))
                               (.getResultVars ^ResultSet result)))))
        (catch QueryCancelledException e
          (run-failure op 503 :timeout "The query took too long." e))
        (catch TimeoutException e
          (run-failure op 503 :timeout
                       "The query took too long, or the server is busy." e))
        (catch QueryException e
          (run-failure op 500 :execution-failed "The query failed to run."
                       e))))))

(defn- graph-format
  "The RDFFormat that write uses for the RDF `lang`: Jena's default, except
  for plain RDF/XML, since the pretty one recurses into each resource and
  overflows the stack on a long chain or list."
  ^RDFFormat [^Lang lang]
  (if (= Lang/RDFXML lang)
    RDFFormat/RDFXML_PLAIN
    (RDFWriterRegistry/defaultSerialization lang)))

(defn write
  "The :result of the operation `op` as UTF-8 bytes in the `media-type`,
  or nil when Jena can't write a result of its :form in that type."
  ^bytes [{:keys [form result]} ^String media-type]
  (let [out (ByteArrayOutputStream.)]
    (when (case form
            (:select :ask)
            (let [lang (WebContent/contentTypeToLangResultSet media-type)]
              (when (and lang (ResultSetWriterRegistry/isRegistered lang))
                (let [w (.build (.lang (ResultsWriter/create) lang))]
                  (if (boolean? result)
                    (.write w out (boolean result))
                    ;; a copy, so that the result can be read again
                    (.write w out ^ResultSet
                            (ResultSetMem. ^ResultSetMem result))))
                true))

            (:construct :describe)
            (let [lang (RDFLanguages/contentTypeToLang media-type)]
              (when (and lang (RDFLanguages/isTriples lang))
                (-> (RDFWriter/source ^Model result)
                    (.format (graph-format lang))
                    (.output out))
                true))

            nil)
      (.toByteArray out))))

(defn content-type
  "The Content-Type of the `media-type`, with a UTF-8 charset when it's
  text."
  [media-type]
  (cond-> media-type
    (str/starts-with? media-type "text/") (str ";charset=utf-8")))

(def format-iris
  "The IRI of the format of each media type, by https://www.w3.org/ns/formats/."
  {"application/sparql-results+json" :formats/SPARQL_Results_JSON
   "application/sparql-results+xml"  :formats/SPARQL_Results_XML
   "text/csv"                        :formats/SPARQL_Results_CSV
   "text/tab-separated-values"       :formats/SPARQL_Results_TSV
   "text/turtle"                     :formats/Turtle
   "application/n-triples"           :formats/N-Triples
   "application/ld+json"             :formats/JSON-LD
   "application/rdf+xml"             :formats/RDF_XML})

(defn service-description
  "The SPARQL 1.1 Service Description of the endpoint at the IRI `endpoint`
  as data for theo/transact!, with its result formats from `media-types`, a
  map like the var media-types. The keywords have the prefixes sd and
  formats as well as term/default-prefixes."
  [endpoint media-types]
  [{:rdf/type             :sd/Service
    :sd/endpoint          (str "<" endpoint ">")
    :sd/supportedLanguage :sd/SPARQL11Query
    :sd/resultFormat      (into #{}
                                (keep format-iris)
                                (apply concat (vals media-types)))}])

(defn- description-model
  "A Model of the service description `data`."
  ^Model [data]
  (let [prefixes (assoc term/default-prefixes
                   :sd "<http://www.w3.org/ns/sparql-service-description#>"
                   :formats "<http://www.w3.org/ns/formats/>")
        db       (theo/db {:prefixes prefixes :transactional? false})]
    (theo/transact! db data)
    (ModelFactory/createModelForGraph (theo/jena-graph db))))

(defn- endpoint-iri
  "The IRI of the endpoint: the :endpoint of the `config`, or else the URL
  of the `request` without its query string, when it is a valid IRI."
  [config {:keys [scheme headers uri]}]
  (or (:endpoint config)
      (let [url (str (name (or scheme :http)) "://" (get headers "host") uri)]
        (when (term/iri-string? (str "<" url ">"))
          url))))

(defn negotiation
  "The negotiation for conneg/negotiate of the result of the operation `op`,
  with the :media-types, :aliases and :format-param of the `config` over
  defaults. An operation with no query gets a service description, a
  graph."
  [{:keys [form]} config]
  (let [{:keys [media-types aliases format-param]} (merge defaults config)
        form (or form :describe)]
    {:media-type {:offers  (get media-types form)
                  :aliases (get aliases form)
                  :query   format-param}}))

(defn- request-timeout
  "The timeout of the operation `op`: the seconds of its :timeout parameter,
  as in Fuseki, in milliseconds, but never more than the :timeout of the
  `config`."
  [{:keys [params]} {:keys [timeout]}]
  (let [asked (some-> (:timeout params) str parse-double)
        asked (when (and asked (Double/isFinite asked) (pos? asked))
                (long (* 1000 asked)))]
    (if (and asked timeout)
      (min asked timeout)
      (or asked timeout))))

(defn error-response
  "The plain text response to the :error of the operation `op`, with an
  Allow field for a 405."
  [_ {{:keys [status message]} :error}]
  {:status  status
   :headers (cond-> {"Content-Type" "text/plain;charset=utf-8"}
              (= 405 status) (assoc "Allow" allowed-methods))
   :body    (str message "\n")})

(defn- with-description
  "The operation `op` with the service description of the endpoint at the
  IRI `endpoint` as its result, with the `config` of handle."
  [op endpoint {:keys [media-types] :as config}]
  (let [data (into (service-description endpoint media-types)
                   (:service-description config))]
    (assoc op :form :describe :result (description-model data))))

(defn respond
  "The Ring response to the `request` of the operation `op`, in the media
  type of the `decision` of conneg/negotiate, with the `config` of handle
  over defaults, whose :headers it has as well. An operation with no query
  gets the service description."
  [request {:keys [error sparql] :as op} decision config]
  (let [config   (merge defaults config)
        error-fn (:error config error-response)
        endpoint (endpoint-iri config request)]
    (-> (cond
          error
          (error-fn request op)

          (:not-acceptable decision)
          (error-fn request (failure op 406 :not-acceptable
                                     (str "The media types are "
                                          (->> (negotiation op config)
                                               :media-type :offers
                                               (str/join ", "))
                                          ".")))

          (and (nil? sparql) (false? (:service-description config)))
          (error-fn request (failure op 400 :no-query
                                     "The request has no query."))

          (and (nil? sparql) (nil? endpoint))
          (error-fn request (failure op 400 :invalid-url
                                     "The URL of the request isn't an IRI."))

          :else
          (let [media-type (:media-type decision)
                op         (if sparql op (with-description op endpoint config))]
            {:status  200
             :headers (cond-> {"Content-Type" (content-type media-type)}
                        (seq (:vary decision))
                        (assoc "Vary" (conneg/vary-header (:vary decision))))
             :body    (when-not (= :head (:request-method request))
                        (write op media-type))}))
        (update :headers #(merge (:headers config) %)))))

(defn request-db
  "The db of the `config` of handle for the `request`."
  [{:keys [db]} request]
  (if (fn? db) (db request) db))

(defn prepare-opts
  "The opts of prepare for the `request`: the `config` of handle with the
  prefixes of the db and the endpoint's IRI as the base, unless the config
  gives others."
  [config request]
  (assoc config
    :prefixes (:prefixes config (:prefixes (request-db config request)))
    :base (:base config (endpoint-iri config request))))

(defn run-opts
  "The opts of run for the operation `op`: the `config` of handle over
  defaults, with the timeout that the operation asks for, in seconds, as in
  Fuseki, though never more than the :timeout of the config."
  [config op]
  (let [config (merge defaults config)]
    (assoc config :timeout (request-timeout op config))))

(defn- check
  "Throw when the option `k` of the `config` has a value that `pred`
  doesn't accept."
  [config k pred]
  (let [v (get config k)]
    (when-not (pred v)
      (throw (ex-info (str "Not a valid option: " k " " (pr-str v))
                      {:option k :value v})))))

(defn config
  "The config of handle of the `opts`, over defaults. It throws when an
  option has a value of the wrong kind, e.g. when there is no :db."
  [opts]
  (when-not (map? opts)
    (throw (ex-info "The options are a map." {:opts opts})))
  (let [config (merge defaults opts)]
    (check config :db some?)
    (doseq [k [:max-bytes :timeout :limit :max-length]]
      (check config k (some-fn nil? pos-int?)))
    (check config :forms #(and (set? %) (every? all-forms %)))
    (check config :executor #(or (nil? %) (instance? ExecutorService %)))
    config))

(defn handle
  "The Ring response to the SPARQL query operation `request`, run on a db
  with the `config` below over defaults. Only :db has no default.

  - :db, a db, or a function of the request that returns one
  - :prefixes, for the query and its results, by default those of the db
  - :max-bytes, as operation takes it
  - :forms, :max-length, :limit, :offset, :distinct?, :dataset?, :service?,
    :java? and :versions, as prepare takes them
  - :timeout, the most milliseconds that a query may run. A request may
    ask for fewer with a timeout parameter in seconds, as in Fuseki.
  - :executor and :context, as run takes them
  - :media-types and :aliases, maps like the vars of the same names, for
    the formats that a client may ask for, by Accept or a parameter
  - :format-param, the name of that parameter
  - :headers, the headers of every response
  - :endpoint, the IRI of the endpoint, by default the URL of the request.
    It's in the service description and is the base IRI of the queries,
    as section 2.3 of the protocol allows, unless :base gives another.
  - :service-description, more data for theo/transact! to describe the
    service with, or false to answer a GET with no query with a 400
  - :error, a function of the request and the operation that returns the
    response to its :error, by default error-response

  It answers a GET with no query with the service description."
  [request config]
  (let [config   (merge defaults config)
        db       (request-db config request)
        op       (-> (operation request config)
                     (prepare (prepare-opts config request)))
        decision (when-not (:error op)
                   (conneg/negotiate (negotiation op config) request))
        op       (cond-> op
                   (not (:not-acceptable decision))
                   (run db (run-opts config op)))]
    (respond request op decision config)))
