(ns jolt.http.fake-socket
  "An in-process `teensyp.server/Socket` and a reactor-shaped driver for
  `jolt.http.protocol/tcp-handler`.

  `teensyp.server/Socket` is a protocol, so the HTTP state machine can be run
  with no reactor, no port and no sleep: bytes are handed to the read arity
  directly and everything it writes is collected in a vector. That makes the
  parser testable deterministically and at thousands of cases per second, which
  is where the generative conformance properties belong — a loopback fixture
  spends nearly all its time in connect/close.

  Being *faithful* to the reactor is the whole point, so [[feed!]] reproduces
  the read-buffer protocol from teensyp.server rather than approximating it:

  - a fixed-capacity read buffer whose position is the write cursor;
  - a read *view* sharing the same array, re-based to [0, rb.position) before
    each handler call (`update-read-view!`);
  - after the handler returns, the bytes it consumed are dropped and the
    remainder shifted to the front (`drop-consumed!` / `compact-by!`);
  - the FULL flag when the buffer fills, and the `handle-pending-read`
    re-submission when unconsumed bytes remain.

  Two deliberate differences from the reactor, both of which make the harness
  *stricter* rather than more forgiving:

  - Writes complete instantly and their callbacks fire synchronously, inside
    `tcp/write`. `body/socket-sink` queues with a callback that delivers a
    promise and then derefs it, so delivering first is safe; a handler that
    depended on the callback being deferred would be relying on reactor timing
    it is not promised.
  - There is no socket buffer, so a write never reports EAGAIN. The partial-send
    loop is jolt-tcp's concern and is covered by jolt-tcp's own properties.

  This harness is the fast layer only. The loopback properties in
  jolt.http.server-property-test cover the same protocol behaviour over real
  transport, so a divergence between the two shows up as one passing and the
  other failing."
  (:require [jolt.http.protocol :as protocol]
            [teensyp.buffer :as buf]
            [teensyp.server :as tcp]))

;; --- the socket ------------------------------------------------------------

(defrecord FakeSocket [lock flags writes controls])

(defn- snapshot
  "The live region of a queued write, as a byte-array. Copies, because the
  caller's buffer is the connection's shared response buffer and is about to be
  cleared and rewritten — the same reason body/queue-write! copies."
  ^bytes [b]
  (let [d (buf/duplicate b)]
    (buf/get-bytes! d (buf/remaining d))))

(extend-type FakeSocket
  tcp/Socket
  ;; jolt-tcp does all socket I/O on the reactor thread, so its try-write also
  ;; always returns false and every write is queued.
  (try-write [_ _] false)
  ;; Control events are recorded rather than applied. ::resume-reads matters:
  ;; in the reactor handle-control re-submits the read handler when buffered
  ;; data is waiting, which is how a pipelined request behind a streaming body
  ;; gets parsed. [[pump-resumes!]] replays that.
  (queue-control [this event callback]
    (swap! (:controls this) conj event)
    (when callback (callback)))
  (queue-write [this buffer callback]
    (if (= buffer :teensyp.server/close)
      (do (swap! (:writes this) conj :close)
          (vswap! (:flags this) bit-or (long tcp/CLOSED)))
      (swap! (:writes this) conj (snapshot buffer)))
    (when callback (callback)))
  (socket-info [_] {:local-address nil :remote-address nil :fd -1})
  (socket-lock [this] (:lock this))

  tcp/CompletionSocket
  (queue-write-completion [this buffer completion]
    (swap! (:writes this) conj (snapshot buffer))
    (deliver completion {:status :written})))

(defn fake-socket []
  (map->FakeSocket {:lock     (java.util.concurrent.locks.ReentrantLock.)
                    ;; a volatile, not an atom: tcp/peer-closed? derefs
                    ;; (:flags socket) directly and teensyp vswap!s it.
                    :flags    (volatile! 0)
                    :writes   (atom [])
                    :controls (atom [])}))

(defn- flag? [sock flag]
  (not (zero? (bit-and (long @(:flags sock)) (long flag)))))

(defn closed? [conn] (flag? (:socket conn) tcp/CLOSED))

;; --- read buffer plumbing (ported from teensyp.server, which keeps it private)

(defn- compact-by!
  "Drop the first `n` consumed bytes of read-buffer `b`, shifting the live bytes
  to the front; position -= n, limit unchanged."
  [b n]
  (when (pos? (long n))
    (let [^bytes arr (:arr b)
          pos  (buf/position b)
          n    (long n)
          keep (- pos n)]
      (loop [i 0]
        (when (< i keep)
          (aset arr i (aget arr (+ n i)))
          (recur (inc i))))
      (buf/set-position! b keep))))

(defn- drop-consumed! [{:keys [rb rv]}]
  (let [n (buf/position rv)]
    (when (pos? n)
      (compact-by! rb n)
      (buf/set-limit! rv (- (buf/limit rv) n))
      (buf/set-position! rv 0))))

(defn- update-read-view! [{:keys [rb rv]}]
  (buf/set-position! rv 0)
  (buf/set-limit! rv (buf/position rb)))

;; --- the connection --------------------------------------------------------

(defn- sync-ring-handler
  "jolt.http.server's sync wrapper, which is private there. A handler that
  throws must reach `raise`, not the caller."
  [handler]
  (fn [request respond raise]
    (let [response (try (handler request)
                        (catch :default ex (raise ex) ::error))]
      (when (not= response ::error)
        (respond response false)))))

(def default-opts
  {:port                 8080
   :server-name          "127.0.0.1"
   :remote-addr          "127.0.0.1"
   :read-buffer-size     8192
   :max-header-bytes     65536
   :max-header-count     100
   :response-buffer-size 32768
   :stream-queue-size    8
   :error-logger         (fn [_])
   :error-handler        (fn [_request respond _ex]
                           (respond {:status  500
                                     :headers {"Content-Type" "text/plain; charset=UTF-8"}
                                     :body    "Internal Server Error"}))})

(defn connect
  "A fresh connection over `tcp-handler-fn` (the value returned by
  `protocol/tcp-handler`). Mirrors what the reactor does on accept: allocate the
  read buffer, flip a view over it, and call the handler's 1-arity for the
  initial state."
  [tcp-handler-fn opts]
  (let [cap  (long (:read-buffer-size opts))
        rb   (buf/buffer cap)
        sock (fake-socket)]
    {:handler tcp-handler-fn
     :socket  sock
     :rb      rb
     :rv      (buf/flip (buf/duplicate rb))
     :opts    opts
     :state   (volatile! (tcp-handler-fn sock))}))

(defn- call-handler! [{:keys [handler state socket rv] :as conn}]
  (update-read-view! conn)
  (vswap! state handler socket rv))

(defn- pump!
  "The `handle-pending-read` loop: while the handler consumed something and
  unconsumed bytes remain, hand it a fresh view.

  Every iteration consumes at least one byte from a buffer holding at most
  `capacity`, so more iterations than that means the loop is not making
  progress. That throws rather than returning quietly: a parser that can be made
  to spin is a denial-of-service bug, and it must show up as a failing property
  instead of a silently truncated pump."
  [{:keys [rb rv] :as conn}]
  (let [limit (+ (long (buf/capacity rb)) 8)]
    (loop [guard 0]
      (when (>= guard limit)
        (throw (ex-info "read pump made no progress; the handler may be spinning"
                        {:hegel/origin "fake-socket/pump-runaway"
                         :capacity (buf/capacity rb)})))
      (let [before (buf/position rv)]
        (when (pos? before)
          (drop-consumed! conn)
          (when (pos? (buf/position rb))
            (call-handler! conn)
            (recur (inc guard))))))))

(defn feed!
  "Hand `bs` (a byte vector or byte-array) to the connection the way the reactor
  would, and return the number of bytes actually accepted. Fewer than all of
  them means the read buffer filled: feed the remainder after the handler has
  drained some, exactly as the reactor's next readable event would.

  Returns 0 once the peer has been marked half-closed or the socket is closed,
  since no further data can arrive on either."
  [{:keys [rb socket] :as conn} bs]
  (let [bs (if (bytes? bs) (vec bs) (vec bs))]
    (if (or (flag? socket tcp/EOF) (flag? socket tcp/CLOSED))
      0
      (do
        (drop-consumed! conn)
        (let [space (- (long (buf/capacity rb)) (buf/position rb))
              n     (min space (count bs))]
          (when (pos? n)
            (let [arr (byte-array n)]
              (dotimes [i n] (aset arr i (unchecked-byte (long (nth bs i)))))
              (buf/put-bytes! rb arr 0 n)))
          (when (pos? n)
            (call-handler! conn)
            (pump! conn))
          n)))))

(defn feed-all!
  "Feed every byte of `bs`, re-feeding whatever did not fit until it is all
  accepted or the connection stops accepting. Returns the bytes never accepted."
  [conn bs]
  (let [limit (+ (count bs) 16)]
    (loop [remaining (vec bs) guard 0]
      (cond
        (empty? remaining) remaining
        ;; Each accepted round moves at least one byte, so exceeding this means
        ;; the harness itself is looping — never a silent truncation.
        (> guard limit)
        (throw (ex-info "feed-all! made no progress"
                        {:hegel/origin "fake-socket/feed-runaway"
                         :remaining (count remaining)}))
        :else
        (let [n (feed! conn remaining)]
          (if (zero? n)
            remaining
            (recur (subvec remaining n) (inc guard))))))))

(defn half-close!
  "Mark the peer's write side closed and deliver the one read-arity call the
  contract promises when `peer-closed?` becomes true (teensyp's handle-eof! /
  handle-pending-read EOF-SEEN path)."
  [{:keys [socket] :as conn}]
  (when-not (flag? socket tcp/EOF)
    ;; Real TCP sets EOF-SEEN at the same moment it refreshes the terminal read
    ;; view handed to the handler. This direct driver performs that refresh
    ;; synchronously below, so publish both flags together.
    (vswap! (:flags socket) bit-or (long tcp/EOF) (long tcp/EOF-SEEN))
    (drop-consumed! conn)
    (call-handler! conn)
    (pump! conn))
  conn)

(defn pump-resumes!
  "Replay any ::resume-reads queued since the last call: if unconsumed bytes are
  waiting, hand them to the handler. This is teensyp's handle-control ->
  submit-read-handler path, and without it a request pipelined behind a
  streaming body would never be parsed. Returns true if the handler ran."
  [{:keys [socket rb] :as conn}]
  (let [ctl     (:controls socket)
        pending (loop [] (let [v @ctl] (if (compare-and-set! ctl v []) v (recur))))]
    (when (and (some #(= % :teensyp.server/resume-reads) pending)
               (pos? (buf/position rb))
               (not (closed? conn)))
      (call-handler! conn)
      (pump! conn)
      true)))

(defn await!
  "Poll `done?` until it holds, replaying resume events each time round.

  A response produced by the streaming-body path is written from an executor
  thread (`run-streaming-handler`), so it is not visible the instant `feed!`
  returns. This is the harness's one timing-dependent moment, and it is bounded:
  reaching the deadline returns false, and every caller must treat that as a
  failure rather than as a reason to look again later — the same discipline the
  loopback fixture applies to draining a response."
  ([conn done?] (await! conn done? 5000))
  ([conn done? timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
     (loop []
       (pump-resumes! conn)
       (cond
         (done?)                                      true
         (>= (System/currentTimeMillis) deadline)      false
         :else (do (Thread/sleep 1) (recur)))))))

(defn written
  "Everything the connection has written, as one byte vector. The `:close`
  markers are dropped; use [[closed?]] to ask about them."
  [{:keys [socket]}]
  (into [] (comp (remove keyword?)
                 (mapcat (fn [^bytes a]
                           (map (fn [i] (bit-and (long (aget a i)) 0xff))
                                (range (alength a))))))
        @(:writes socket)))

(defn close-is-last?
  "A ::close must be the final entry in the write queue: anything queued after
  it can never reach the client."
  [{:keys [socket]}]
  (let [ws @(:writes socket)]
    (or (not (some keyword? ws))
        (keyword? (peek ws)))))

;; One pool for every connection this harness creates. A pool per connection
;; would mean a pool per generated case, and a property running hundreds of
;; cases then holds hundreds of never-shut-down pools — which exhausts the
;; process's threads and surfaces as "Resource temporarily unavailable" from
;; deep inside an unrelated property.
(defonce ^:private shared-executor
  (java.util.concurrent.Executors/newFixedThreadPool 16))

(defn- tracking-executor [delegate tasks]
  (reify java.util.concurrent.Executor
    (execute [_ task]
      (swap! tasks update :submitted inc)
      (try
        (.execute
         ^java.util.concurrent.Executor delegate
         (fn []
           (swap! tasks
                  (fn [s]
                    (let [active (inc (:active s))]
                      (-> s
                          (update :started inc)
                          (assoc :active active)
                          (update :max-active max active)))))
           (try
             (task)
             (finally
               (swap! tasks
                      (fn [s]
                        (-> s
                            (update :completed inc)
                            (update :active dec))))))))
        (catch :default e
          (swap! tasks update :rejected inc)
          (throw e))))))

(defn- tasks-settled? [{:keys [submitted completed rejected]}]
  (= submitted (+ completed rejected)))

(defn- await-handler-tasks!
  "Wait for this fake connection's submitted handler tasks to leave the shared
  executor after its body channel is closed.

  Without this barrier, one generated case can return while its handler is still
  unwinding. Enough such cases make a later property look deadlocked even though
  the unfinished work belongs to earlier harness cleanup."
  [{:keys [handler-tasks] :as conn}]
  (when handler-tasks
    (let [deadline (+ (System/currentTimeMillis) 2000)]
      (loop []
        (cond
          (tasks-settled? @handler-tasks) nil
          (>= (System/currentTimeMillis) deadline)
          (throw
           (ex-info "fake connection handler tasks did not settle after close"
                    {:err ::handler-tasks-not-settled
                     :tasks @handler-tasks
                     :closed? (closed? conn)}))
          :else (do (Thread/sleep 1) (recur)))))))

(defn close-conn!
  "End the connection the way the reactor does when it closes: mark it CLOSED
  and run the handler's close arity, which closes the request-body channel.

  Every case must call this, and not only for tidiness. A handler blocked in
  `body-bytes` on a body that never finished arriving — which is exactly what a
  truncated request in a fuzz property produces — holds a pool thread forever,
  and once the shared pool is full every later case hangs. Closing the channel
  is what releases it. The settlement barrier then proves that all work this
  connection submitted has left the executor before the next case begins."
  [{:keys [handler state socket] :as conn}]
  (vswap! (:flags socket) bit-or (long tcp/CLOSED))
  (try (handler @state nil) (catch :default _ nil))
  (await-handler-tasks! conn)
  nil)

(defn ring-conn
  "Convenience: a connection running `ring-handler` (a plain 1-arity Ring
  handler) with `default-opts` merged with `opts`. Pair every call with
  [[close-conn!]]."
  ([ring-handler] (ring-conn ring-handler {}))
  ([ring-handler opts]
   (let [opts (merge default-opts opts)
         tasks (atom {:submitted 0 :started 0 :completed 0 :rejected 0
                      :active 0 :max-active 0})
         executor (tracking-executor (or (:executor opts) shared-executor) tasks)
         opts (assoc opts :executor executor)]
     (assoc (connect (protocol/tcp-handler (sync-ring-handler ring-handler) opts)
                     opts)
            :handler-tasks tasks))))
