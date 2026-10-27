(ns arachne.aristotle.txn
  "Transactions on a Jena Graph, Model or Dataset, and a hard timeout.

  Each transaction calls a function of no arguments and returns what it
  returns. A Graph or Model of a dataset, e.g. TDB2, uses the transactions
  of that dataset, also under an inference graph. A plain in-memory graph
  has none, so the function runs as it is: other threads can see its
  writes, and a failure partway through doesn't undo them. For an
  in-memory store with transactions, use DatasetFactory/createTxnMem."
  (:import [java.util.concurrent ExecutionException ExecutorService Future
            RejectedExecutionException TimeUnit TimeoutException]
           [java.util.function Supplier]
           [org.apache.jena.graph Graph]
           [org.apache.jena.query Dataset]
           [org.apache.jena.rdf.model Model]
           [org.apache.jena.reasoner InfGraph]
           [org.apache.jena.sparql.core DatasetGraph GraphView Transactional]
           [org.apache.jena.system Txn]))

(defn transactional
  "The dataset whose transactions `x`, a Graph, Model or Dataset, uses, or
  nil when it has none."
  ^Transactional [x]
  (cond
    (instance? Dataset x)      (when (.supportsTransactions ^Dataset x) x)
    (instance? DatasetGraph x) (when (.supportsTransactions ^DatasetGraph x) x)
    (instance? Model x)        (transactional (.getGraph ^Model x))
    (instance? GraphView x)    (transactional (.getDataset ^GraphView x))
    (instance? InfGraph x)     (transactional (.getRawGraph ^InfGraph x))
    (instance? Graph x)        nil
    :else (throw (ex-info (str "Not a Graph, Model or Dataset: " x)
                          {:x x}))))

(defn calculate
  "Call `f` in a transaction on `x` and return what it returns. The
  transaction starts as a read and becomes a write when `f` writes. That
  fails when another write has committed since it began, so a function
  that writes is better off with calculate-write."
  [x f]
  (if-let [t (transactional x)]
    (Txn/calculate t ^Supplier f)
    (f)))

(defn calculate-read
  "Call `f` in a read transaction on `x` and return what it returns."
  [x f]
  (if-let [t (transactional x)]
    (Txn/calculateRead t ^Supplier f)
    (f)))

(defn calculate-write
  "Call `f` in a write transaction on `x` and return what it returns."
  [x f]
  (if-let [t (transactional x)]
    (Txn/calculateWrite t ^Supplier f)
    (f)))

;; Jena's own query timeout only takes effect when the query moves on to
;; the next result, and a rule reasoner can spend minutes before it does
(defn with-hard-timeout
  "Call `f` on `executor` and return what it returns. Throw a
  TimeoutException after `timeout` milliseconds, or when the executor
  rejects `f`. An exception from `f` is thrown as it is.

  The thread that runs `f` is left to finish, since interrupting it would
  make Java close TDB2's file channels for good. An executor with a queue
  lets a call wait there until its timeout runs out. Only one without a
  queue rejects a call when all its threads are busy."
  [^ExecutorService executor timeout f]
  (let [^Future fut (try
                      (.submit executor ^Callable f)
                      (catch RejectedExecutionException _
                        (throw (TimeoutException.
                                 "The executor rejected the call"))))]
    (try
      (.get fut timeout TimeUnit/MILLISECONDS)
      (catch TimeoutException e
        (.cancel fut false)
        (throw e))
      (catch ExecutionException e
        (throw (.getCause e))))))
