(ns jolt.http.server
  "The namespace for running the jolt-http server.

      (require '[jolt.http.server :as http])

      (defn handler [_request]
        {:status  200
         :headers {\"Content-Type\" \"text/plain\"}
         :body    \"Hello World\"})

      (def server (http/run-server handler :port 3000))
      ;; ... later ...
      (http/stop-server server)

  Note that jolt-http loads core.async (for request body streaming), which
  starts non-daemon threads. A `-main` that runs a server will not exit on its
  own — end it with `(System/exit 0)`."
  (:require [jolt.http.protocol :as protocol]
            [teensyp.server :as tcp]))

(defn- async-handler [handler]
  (fn [request respond raise]
    (handler request #(respond % true) raise)))

(defn- sync-handler [handler]
  (fn [request respond raise]
    (let [response (try (handler request)
                        (catch :default ex (raise ex) ::error))]
      (when (not= response ::error)
        (respond response false)))))

(defn- default-error-handler [_request respond _exception]
  (respond {:status  500
            :headers {"Content-Type" "text/plain; charset=UTF-8"}
            :body    "Internal Server Error"}))

(defn- default-error-logger [exception]
  (binding [*out* *err*] (prn exception)))

(def ^:private default-options
  {:error-handler        default-error-handler
   :error-logger         default-error-logger
   :port                 80
   :server-name          "127.0.0.1"
   :remote-addr          "127.0.0.1"
   :read-buffer-size     8192
   :response-buffer-size 32768
   ;; 4x the response buffer: jolt-tcp rejects a queued write that exceeds the
   ;; remaining write credit, and a full response buffer is queued at once.
   :write-buffer-size    131072
   :stream-queue-size    8
   ;; Size of the default (bounded) handler pool. This caps how many handlers
   ;; can be in flight at once, which matters when handlers block streaming a
   ;; body; raise it for blocking workloads, but note that each thread is a real
   ;; OS thread and jolt does not tolerate very large numbers of them.
   :pool-size            32})

(defn- shutdown-and-await! [executor]
  (.shutdown executor)
  (loop []
    (when-not (.isTerminated executor)
      (Thread/yield)
      (recur))))

(defn run-server
  "Start a web server in a new thread with the supplied Ring handler and
  options. Returns a handle that can be passed to [[stop-server]], and which
  works with `with-open`.

  Accepts the following options:

  - `:async?` - if true, expect 3-arity async Ring handlers (defaults to false)
  - `:control-queue-size` - the max number of queued control events (default 32)
  - `:error-handler` - an asynchronous Ring handler function used to handle
    uncaught exceptions (defaults to sending a 500 Internal Server Error)
  - `:error-logger` - a function that takes a single exception argument and
    logs it somehow (defaults to printing to *err*)
  - `:executor` - the ExecutorService to use for running handlers
  - `:pool-size` - size of the default executor (defaults to 32)
  - `:port` - the port number to listen on (defaults to 80)
  - `:read-buffer-size` - the size of the buffer to use when reading from the
    socket, which bounds the request line and header size (defaults to 8K)
  - `:recv-buffer-size` - the receive buffer size (i.e. the SO_RCVBUF option)
  - `:remote-addr` - reported as `:remote-addr` on requests; jolt-tcp exposes no
    peer address, so this is a constant (defaults to 127.0.0.1)
  - `:response-buffer-size` - the size of the buffer used when constructing the
    response, which must be at least large enough to contain the response
    status line and headers (defaults to 32K)
  - `:reuse-address?` - sets the SO_REUSEADDR socket option (default false)
  - `:server-name` - reported as `:server-name` on requests (default 127.0.0.1)
  - `:shutdown-executor?` - if true, adopt and shut down a caller-supplied
    executor on stop (default false; the internally created executor is always
    shut down)
  - `:stream-queue-size` - how many request body chunks may be buffered before
    the reader applies backpressure (defaults to 8)
  - `:write-buffer-size` - the write buffer size in bytes (default 128K)
  - `:write-queue-size` - the max number of writes in the queue (default 64)"
  [handler & {:as options}]
  (let [supplied-executor (:executor options)
        owns-executor? (not supplied-executor)
        adopt-executor? (or owns-executor?
                            (true? (:shutdown-executor? options)))
        options  (merge default-options options)
        ;; A *bounded* pool. Capra uses a virtual-thread-per-task executor, and
        ;; the obvious translation is a cached pool — but jolt has no virtual
        ;; threads, so that means one OS thread per in-flight blocking handler,
        ;; and a burst of concurrent streaming responses spawns enough threads
        ;; doing concurrent FFI to crash Chez outright ("nonrecoverable invalid
        ;; memory reference"). Bounded is the safe default here.
        ;;
        ;; Bounding the handler pool does not reintroduce the deadlock that the
        ;; cached pool was avoiding: jolt-tcp runs write-completion callbacks on
        ;; a separate executor, so a handler blocked on its own write is always
        ;; released, however small this pool is. Excess requests queue instead.
        executor (or supplied-executor
                     (java.util.concurrent.Executors/newFixedThreadPool
                      (:pool-size options)))
        ;; jolt-tcp treats every supplied executor as caller-owned unless this
        ;; flag opts into adoption. HTTP has to construct its default pool
        ;; before creating the protocol handler, so explicitly transfer that
        ;; pool's ownership while preserving the caller's choice for a custom
        ;; executor.
        options  (cond-> (assoc options :executor executor)
                   owns-executor? (assoc :shutdown-executor? true))
        handler  (if (:async? options)
                   (async-handler handler)
                   (sync-handler handler))]
    (try
      (tcp/run-server
       (assoc options :handler (protocol/tcp-handler handler options)))
      (catch :default e
        ;; jolt-tcp attempts listen before it records a supplied executor in
        ;; its startup cleanup state. If that early step fails, ownership has
        ;; not transferred yet. Reclaim an HTTP-owned or explicitly adopted
        ;; pool; a borrowed caller pool stays caller-owned on failed startup
        ;; just as it does after a successful stop.
        ;; Later jolt-tcp failures may already have shut the adopted pool down;
        ;; shutdown is idempotent, and awaiting again preserves the same
        ;; termination-before-rethrow contract. Cleanup failure must not replace
        ;; the original startup exception.
        (when adopt-executor?
          (try (shutdown-and-await! executor)
               (catch :default _ nil)))
        (throw e)))))

(defn stop-server
  "Stop a server started by [[run-server]]."
  [server]
  (tcp/stop-server server))
