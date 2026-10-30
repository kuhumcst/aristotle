(ns dk.cst.theophrastus.txn
  "Transactions on a db or a Jena Graph, Model or Dataset, and a hard
  timeout.

  Each kind of transaction has a function that calls a function of no
  arguments (e.g. calculate-read) and a macro that runs a body (e.g.
  with-read-transaction). Both return the result of the code they run.

  A Graph or Model of a dataset such as TDB2 uses that dataset's
  transactions, also under an inference graph. A plain in-memory graph has
  none, so the code runs without one: other threads see its writes, and a
  failure partway through doesn't undo them. The in-memory db that theo/db
  makes has transactions unless :transactional? is false."
  (:import [java.util.concurrent ArrayBlockingQueue BlockingQueue
            ExecutionException ExecutorService Future
            RejectedExecutionException SynchronousQueue ThreadPoolExecutor
            TimeUnit TimeoutException]
           [java.util.function Supplier]
           [org.apache.jena.graph Graph]
           [org.apache.jena.query Dataset]
           [org.apache.jena.rdf.model Model]
           [org.apache.jena.reasoner InfGraph]
           [org.apache.jena.sparql.core DatasetGraph GraphView Transactional]
           [org.apache.jena.system Txn]))

(defn transactional
  "The dataset whose transactions `x` (a db, Graph, Model or Dataset) uses,
  or nil when there is none."
  ^Transactional [x]
  (cond
    (instance? Dataset x)      (when (.supportsTransactions ^Dataset x) x)
    (instance? DatasetGraph x) (when (.supportsTransactions ^DatasetGraph x) x)
    (instance? Model x)        (transactional (.getGraph ^Model x))
    (instance? GraphView x)    (transactional (.getDataset ^GraphView x))
    (instance? InfGraph x)     (transactional (.getRawGraph ^InfGraph x))
    (instance? Graph x)        nil
    ;; a db, whose namespace requires this one
    (map? x)                   (transactional (or (:dataset x) (:model x)))
    :else (throw (ex-info (str "Not a db, Graph, Model or Dataset: "
                               (pr-str x))
                          {:x x}))))

;; named after the methods of Jena's Txn that they call
(defn calculate
  "Call `f` in a transaction on `x` and return its result. The transaction
  starts as a read and becomes a write when `f` writes, which fails when
  another write has committed in the meantime. A function that always
  writes is better off with calculate-write."
  [x f]
  (if-let [t (transactional x)]
    (Txn/calculate t ^Supplier f)
    (f)))

(defn calculate-read
  "Call `f` in a read transaction on `x` and return its result."
  [x f]
  (if-let [t (transactional x)]
    (Txn/calculateRead t ^Supplier f)
    (f)))

(defn calculate-write
  "Call `f` in a write transaction on `x` and return its result."
  [x f]
  (if-let [t (transactional x)]
    (Txn/calculateWrite t ^Supplier f)
    (f)))

;; named after next.jdbc's with-transaction
(defmacro with-transaction
  "Run `body` in a transaction on `x` and return its value. The transaction
  starts as a read and becomes a write when `body` writes, as calculate's
  does. It throws inside a read transaction, even when `body` only reads, so
  code that may run in one is better off with with-read-transaction."
  [x & body]
  `(calculate ~x (fn [] ~@body)))

(defmacro with-read-transaction
  "Run `body` in a read transaction on `x` and return its value, as
  calculate-read does."
  [x & body]
  `(calculate-read ~x (fn [] ~@body)))

(defmacro with-write-transaction
  "Run `body` in a write transaction on `x` and return its value, as
  calculate-write does."
  [x & body]
  `(calculate-write ~x (fn [] ~@body)))

;; Jena's own query timeout only takes effect when the query moves on to
;; the next result, and a rule reasoner can spend minutes before it does
(defmacro with-hard-timeout
  "Run `body` on `executor` and return its value. Throw a TimeoutException
  after `timeout` milliseconds, or when the executor rejects `body`. An
  exception from `body` is thrown as it is.

  The thread that runs `body` is left to finish, since interrupting it would
  make Java close TDB2's file channels for good. An executor with a queue
  lets a call wait in it until the timeout runs out, and one without a
  queue rejects a call when all its threads are busy."
  [executor timeout & body]
  `(let [^ExecutorService executor# ~executor
         timeout# ~timeout
         ^Callable f# (fn [] ~@body)
         ^Future fut# (try
                        (.submit executor# f#)
                        (catch RejectedExecutionException _#
                          (throw (TimeoutException.
                                   "The executor rejected the call"))))]
     (try
       (.get fut# timeout# TimeUnit/MILLISECONDS)
       (catch TimeoutException e#
         (.cancel fut# false)
         (throw e#))
       (catch ExecutionException e#
         (throw (.getCause e#))))))

(defn executor
  "A pool of `n` threads with these `opts`, for with-hard-timeout and the
  :executor of the endpoint. Without `n`, it has a thread for each
  processor of the JVM, and the `opts` may come alone:

  - :queue, the most calls that may wait for a thread (0 when not given),
    so that a pool with no queue rejects a call when all its threads are
    busy.
  - :name, the prefix of the names of its threads (theophrastus when not
    given), e.g. for a thread dump.

  Its threads are daemons, so a call that ran out of time can't keep the
  JVM running."
  (^ExecutorService []
   (executor {}))
  (^ExecutorService [x]
   (if (map? x)
     (executor (.availableProcessors (Runtime/getRuntime)) x)
     (executor x nil)))
  (^ExecutorService [n {:keys [queue] prefix :name
                        :or   {queue 0 prefix "theophrastus"}}]
   (let [^BlockingQueue q (if (pos? queue)
                            (ArrayBlockingQueue. (int queue))
                            (SynchronousQueue.))]
     (ThreadPoolExecutor. (int n) (int n) 0 TimeUnit/MILLISECONDS q
                          (-> (Thread/ofPlatform)
                              (.name (str prefix "-") 0)
                              (.daemon true)
                              (.factory))))))
