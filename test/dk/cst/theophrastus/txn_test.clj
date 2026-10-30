(ns dk.cst.theophrastus.txn-test
  "Transactions on each kind of Graph, Model and Dataset, and the hard
  timeout."
  (:require [clojure.test :refer [deftest is]]
            [dk.cst.theophrastus.txn :as txn])
  (:import [clojure.lang ExceptionInfo]
           [java.util.concurrent Executors ExecutorService SynchronousQueue
            ThreadPoolExecutor TimeUnit TimeoutException]
           [org.apache.jena.graph Graph NodeFactory Triple]
           [org.apache.jena.query Dataset DatasetFactory ReadWrite TxnType]
           [org.apache.jena.rdf.model Model ModelFactory]
           [org.apache.jena.reasoner ReasonerRegistry]
           [org.apache.jena.tdb2 TDB2Factory]))

(defn triple
  "A triple with the literal `o` as its object."
  [o]
  (Triple/create (NodeFactory/createURI "http://example.com/s")
                 (NodeFactory/createURI "http://example.com/p")
                 (NodeFactory/createLiteralString o)))

(defn inference
  "An RDFS inference Model on the Model `m`."
  ^Model [m]
  (ModelFactory/createInfModel (ReasonerRegistry/getRDFSReasoner) ^Model m))

(def tdb2-cases
  "Each kind of TDB2 Dataset and Model, with a function of a new TDB2
  dataset that returns [x g]: what the transaction is on, and the graph
  that it writes to."
  [["a TDB2 Dataset"
    (fn [^Dataset ds] [ds (.getGraph (.getDefaultModel ds))])]
   ["a Model of its default graph"
    (fn [^Dataset ds] (let [m (.getDefaultModel ds)] [m (.getGraph m)]))]
   ["a Model of a named graph"
    (fn [^Dataset ds]
      (let [m (.getNamedModel ds "http://example.com/g")] [m (.getGraph m)]))]
   ["an inference Model on its default graph"
    (fn [^Dataset ds]
      (let [m (.getDefaultModel ds)] [(inference m) (.getGraph m)]))]])

(deftest transactional
  (let [ds  (TDB2Factory/createDataset)
        dsg (.asDatasetGraph ds)]
    (is (identical? ds (txn/transactional ds)))
    (is (identical? dsg (txn/transactional (.getDefaultModel ds))))
    (is (identical? dsg (txn/transactional
                          (.getNamedModel ds "http://example.com/g"))))
    (is (identical? dsg (txn/transactional (.getUnionModel ds))))
    (is (identical? dsg (txn/transactional (inference (.getUnionModel ds))))
        "an inference model on the union model"))
  (is (some? (txn/transactional (DatasetFactory/createTxnMem))))
  (is (nil? (txn/transactional (ModelFactory/createDefaultModel))))
  (is (nil? (txn/transactional (inference (ModelFactory/createDefaultModel)))))
  (is (thrown? ExceptionInfo (txn/transactional {}))))

(deftest transaction-types
  (doseq [[label setup] tdb2-cases
          :let [ds    (TDB2Factory/createDataset)
                [x _] (setup ds)
                types #(vector (.transactionType ds) (.transactionMode ds))]]
    (is (= [TxnType/WRITE ReadWrite/WRITE] (txn/calculate-write x types)) label)
    (is (= [TxnType/READ ReadWrite/READ] (txn/calculate-read x types)) label)
    (is (= [TxnType/READ_PROMOTE ReadWrite/READ] (txn/calculate x types))
        label)
    (is (= [TxnType/READ_PROMOTE ReadWrite/READ]
           (txn/with-transaction x (types)))
        label)
    (is (= [TxnType/READ ReadWrite/READ] (txn/with-read-transaction x (types)))
        label)
    (is (= [TxnType/WRITE ReadWrite/WRITE]
           (txn/with-write-transaction x (types)))
        label)))

(deftest rollback-and-promotion
  (doseq [[label setup] tdb2-cases
          :let [ds          (TDB2Factory/createDataset)
                [x ^Graph g] (setup ds)
                size        #(txn/calculate-read ds (fn [] (.size g)))]]
    (is (thrown? ExceptionInfo
                 (txn/calculate-write x #(do (.add g (triple "a"))
                                             (throw (ex-info "boom" {}))))))
    (is (zero? (size)) (str label ": a failure undoes the write"))
    (txn/calculate x #(.add g (triple "b")))
    (is (= 1 (size)) (str label ": a write promotes the transaction"))))

(deftest nesting
  (let [ds    (TDB2Factory/createDataset)
        m     (.getDefaultModel ds)
        write #(txn/calculate-write m (constantly :ok))]
    (is (= :ok (txn/calculate-write ds write)) "a write in a write joins it")
    (is (thrown? Exception (txn/calculate-read ds write))
        "a write in a read")
    (is (thrown? Exception
                 (txn/calculate-read ds #(txn/with-transaction m :ok)))
        "a transaction that can become a write, in a read")))

(deftest no-transactions
  (let [m (ModelFactory/createDefaultModel)
        g (.getGraph m)]
    (is (= :ok (txn/calculate-write m (constantly :ok))))
    (is (thrown? ExceptionInfo
                 (txn/calculate-write m #(do (.add g (triple "a"))
                                             (throw (ex-info "boom" {}))))))
    (is (= 1 (.size g)) "nothing undoes a write to a plain in-memory graph")))

(deftest hard-timeout
  (let [^ExecutorService executor (Executors/newFixedThreadPool 1)]
    (try
      (is (= :ok (txn/with-hard-timeout executor 1000 :ok)))
      (is (thrown-with-msg? ExceptionInfo #"boom"
                            (txn/with-hard-timeout
                              executor 1000 (throw (ex-info "boom" {}))))
          "the exception from the body, not an ExecutionException")
      (is (thrown? TimeoutException
                   (txn/with-hard-timeout executor 50 (Thread/sleep 1000))))
      (finally
        (.shutdownNow executor))))
  (let [executor (ThreadPoolExecutor. 1 1 0 TimeUnit/MILLISECONDS
                                      (SynchronousQueue.))]
    (try
      (.submit executor ^Callable #(Thread/sleep 500))
      (is (thrown-with-msg? TimeoutException #"rejected"
                            (txn/with-hard-timeout executor 1000 :ok))
          "an executor without a queue, whose only thread is busy")
      (finally
        (.shutdownNow executor)))))

(deftest executor
  (let [pool (txn/executor 1 {:name "test"})]
    (try
      (is (= ["test-0" true]
             @(.submit pool ^Callable #(let [t (Thread/currentThread)]
                                         [(.getName t) (.isDaemon t)])))
          "named daemon threads")
      (finally
        (.shutdownNow pool))))
  (doseq [^ThreadPoolExecutor pool [(txn/executor) (txn/executor {:queue 1})]]
    (try
      (is (= (.availableProcessors (Runtime/getRuntime))
             (.getMaximumPoolSize pool))
          "a thread for each processor")
      (finally
        (.shutdownNow pool))))
  (let [pool (txn/executor 1)]
    (try
      (.submit pool ^Callable #(Thread/sleep 500))
      (is (thrown-with-msg? TimeoutException #"rejected"
                            (txn/with-hard-timeout pool 1000 :ok))
          "no queue, so a call is rejected when the thread is busy")
      (finally
        (.shutdownNow pool))))
  (let [pool (txn/executor 1 {:queue 1})]
    (try
      (.submit pool ^Callable #(Thread/sleep 100))
      (is (= :ok (txn/with-hard-timeout pool 1000 :ok))
          "a call waits in the queue")
      (finally
        (.shutdownNow pool)))))
