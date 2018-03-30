(ns jepsen.dgraph.client
  "Clojure wrapper for the Java DGraph client library. This whole thing is
  *riddled* with escaping vulnerabilities, so, you know, tread carefully."
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info]]
            [dom-top.core :refer [with-retry]]
            [wall.hack]
            [cheshire.core :as json]
            [jepsen.client :as jc])
  (:import (com.google.protobuf ByteString)
           (io.grpc ManagedChannel
                    ManagedChannelBuilder)
           (io.dgraph DgraphGrpc
                      DgraphClient
                      DgraphClient$Transaction
                      DgraphProto$Assigned
                      DgraphProto$Mutation
                      DgraphProto$Response
                      DgraphProto$Operation
                      TxnConflictException)))

(def default-port "Default dgraph alpha GRPC port" 9080)

(def deadline "Timeout in seconds" 5)

(defn open
  "Creates a new DgraphClient for the given node."
  ([node]
   (open node default-port))
  ([node port]
   (let [channel (.. (ManagedChannelBuilder/forAddress node port)
                     (usePlaintext true)
                     (build))
         blocking-stub (DgraphGrpc/newBlockingStub channel)]
     (DgraphClient. [blocking-stub] deadline))))

(defn close!
  "Closes a client. Close is asynchronous; resources may be freed some time
  after calling (close! client)."
  [client]
  (doseq [c (wall.hack/field DgraphClient :clients client)]
    (.. c getChannel shutdown)))

(defmacro with-txn
  "Takes a vector of a symbol and a client. Opens a transaction on the client,
  binds it to that symbol, and evaluates body. Calls commit at the end of
  the body, or discards the transaction if an exception is thrown. Ex:

      (with-txn [t my-client]
        (mutate! t ...)
        (mutate! t ...))"
  [[txn-sym client] & body]
  `(let [~txn-sym (.newTransaction ^DgraphClient ~client)]
     (try
       ;(info "Begin transaction.")
       (let [res# (do ~@body)]
         (.commit ~txn-sym)
         ;(info "Transaction committed.")
         res#)
       ;(catch RuntimeException e#
       ;  (info "Transaction aborted.")
       ;  (throw e#))
       (finally
         (.discard ~txn-sym)))))

(defmacro with-unavailable-backoff
  "Wraps an expression returning a completion operation; for selected failure
  modes, sleeps a random amount of time before returning, so we don't spin our
  wheels against a down system."
  [& body]
  `(let [res# (do ~@body)]
     (when (and (= :fail (:type res#))
                (#{:unavailable
                   :unhealthy-connection} (:error res#)))
       (Thread/sleep (rand-int 2000)))
     res#))

(defmacro with-conflict-as-fail
  "Takes an operation and a body. Evaluates body; if a transaction conflict is
  thrown, returns `op` with :type :fail, :error :conflict."
  [op & body]
  `(with-unavailable-backoff
     (try ~@body
          (catch io.grpc.StatusRuntimeException e#
            (condp re-find (.getMessage e#)
              #"DEADLINE_EXCEEDED:"
              (assoc ~op, :type :info, :error :timeout)

              #"context deadline exceeded"
              (assoc ~op, :type :info, :error :timeout)

              #"Conflicts with pending transaction. Please abort."
              (assoc ~op :type :fail, :error :conflict)

              #"Predicate is being moved, please retry later"
              (assoc ~op :type :fail, :error :predicate-moving)

              #"Please retry again, server is not ready to accept requests"
              (assoc ~op :type :fail, :error :not-ready-for-requests)

              #"UNAVAILABLE"
              (assoc ~op, :type :fail, :error :unavailable)

              #"No connection exists"
              (assoc ~op :type :fail, :error :no-connection)

              ; Guessssing this means it couldn't even open a conn but not sure
              ; This might be a fail???
              #"Unavailable desc = all SubConns are in TransientFailure"
              (assoc ~op :type :info, :error :unavailable-all-subconns-down)

              #"dispatchTaskOverNetwork: while retrieving connection. error: Unhealthy connection"
              (assoc ~op :type :info, :error :unhealthy-connection)

              #"Only leader can decide to commit or abort"
              (assoc ~op :type :fail, :error :only-leader-can-commit)

              (throw e#)))

          (catch TxnConflictException e#
            (assoc ~op :type :fail, :error :conflict)))))

(defn str->byte-string
  "Converts a string to a protobuf bytestring."
  [s]
  (ByteString/copyFromUtf8 s))


(defn alter-schema!
  "Takes a schema string (or any number of strings) and applies that alteration
  to dgraph."
  [^DgraphClient client & schemata]
  (.alter client (.. (DgraphProto$Operation/newBuilder)
                     (setSchema (str/join "\n" schemata))
                     build)))

(defn ^DgraphProto$Assigned mutate!*
  "Takes a mutation object and applies it to a transaction. Returns an
  Assigned."
  [^DgraphClient$Transaction txn mut]
  ;(info "Mutate:" mut)
  (.mutate txn (.. (DgraphProto$Mutation/newBuilder)
                   (setSetJson (str->byte-string (json/generate-string mut)))
                   build)))

(defn mutate!
  "Like mutate!*, but returns a map of key names to UID strings."
  [txn mut]
  (.getUidsMap (mutate!* txn mut)))

(defn delete!
  "Deletes a record. Can take either a map (treated as a JSON deletion), or a
  UID string, in which case every outbound edge for the given entity is
  deleted."
  [^DgraphClient$Transaction txn str-or-map]
  (if (string? str-or-map)
    (recur txn {:uid str-or-map})
    (.mutate txn (.. (DgraphProto$Mutation/newBuilder)
                     (setDeleteJson (-> str-or-map
                                        json/generate-string
                                        str->byte-string))
                     build))))

(defn graphql-type
  "Takes an object and infers a type in the query language, e.g.

      \"4\" -> \"string\",
      4     -> \"int\""
  [x]
  (when-not x
    (throw (IllegalArgumentException.
             "Can't infer graphql+- type for `nil`; did you mean to pass a non-nil value instead?")))

  (condp instance? x
    Long    "int"
    Integer "int"
    String  "string"
    Boolean "bool"
    Double  "float"
    clojure.lang.BigInt "int"
    (throw (IllegalArgumentException.
             (str "Don't know graphql+- type of " (pr-str x))))))

(defn query*
  "Runs a query given a graphql+- query string, and a map of variables for the
  query. Variables can be a map of strings, keywords, or symbols to strings,
  keywords, or symbols; they're all coerced to their string names, and prefixed
  with $.

      query(txn \"query all($a: string) { all(func: eq(name, $a)) { uid } }\"
            {:a \"cat\"})"
  ([^DgraphClient$Transaction txn query-str]
   (json/parse-string (.. txn (query query-str) getJson toStringUtf8)
                      true))
  ([^DgraphClient$Transaction txn query vars]
   ;(info "Query (vars:" (pr-str vars) "):" query)
   (let [vars (->> vars
                   (map (fn [[k v]] [(str "$" (name k)) (str v)]))
                   (into {}))
         res (.queryWithVars txn query vars)]
     (json/parse-string (.. res getJson toStringUtf8) true))))

(defn query
  "Like query*, but automatically generates the top-level `query` block with
  query variables inferred from the vars map. Example:

      query(txn,
            \"{ all(func: eq(name, $a)) { uid } }\"
            {:a \"cat\"})"
  ([txn query-str]
   (query* txn query-str))
  ([txn query-str vars]
   (query* txn
           (str "query all("
                (->> vars
                     (map (fn [[k v]] (str "$" (name k) ": " (graphql-type v))))
                     (str/join ", "))
                ") " query-str)
           vars)))

(defn schema
  "Retrieves the current schema as JSON"
  [txn]
  (query txn "schema {}"))

(defn await-ready
  "Blocks until the server is up and responding to requests, or throws. Returns
  client."
  [client]
  (with-retry [attempts 6]
    (with-txn [t client]
      (schema t))
    (catch io.grpc.StatusRuntimeException e
      (cond (<= attempts 1)
            (throw e)

            (and (.getCause e)
                 (instance? java.net.ConnectException
                            (.getCause (.getCause e))))
            (do (info "GRPC interface unavailable, retrying in 5 seconds")
                (Thread/sleep 5000)
                (retry (dec attempts)))

            (re-find #"server is not ready to accept requests"
                     (.getMessage e))
            (do (info "Server not ready, retrying in 5 seconds")
                (Thread/sleep 5000)
                (retry (dec attempts)))

            :else
            (throw e))))
  client)

(defn upsert!
  "Takes a transaction, a predicate, and a record map. If only one map is
  provided, it is used as the predicate. If no record exists for the given
  predicate, inserts the record map.

  Predicate can be a keyword, which is used as the primary key of the record.
  TODO: add more complex predicates.

  Returns nil if upsert did not take place. Returns mutation results otherwise."
  [t pred record]
  (if-let [pred-value (get record pred)]
    (let [res (-> (query t (str "{\n"
                            "  all(func: eq(" (name pred) ", $a)) {\n"
                            "    uid\n"
                            "  }\n"
                            "}")
                     {:a pred-value}))]
      ;(info "Query results:" res)
      (when (empty? (:all res))
        ;(info "Inserting...")
        (mutate! t record)))

    (throw (IllegalArgumentException.
             (str "Record " (pr-str record) " has no value for "
                  (pr-str pred))))))

(defrecord TxnClient [opts conn]
  jc/Client
  (open! [this test node]
    (assoc this :conn (open node)))

  (setup! [this test]
    (alter-schema! conn (str "key: int @index(int) .\n"
                               "val: int .\n")))

  (invoke! [this test op]
    (with-conflict-as-fail op
      (with-txn [t conn]
        (->> (:value op)
             (reduce
               (fn [txn' [f k v :as micro-op]]
                 (case f
                   :r
                   (let [res (query t (str "{ q(func: eq(key, $key)) {\n"
                                           "  val\n"
                                           "}}")
                                    {:key k})
                         reads (:q res)]
                     (conj txn' [f k (condp = (count reads)
                                       ; Not found
                                       0 nil
                                       ; Found
                                       1 (:val (first reads))
                                       ; Ummm
                                       (throw (RuntimeException.
                                                (str "Unexpected multiple results for key "
                                                     (pr-str k) ": "
                                                     (pr-str reads)))))]))

                   ; TODO: we should be able to optimize this to do pure
                   ; inserts and UID-direct writes without the upsert
                   ; read-write cycle, at least when we know the state
                   :w (do (if (:blind-insert-on-write? opts)
                            (mutate! t {:key k :val v})
                            (upsert! t :key {:key k, :val v}))
                          (conj txn' micro-op))))
               [])
             (assoc op :type :ok, :value)))))

  (teardown! [this test])

  (close! [this test]
    (close! conn)))

(defn txn-client
  "A client which can execute generic transcational workloads over arbitrary
  integer keys and values.

  Options:

    :blind-insert-on-write?   If true, don't do upserts; just insert on every
                              write. Only appropriate when you'll never write
                              the same thing twice."
  [opts]
  (TxnClient. opts nil))
