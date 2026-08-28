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
    (try
      (handler request #(respond % true) raise)
      (catch :default ex
        (raise ex)))))

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
   :read-buffer-size     8192
   :max-header-bytes     65536
   :max-header-count     100
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
  - `:executor` - the ExecutorService to use for running handlers; supplied
    executors are borrowed unless `:shutdown-executor?` is true
  - `:pool-size` - size of the default executor (defaults to 32)
  - `:port` - the port number to listen on (defaults to 80)
  - `:read-buffer-size` - the size of the buffer to use when reading from the
    socket, which bounds one request line or header field (defaults to 8K)
  - `:max-header-bytes` - maximum aggregate request header-section bytes,
    including CRLF delimiters (defaults to 64K)
  - `:max-header-count` - maximum request header field count (defaults to 100)
  - `:recv-buffer-size` - the receive buffer size (i.e. the SO_RCVBUF option)
  - `:remote-addr` - optional override for the connected peer address reported
    on requests (defaults to jolt-tcp's actual peer endpoint)
  - `:response-buffer-size` - the size of the buffer used when constructing the
    response, which must be at least large enough to contain the response
    status line and headers (defaults to 32K)
  - `:reuse-address?` - sets the SO_REUSEADDR socket option (default false)
  - `:server-name` - optional override for the local address reported on
    requests (defaults to jolt-tcp's actual local endpoint)
  - `:shutdown-executor?` - adopt and shut down a supplied `:executor` (default
    false; an executor created by jolt-http is always adopted)
  - `:stream-queue-size` - how many request body chunks may be buffered before
    the reader applies backpressure (defaults to 8)
  - `:write-buffer-size` - the write buffer size in bytes (default 128K)
  - `:write-queue-size` - the max number of writes in the queue (default 64)"
  [handler & {:as supplied-options}]
  (let [owns-executor? (nil? (:executor supplied-options))
        options  (merge default-options supplied-options)
        ;; A configurable bounded pool. Capra uses a virtual-thread-per-task
        ;; executor, but jolt has no M:N virtual threads. In v0.4.15 the
        ;; cached, virtual-thread, and work-stealing constructor shims all map to
        ;; one fixed 32-worker implementation, so none provides Capra's scaling
        ;; semantics. A separate high-concurrency native-I/O workload has ended
        ;; in Chez's "nonrecoverable invalid memory reference"; that runtime
        ;; safety case still needs a minimal reproducer. Keep explicit bounded
        ;; policy here rather than relying on a misleading constructor name.
        ;;
        ;; Bounding the handler pool does not reintroduce the deadlock that the
        ;; alternate pool was avoiding: jolt-tcp runs write-completion callbacks on
        ;; a separate executor, so a handler blocked on its own write is always
        ;; released, however small this pool is. Excess requests queue instead.
        executor (or (:executor options)
                     (java.util.concurrent.Executors/newFixedThreadPool
                      (:pool-size options)))
        ;; TCP cannot infer that an executor passed by this adapter is owned by
        ;; the adapter. Transfer ownership explicitly for the pool we create,
        ;; while preserving TCP's borrowed-by-default rule for a user pool.
        options  (cond-> (assoc options :executor executor)
                   owns-executor? (assoc :shutdown-executor? true))
        handler  (if (:async? options)
                   (async-handler handler)
                   (sync-handler handler))]
    (tcp/run-server
     (assoc options :handler (protocol/tcp-handler handler options)))))

(defn stop-server
  "Stop a server started by [[run-server]]."
  [server]
  (tcp/stop-server server))
