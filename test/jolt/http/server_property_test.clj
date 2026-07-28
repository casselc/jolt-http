(ns jolt.http.server-property-test
  "Generative properties driven over real loopback TCP.

  The in-process properties in jolt.http.protocol-property-test cover the state
  machine exhaustively and cheaply, because they drive the handler through a
  `teensyp.server/Socket` fake. What that fake cannot cover is everything
  *below* the handler: the reactor's send loop and its EAGAIN handling, write
  credit accounting, the blocking channel push that applies backpressure to a
  real sender, and a genuine half-close. These properties exercise exactly
  those, so a divergence between the two layers shows up as one passing and the
  other failing.

  Fixture shape (see the jolt-hegel skill's guidance on sharing an expensive
  external service):

    - One server per property, started once around the whole `run-test!` call
      and kept alive until it returns, so generation, shrinking and final replay
      all run against it.
    - A fresh *connection* per case is the isolation unit: the parser state, the
      response buffer and the write queue are all created on accept, so every
      case starts from equivalent observable server state and shrinking stays
      sound.
    - The connection is closed in a `finally`, including on failing cases.
    - Cases end on protocol signals, not sleeps: the client half-closes and
      drains to a real EOF, bounded in time and bytes. Hitting a bound is a
      failure, not a retry.

  This namespace uses h/run-test! directly and counts failures, matching the
  framework-less style of jolt.http.server-test. Failures print the seed; replay
  with (parse-long seed) as :seed."
  (:require [clojure.string :as str]
            [hegel.core :as h]
            [hegel.generator :as g]
            [hegel.report :as report]
            [hegel.stateful :as hs]
            [jolt.http.body :as body]
            [jolt.http.hegel-support :as hegel-support]
            [jolt.http.http-model :as m]
            [jolt.http.server :as http]
            [teensyp.ffi-net :as net]))

(def ^:private run-opts
  (hegel-support/run-opts
   {:test-cases 30 :database "" :verbosity :quiet}))

;; Out-of-band failure log. A libhegel-detected nondeterministic run comes back
;; with :failures [] and no counterexample, so the only record of what actually
;; went wrong in the case that did not reproduce is what was noted as it
;; happened.
(def ^:private events (atom []))

(defn- fail! [origin data]
  (swap! events conj [origin data])
  (throw (ex-info (str "property failed: " origin) (assoc data :hegel/origin origin))))

;; jolt-hegel's counting runner. It records pass/fail/error, counts both failed
;; results and thrown run errors, and never exits the process — which is what a
;; framework-less suite needs, because jolt.http.server-test aggregates one
;; failure total and exits at the end, so a single red property must not abort
;; the rest of the run.
;;
;; The reporter adds the fixture's out-of-band `events` log and preserves
;; jolt-hegel's bounded `:observed-failures`. A nondeterministic run can come
;; back without a counterexample, but the latter still records structured
;; throwable summaries from the cases that failed during exploration.
;; NOTE ON THE :error BRANCH. hegel.report/run! publishes a thrown setup,
;; health-check or engine error under :exception. This reporter destructured
;; :error, which is never present on that event, so every engine error printed
;; a bare "nil" and the actual exception was discarded. That is how a real
;; macOS x86-64 failure ("engine/setup error", 32 s) arrived with no
;; diagnosable content at all. Read :exception, and print its message, ex-data
;; and cause chain rather than relying on pr-str of an exception object.
(defn- describe-exception [e]
  (when e
    (let [data (ex-data e)]
      (cond-> {:message (ex-message e)}
        (some? data) (assoc :ex-data data)
        (some? (ex-cause e)) (assoc :cause (ex-message (ex-cause e)))))))

(defn- reporter [{:keys [type description result exception] :as ev}]
  (case type
    :pass  (println "ok   " description (str "(" (:valid-test-cases result) " cases)"))
    :fail  (do (println "FAIL " description)
               (println "   seed:    " (:seed result) " (replay with :seed (parse-long ...))")
               (when (:flaky? result)
                 (println "   flaky:    true — fix case isolation/timing before trusting the counterexample"))
               (when (:error result) (println "   error:   " (pr-str (:error result))))
               (println "   failures:" (pr-str (:n-failures result)))
               (println "   detail:  " (pr-str (:failures result)))
               (println "   throwables:" (pr-str (:observed-failures result)))
               (println "   observed:" (pr-str (frequencies (map first @events))))
               (println "   first:   " (pr-str (first @events))))
    :error (do (println "FAIL " description "(engine/setup error)")
               (println "   exception:" (pr-str (describe-exception exception)))
               (println "   raw:      " (pr-str exception))
               (println "   observed: " (pr-str (frequencies (map first @events))))
               (println "   first:    " (pr-str (first @events))))
    (println (pr-str ev)))
  (reset! events [])
  (flush))

(def ^:private runner (report/counting-runner {:reporter reporter}))

(defn failure-count [] (report/failure-count runner))

(defn run-count
  "How many property thunks this runner has actually executed.

  Exposed so the suite can refuse to report success when JOLT_HEGEL_REQUIRED is
  set but nothing generative ran. A zero failure count is not evidence of
  coverage: it is also what a group that never executed reports."
  []
  (report/run-count runner))

(defn- guarded
  "Run one complete property through the counting runner, and report its wall
  time. jolt block-buffers stdout when it is redirected, so without the flush in
  the reporter a slow or wedged property is indistinguishable from a silent run
  — the same reason jolt.http.server-test writes a progress file."
  [label f]
  (let [start (System/currentTimeMillis)]
    (try (report/run! runner label f)
         (finally
           (println (str "      (" (- (System/currentTimeMillis) start) " ms)"))
           (flush)))))

;; --- server lifecycle ------------------------------------------------------

(defn- with-server
  "Start one server around a whole run-test! call and keep it alive until the
  call returns — shrinking and final replay both need it."
  [opts f]
  (let [srv  (apply http/run-server (:handler opts)
                    (apply concat (merge {:port 0 :reuse-address? true
                                          :error-logger (fn [_])}
                                         (dissoc opts :handler))))
        port (:port srv)]
    (try (f port) (finally (http/stop-server srv)))))

;; --- client ----------------------------------------------------------------

(defn- connection-reset?
  "True for the terminal signal a peer that closed with unread request bytes
  still buffered delivers to this side.

  jolt.net maps both ECONNRESET and EPIPE (and Windows' WSAECONNRESET, 10054)
  onto the single :connection-reset kind, so one predicate covers every
  platform's version of \"the peer is gone\"."
  [e]
  (= :connection-reset (:jolt.net/kind (ex-data e))))

;; A reset is a legitimate terminal signal here, not a property failure, and it
;; is far more likely on Windows than on POSIX. The oversize property is the
;; clear case: the server answers 414/431 and closes while the rest of the
;; oversized request is still in flight. POSIX loopback usually still lets the
;; client observe a clean EOF; Windows delivers WSAECONNRESET to whichever call
;; is in flight -- the remaining send, the half-close, or the read.
;;
;; jolt.http.server-test already encodes exactly this reasoning in its
;; recv-until-eof ("the response bytes received before that terminal signal
;; remain valid and must still be checked"); the property harness did not, so on
;; native Windows the raised exception escaped `fail!` entirely and libhegel saw
;; a case that threw once and passed on replay -- reported as a flaky test with
;; no counterexample and an empty out-of-band event log.
;;
;; Swallowing the reset does NOT weaken any oracle. Every caller still has to
;; satisfy its own `done?` over the bytes actually received; a reset that
;; arrives before the expected response still fails the property, now as a
;; specific "no-response"/"timeout" origin rather than as an unattributed throw.
(defn- send-through!
  "Send every chunk and half-close. Returns true normally, false once the peer
  has reset -- there is nothing left to send to."
  [fd chunks]
  (try
    (doseq [c chunks] (net/client-send-all fd (m/->ba c)))
    (net/shutdown-write! fd)
    true
    (catch :default e
      (if (connection-reset? e) false (throw e)))))

(defn- recv-chunk!
  "One bounded receive. Returns the bytes, or nil for EOF *or* a peer reset."
  [fd n]
  (try (net/client-recv fd n)
       (catch :default e
         (if (connection-reset? e) nil (throw e)))))

(defn- recv-until-eof
  "Drain to a real EOF, bounded in time and bytes. Reaching either bound is a
  failure of the property, never a retry."
  [fd max-bytes timeout-ms]
  (let [r (deref (future (loop [acc [] total 0]
                           (if (> total (long max-bytes))
                             :overrun
                             (if-let [b (recv-chunk! fd 16384)]
                               (recur (conj acc b) (+ total (alength b)))
                               acc))))
                 timeout-ms :timeout)]
    (cond
      (= r :timeout) :timeout
      (= r :overrun) :overrun
      :else (vec (mapcat m/->octets r)))))

(defn- read-into!
  "Accumulate bytes into `acc` until `done?` holds over its contents, or the
  deadline passes.

  Returns :done when `done?` was satisfied, :peer-closed when the peer ended
  the connection (clean EOF or reset) with `done?` still unsatisfied, and
  :timeout when the deadline expired with the connection still open.

  Those last two used to be conflated as a single false, which made a
  connection the server terminated in 79 ms indistinguishable from a server
  that never answered inside 8 s. They are completely different observations:
  one is a legal transport outcome for a request the server is entitled to
  reject and close on, the other is a liveness failure. Only a caller that
  knows which of the two its scenario permits can judge it.

  ONE future around the whole receive loop, deliberately. Wrapping each
  individual recv in its own short-timeout future looks equivalent and is not:
  an abandoned recv stays blocked on the same fd, and when bytes finally arrive
  it consumes them while `acc` — which only the live reader appends to — stays
  empty. Two live readers on one fd is a lost response, and it showed up as an
  intermittent \"the server never answered\" timeout in properties that were
  testing something else entirely.

  Used by the keep-alive properties, where there is no EOF to wait for: the
  server holds the connection open for the next request, so the stopping
  condition has to come from the response framing itself."
  [fd acc done? timeout-ms]
  (if (done? @acc)
    :done
    (deref (future
             (loop []
               (if (done? @acc)
                 :done
                 (if-let [b (recv-chunk! fd 16384)]
                   (do (swap! acc into (m/->octets b)) (recur))
                   ;; EOF or peer reset: nothing more is coming, so what has
                   ;; arrived is the final answer.
                   (if (done? @acc) :done :peer-closed)))))
           timeout-ms :timeout)))

(defn- n-responses? [n heads]
  (fn [octets] (>= (count (:responses (m/read-responses octets heads))) n)))

(defn- exchange
  "One case: connect, send `chunks` in order, half-close, then read until the
  expected responses have arrived. The connection is closed in a finally,
  including when the property throws.

  The stopping condition is the *response framing*, not EOF. Draining to EOF
  would make every one of these properties depend on the separate connection
  release contract. That contract now has direct coverage, but it still should
  not be smeared across every property as a second, unrelated oracle."
  ([port chunks] (exchange port chunks (n-responses? 1 nil) 8000))
  ([port chunks done?] (exchange port chunks done? 8000))
  ([port chunks done? timeout-ms]
   (let [fd  (net/connect-loopback port)
         acc (atom [])]
     (try
       (send-through! fd chunks)
       (case (read-into! fd acc done? timeout-ms)
         :done @acc
         :peer-closed :peer-closed
         :timeout :timeout)
       (finally (net/close! fd))))))

(defn- check-drain!
  "Fail unless `got` is real response bytes.

  :peer-closed is a failure by default and only by default: a scenario in which
  the server is entitled to terminate the connection instead of answering must
  say so explicitly at its own call site, so that permission is never granted
  silently to a property that did not intend it."
  [origin got data]
  (when (= got :timeout) (fail! (str origin "/timeout") data))
  (when (= got :peer-closed) (fail! (str origin "/peer-closed") data))
  (when (= got :overrun) (fail! (str origin "/overrun") data))
  got)

(defn- repeatedly-chunk-sizes
  "Chunk sizes for a large generated body. Drawn rather than fixed so the chunk
  boundaries are part of what is explored, but bounded so a 120KB body does not
  become thousands of one-byte chunks."
  [payload]
  (h/draw! (g/vector {:min-size 1 :max-size 12}
                     (g/integer 1 (max 1 (quot (count payload) 4))))))

;; --- handlers --------------------------------------------------------------

(defn- hello-handler [_]
  {:status 200 :headers {"Content-Type" "text/plain"} :body "Hello World"})

(defn- path-handler [req]
  {:status 200 :headers {} :body (str (:uri req))})

(defn- fnv1a
  "FNV-1a over unsigned octets. A digest lets a property compare a 120KB body
  without pr-str'ing a 120000-element vector into the response and read-string
  ing it back on the client — which costs far more than the transfer under test
  and made the property look like a hang."
  [octets]
  (reduce (fn [h b] (bit-and (* (bit-xor (long h) (long b)) 16777619) 0xFFFFFFFF))
          2166136261 octets))

(defn- digest-handler [req]
  (let [bs (body/body-bytes (:body req))]
    {:status 200 :headers {"Content-Type" "text/plain"}
     :body (str (alength bs) ":" (fnv1a (m/->octets bs)))}))

(defn- body-echo-handler
  "Answers with the request body rendered as unsigned octets, so an arbitrary
  binary body can be compared exactly rather than through a UTF-8 round trip."
  [req]
  {:status 200 :headers {"Content-Type" "text/plain"}
   :body (pr-str (m/->octets (body/body-bytes (:body req))))})

;; Per-case response plan. Safe as a shared atom because cases run one
;; connection at a time and it is set before connect and read inside the
;; handler.
(def ^:private response-plan (atom {:body "" :kind :string}))

(defn- planned-response-handler [_]
  (let [{:keys [body kind]} @response-plan]
    {:status 200
     :headers {"Content-Type" "text/plain"}
     :body (case kind
             :string body
             :bytes  (.getBytes ^String body "UTF-8")
             :seq    (vec (map #(apply str %) (partition-all 997 body)))
             body)}))

;; --- 1. request framing survives real TCP write boundaries -----------------

(defn- prop-request-framing-over-tcp []
  (with-server {:handler body-echo-handler :read-buffer-size 512}
    (fn [port]
      (guarded
       "request framing is invariant under real TCP write boundaries"
       (fn []
         (h/run-test!
          (assoc run-opts :name "server/request-framing")
          (fn [_]
            (g/let [{:keys [bytes model]} (m/draw-request!)
                    chunks (g/chunkings bytes)]
              (let [got (exchange port chunks)]
                (h/fprn :request-len (count bytes) :chunk-sizes (mapv count chunks))
                (check-drain! "server/request-framing" got
                              {:request (m/octets->str (take 120 bytes))})
                (let [{:keys [responses error trailing]} (m/read-responses got)]
                  (when error
                    (fail! "server/request-framing/malformed"
                           {:error error :wire (m/octets->str (take 200 got))}))
                  (when (seq trailing)
                    (fail! "server/request-framing/trailing" {:n (count trailing)}))
                  (when-not (= 1 (count responses))
                    (fail! "server/request-framing/count" {:n (count responses)}))
                  (let [r (first responses)]
                    (when-not (= 200 (:status r))
                      (fail! "server/request-framing/status" {:status (:status r)}))
                    (when-not (= (:body model) (read-string (m/body-str r)))
                      (fail! "server/request-framing/body-mismatch"
                             {:want-len (count (:body model))})))))))))))))

;; --- 2. response bodies of every type, at sizes that straddle the buffers ---

(defn- prop-response-body-round-trip []
  (with-server {:handler planned-response-handler
                :response-buffer-size 4096
                :write-buffer-size (* 4 1024 1024)}
    (fn [port]
      (guarded
       "a response body of any type and size arrives intact"
       (fn []
         (h/run-test!
          (assoc run-opts :test-cases 25 :name "server/response-body")
          (fn [_]
            (g/let [n    (g/integer 0 200000)
                    kind (g/sampled-from [:string :bytes :seq])]
              ;; A position-dependent pattern: any duplication, truncation or
              ;; reordering inside run-writer's flush loop or the reactor's send
              ;; loop shows up as a content mismatch, not just a length one.
              (let [payload (apply str (map (fn [i] (char (+ 33 (mod i 90)))) (range n)))]
                (reset! response-plan {:body payload :kind kind})
                (let [got (exchange port [(m/ascii (str "GET / HTTP/1.1\r\nHost: h\r\n"
                                                        "Connection: close\r\n\r\n"))])]
                  (h/fprn :size n :kind kind)
                  (check-drain! "server/response-body" got {:size n :kind kind})
                  (let [{:keys [responses error trailing]} (m/read-responses got)]
                    (when error
                      (fail! "server/response-body/malformed" {:error error :size n :kind kind}))
                    (when (seq trailing)
                      (fail! "server/response-body/trailing" {:n (count trailing)}))
                    (let [r (first responses)]
                      ;; Framing must be self-consistent: a declared
                      ;; Content-Length that does not match the bytes sent
                      ;; desynchronises a keep-alive connection.
                      (when-some [cl (m/header r "Content-Length")]
                        (when-not (= (parse-long cl) (count (:body r)))
                          (fail! "server/response-body/length-mismatch"
                                 {:declared (parse-long cl) :actual (count (:body r))})))
                      (when-not (= payload (m/body-str r))
                        (fail! "server/response-body/content"
                               {:size n :kind kind :got-len (count (:body r))}))))))))))))))

;; --- 3. request bodies large enough to exercise backpressure ---------------

;; This property's observer bound is the shared 8 s default again, and its
;; TooSlow suppression is gone. Both were workarounds for a jolt-tcp/jolt-net
;; defect that has since been fixed upstream; this comment records why they
;; existed and why they no longer need to.
;;
;; The fixture (1 KB read buffer, stream queue of 2, bodies up to 120 KB) drives
;; up to ~118 reactor read cycles per case, an order of magnitude more than any
;; other property here. Each of those cycles was exposed to a reactor re-arm
;; latency: when a handler arity completed in the window after the reactor had
;; drained its pending set but before it re-entered `net/await-ready`, the
;; publication was consumed as though the reactor had already seen it and the
;; connection was only re-serviced on the next tick of that wait, which jolt.net
;; caps at 1000 ms. Observed latency for one case was therefore
;; (real work) + k x 1000 ms.
;;
;; That was measured, not assumed; the raw evidence is in
;; docs/runtime/windows-http-runtime.md. On Linux, this exact fixture over 15
;; consecutive 93388-byte exchanges produced 183/2188/2216/3222/4219/4245/4246/
;; 4248/6228/6234/6251/7245/7301/8257/8296 ms -- a base cost plus a whole number
;; of 1 s steps, with every body delivered whole and correct and ZERO wedges.
;; The same stall reproduced with jolt-tcp alone and no HTTP layer, and vanished
;; when the read buffer was widened, which placed the cause below this
;; repository. It was never observed on native Windows x86-64.
;;
;; The cause was jolt.net's `await-ready` treating its own entry as the
;; stale/fresh boundary for wake epochs, so a publication landing between a
;; consumer's read of producer state and its await was discarded as stale. The
;; fix is a caller-supplied monotonic wake cursor (jolt-net
;; `poller/wake-cursor`, jolt-tcp sampling it before `process-pending!`); see
;; jolt-net's docs/proofs/socket-invariants.md and jolt-tcp's
;; docs/proofs/reactor-lifecycle-invariants.md section 7.
;;
;; Two workarounds were removed once the pin moved to the fixed jolt-tcp:
;;
;;   - The per-case bound had been widened from 8 s to 45 s, because the 8 s
;;     bound was not measuring liveness but sampling the tail of a lower-layer
;;     latency distribution -- which is why it produced a libhegel "flaky"
;;     classification (seed 9157075391771664454, case
;;     {:size 93388, :framing :content-length}) rather than a reproducible
;;     failure.
;;   - TooSlow was suppressed for this property, because libhegel's aggregate
;;     health check has a 30 s threshold for the whole run and aborted before
;;     any per-case bound could be reached. That is what actually failed on
;;     macOS x86-64: "only 8 valid inputs after 38.082074097s (threshold 30s)",
;;     with 15.2/16.3/26.4/26.5/27.4/31.8 s passing and 32.4 s failing.
;;
;; Replaying the witness seed against the fixed stack, this property completes
;; its 20 cases in 2584/2620/2768 ms -- roughly 130 ms per case against the 8 s
;; per-case bound, and about an eleventh of the 30 s TooSlow threshold. Both
;; workarounds are therefore restored to the defaults rather than kept as
;; permanent allowances for a bug that no longer exists.
;;
;; The bound remains a LIVENESS bound: progress is bounded and every byte still
;; has to arrive intact, so a case that exhausts it has genuinely stopped and
;; that is still a failure. Widening the buffers to dodge latency would instead
;; delete the backpressure coverage this property exists for.
(def ^:private backpressure-timeout-ms 8000)

(defn- prop-request-body-backpressure []
  (with-server {:handler digest-handler :read-buffer-size 1024 :stream-queue-size 2}
    (fn [port]
      (guarded
       "a large request body is delivered whole under backpressure"
       (fn []
         (h/run-test!
          (assoc run-opts :test-cases 20 :name "server/request-backpressure")
          (fn [_]
            ;; A small read buffer and a short stream queue mean the parser's
            ;; blocking channel put parks, the socket stays WORKING and the
            ;; reactor stops reading — TCP then flow-controls the sender. That
            ;; path is only reachable with a body far larger than the buffers.
            (g/let [n      (g/integer 4096 120000)
                    framing (g/sampled-from [:content-length :chunked])]
              (let [payload (mapv (fn [i] (mod (* 7 i) 256)) (range n))
                    sizes   (repeatedly-chunk-sizes payload)
                    raw     (m/render-request {:method "POST" :target "/x"
                                               :field-lines [] :body payload
                                               :body-framing (if (= framing :chunked)
                                                               [:chunked sizes]
                                                               :content-length)})
                    got     (exchange port [raw] (n-responses? 1 nil)
                                      backpressure-timeout-ms)]
                (h/fprn :body-size n :framing framing)
                (check-drain! "server/request-backpressure" got {:size n :framing framing})
                (let [{:keys [responses error]} (m/read-responses got)]
                  (when error
                    (fail! "server/request-backpressure/malformed" {:error error :size n}))
                  (let [r (first responses)]
                    (when-not (= 200 (:status r))
                      (fail! "server/request-backpressure/status" {:status (:status r)}))
                    (when-not (= (str n ":" (fnv1a payload)) (m/body-str r))
                      (fail! "server/request-backpressure/mismatch"
                             {:size n :framing framing :got (m/body-str r)})))))))))))))

;; --- 4. pipelining and keep-alive over real transport ----------------------

(defn- prop-pipelining-over-tcp []
  (with-server {:handler path-handler :read-buffer-size 512}
    (fn [port]
      (guarded
       "pipelined requests are all answered, in order, over real TCP"
       (fn []
         (h/run-test!
          (assoc run-opts :name "server/pipelining")
          (fn [_]
            (g/let [n      (g/integer 1 6)
                    paths  (g/vector {:size n} m/path-gen)
                    stream (vec (mapcat (fn [p]
                                          (m/ascii (str "GET " p " HTTP/1.1\r\nHost: h\r\n\r\n")))
                                        paths))
                    chunks (g/chunkings stream)]
              (let [got (exchange port chunks (n-responses? n nil))]
                (h/fprn :paths paths :chunk-sizes (mapv count chunks))
                (check-drain! "server/pipelining" got {:paths paths})
                (let [{:keys [responses error trailing]} (m/read-responses got)]
                  (when error
                    (fail! "server/pipelining/malformed" {:error error :paths paths}))
                  (when (seq trailing)
                    (fail! "server/pipelining/trailing" {:n (count trailing)}))
                  (when-not (= n (count responses))
                    (fail! "server/pipelining/count"
                           {:want n :got (count responses) :paths paths}))
                  (when-not (= paths (mapv m/body-str responses))
                    (fail! "server/pipelining/out-of-order"
                           {:want paths :got (mapv m/body-str responses)}))))))))))))

;; --- 5. half-close is always answered --------------------------------------

(defn- prop-half-close-answered []
  (with-server {:handler body-echo-handler}
    (fn [port]
      (guarded
       "a client that half-closes after sending still gets its response"
       (fn []
         (h/run-test!
          (assoc run-opts :name "server/half-close")
          (fn [_]
            ;; The h1spec pattern: send, shutdown(SHUT_WR), read to EOF. Closing
            ;; on EOF would discard the response to the request just sent, and
            ;; the streaming-body path is the hard case because the handler runs
            ;; on another thread — at EOF the reactor sees no work in flight.
            (g/let [{:keys [bytes model]} (m/draw-request!)]
              (let [got (exchange port [bytes])]
                (h/fprn :request (m/octets->str (take 100 bytes)))
                (check-drain! "server/half-close" got
                              {:request (m/octets->str (take 100 bytes))})
                (let [{:keys [responses error]} (m/read-responses got)]
                  (when error
                    (fail! "server/half-close/malformed" {:error error}))
                  (when-not (= 1 (count responses))
                    (fail! "server/half-close/count" {:n (count responses)}))
                  (when-not (= (:body model) (read-string (m/body-str (first responses))))
                    (fail! "server/half-close/body" {:want-len (count (:body model))}))))))))))))

;; --- 6. Expect: 100-continue ----------------------------------------------

(defn- prop-expect-continue []
  (with-server {:handler body-echo-handler}
    (fn [port]
      (guarded
       "Expect: 100-continue yields an interim 100, then the body is accepted"
       (fn []
         (h/run-test!
          (assoc run-opts :test-cases 20 :name "server/expect-continue")
          (fn [_]
            (g/let [payload (g/vector {:min-size 1 :max-size 4096} (g/octet))]
              (let [fd  (net/connect-loopback port)
                    acc (atom [])]
                (try
                  (net/client-send-all
                   fd (m/->ba (m/ascii (str "POST / HTTP/1.1\r\nHost: h\r\n"
                                            "Content-Length: " (count payload) "\r\n"
                                            "Expect: 100-continue\r\n\r\n"))))
                  ;; The client must not send the body until it has permission;
                  ;; without the interim response it stalls until its own
                  ;; timeout (curl does this for bodies over 1KB).
                  (let [outcome (read-into! fd acc
                                            (fn [o] (seq (:responses (m/read-responses o))))
                                            4000)]
                    (when-not (= :done outcome)
                      (fail! "server/expect-continue/no-interim"
                             {:size (count payload) :outcome outcome})))
                  (let [interim (first (:responses (m/read-responses @acc)))]
                    (when-not (= 100 (:status interim))
                      (fail! "server/expect-continue/not-100" {:status (:status interim)})))
                  (net/client-send-all fd (m/->ba payload))
                  (net/shutdown-write! fd)
                  (let [outcome (read-into! fd acc (n-responses? 2 nil) 8000)]
                    (when-not (= :done outcome)
                      (fail! "server/expect-continue/no-final"
                             {:size (count payload) :outcome outcome})))
                  (let [final (second (:responses (m/read-responses @acc)))]
                    (h/fprn :size (count payload))
                    (when-not (= payload (read-string (m/body-str final)))
                      (fail! "server/expect-continue/body" {:size (count payload)})))
                  (finally (net/close! fd))))))))))))

;; --- 7. the oversize boundary ---------------------------------------------

(defn- prop-oversize-boundary []
  ;; One server, one read-buffer size, for the whole run. Starting a server per
  ;; case would break the fixture contract the other properties keep: shrinking
  ;; re-runs the body, and a shrink whose cases each bind a different port is
  ;; not shrinking against equivalent server state.
  (let [buf-size 1024]
    (with-server {:handler hello-handler :read-buffer-size buf-size}
      (fn [port]
        (guarded
         "a request line or header just under the read buffer is served, just over is refused"
         (fn []
           (h/run-test!
            (assoc run-opts :test-cases 25 :name "server/oversize")
            (fn [_]
              ;; The bound is `(< (buf/limit buffer) max-buffer-size)`, so the
              ;; interesting inputs are the ones either side of it — not one
              ;; arbitrarily huge URI. The generator straddles the boundary
              ;; directly, and integer shrinking walks straight to it.
              (g/let [pad-len (g/integer 1 (* 2 buf-size))
                      in-uri? (g/boolean)]
                (let [pad  (apply str (repeat pad-len "x"))
                      raw  (if in-uri?
                             (str "GET /" pad " HTTP/1.1\r\nHost: h\r\n\r\n")
                             (str "GET / HTTP/1.1\r\nHost: h\r\nX-Big: " pad "\r\n\r\n"))
                      over? (>= (count raw) buf-size)
                      want  (cond (not over?) 200
                                  in-uri?     414
                                  :else       431)
                      got   (exchange port [(m/ascii raw)])]
                  (h/fprn :pad-len pad-len :in-uri? in-uri? :raw-len (count raw) :over? over?)
                  ;; An over-limit request is the one case in this suite where
                  ;; the server legitimately rejects and closes while the rest
                  ;; of the request is still in flight. TCP then answers the
                  ;; unread inbound data with a RST, and a RST discards whatever
                  ;; is still sitting unread in the client's receive queue --
                  ;; including, sometimes, the 414/431 the server did send.
                  ;;
                  ;; That is a transport fact, not an HTTP conformance fact, and
                  ;; it is timing-dependent: native Windows x86-64 exposed it
                  ;; first (seed 305835134111915440, pad-len 2048), and hosted
                  ;; macOS x86-64 later produced one nondeterministic outcome at
                  ;; the same boundary. jolt.http.server-test's
                  ;; recv-until-eof already encodes exactly this reasoning for
                  ;; the acceptance scenario; the property harness did not, so
                  ;; the case surfaced as a libhegel "flaky" verdict.
                  ;;
                  ;; Permit it here, and ONLY here, and only for over? cases --
                  ;; an under-limit request is answered on a connection the
                  ;; server keeps open, so it has no reset race and must always
                  ;; produce its 200. Every other oracle is untouched: if a
                  ;; response does arrive it must still be well-formed and carry
                  ;; exactly the expected status.
                  (if (and over? (= got :peer-closed))
                    (h/fprn :oversize-terminated-without-response true)
                    (do
                      (check-drain! "server/oversize" got
                                    {:pad-len pad-len :over? over?})
                      (let [{:keys [responses error]} (m/read-responses got)]
                        (when error
                          (fail! "server/oversize/malformed" {:error error :pad-len pad-len}))
                        (when (empty? responses)
                          (fail! "server/oversize/no-response" {:pad-len pad-len :raw-len (count raw)}))
                        (when-not (= want (:status (first responses)))
                          (fail! "server/oversize/status"
                                 {:want want :got (:status (first responses))
                                  :raw-len (count raw) :over? over? :in-uri? in-uri?})))))))))))))))

;; --- 8. stateful connection model over real transport ----------------------

(defn- prop-connection-stateful []
  (with-server {:handler path-handler :read-buffer-size 1024}
    (fn [port]
      (guarded
       "a generated sequence of requests on one connection stays in sync"
       (fn []
         (h/run-test!
          (assoc run-opts :test-cases 25 :name "server/stateful")
          (fn [_]
            ;; A real connection is a sequence: requests interleaved with
            ;; partial writes and HEADs, then a half-close. The parser carries
            ;; state between them, and this checks a model of what must have
            ;; been answered, in order, at every step.
            (let [fd  (net/connect-loopback port)
                  acc (atom [])]
              (try
                (hs/run!
                 {:initial-state {:fd fd :acc acc :pending [] :drained []
                                  :partial nil :eof? false}
                  :rules
                  [(hs/rule :send-request
                            {:precondition (fn [{:keys [partial eof?]}]
                                             (and (nil? partial) (not eof?)))}
                            (fn [{:keys [fd pending] :as state}]
                              (let [path (h/draw! m/path-gen)]
                                (net/client-send-all
                                 fd (m/->ba (m/ascii (str "GET " path " HTTP/1.1\r\nHost: h\r\n\r\n"))))
                                (assoc state :pending
                                       (conj pending {:head? false :body path})))))

                   (hs/rule :send-head
                            {:precondition (fn [{:keys [partial eof?]}]
                                             (and (nil? partial) (not eof?)))}
                            (fn [{:keys [fd pending] :as state}]
                              (let [path (h/draw! m/path-gen)]
                                (net/client-send-all
                                 fd (m/->ba (m/ascii (str "HEAD " path " HTTP/1.1\r\nHost: h\r\n\r\n"))))
                                (assoc state :pending
                                       (conj pending {:head? true :body ""})))))

                   ;; half a request now, the rest later — the parser must hold
                   ;; its state across the gap
                   (hs/rule :send-partial
                            {:precondition (fn [{:keys [partial eof?]}]
                                             (and (nil? partial) (not eof?)))}
                            (fn [{:keys [fd] :as state}]
                              (let [path (h/draw! m/path-gen)
                                    raw  (m/ascii (str "GET " path " HTTP/1.1\r\nHost: h\r\n\r\n"))
                                    cut  (h/draw! (g/integer 1 (dec (count raw))))]
                                (net/client-send-all fd (m/->ba (subvec raw 0 cut)))
                                (assoc state :partial {:rest (subvec raw cut) :path path}))))

                   (hs/rule :finish-partial
                            {:precondition (fn [{:keys [partial eof?]}]
                                             (and (some? partial) (not eof?)))}
                            (fn [{:keys [fd partial pending] :as state}]
                              (net/client-send-all fd (m/->ba (:rest partial)))
                              (assoc state :partial nil
                                     :pending (conj pending {:head? false
                                                             :body (:path partial)}))))

                   (hs/rule :half-close
                            {:precondition (fn [{:keys [eof?]}] (not eof?))}
                            (fn [{:keys [fd] :as state}]
                              (net/shutdown-write! fd)
                              (assoc state :eof? true)))

                   (hs/rule :drain-one
                            {:precondition (fn [{:keys [pending]}] (seq pending))}
                            (fn [{:keys [fd acc pending drained] :as state}]
                              (let [want  (count drained)
                                    heads (map :head? (concat drained pending))]
                                (when-not (= :done (read-into! fd acc (n-responses? (inc want) heads) 15000))
                                  (fail! "server/stateful/missing-response"
                                         {:expected (inc want)
                                          :got (count (:responses (m/read-responses @acc heads)))}))
                                (let [rs   (:responses (m/read-responses @acc heads))
                                      r    (nth rs want)
                                      spec (first pending)]
                                  (when-not (= 200 (:status r))
                                    (fail! "server/stateful/status" {:status (:status r)}))
                                  (when-not (= (:body spec) (m/body-str r))
                                    (fail! "server/stateful/out-of-order"
                                           {:want (:body spec) :got (m/body-str r)}))
                                  (assoc state
                                         :pending (vec (rest pending))
                                         :drained (conj drained spec))))))]

                  :invariants
                  [;; Never more answers on the wire than requests fully sent.
                   (hs/invariant :no-extra-responses
                                 (fn [{:keys [acc pending drained]}]
                                   (<= (m/status-lines @acc)
                                       (+ (count pending) (count drained)))))
                   ;; Whatever has arrived so far is either well framed or
                   ;; simply incomplete — never malformed.
                   (hs/invariant :stream-stays-framed
                                 (fn [{:keys [acc pending drained]}]
                                   (let [heads (map :head? (concat drained pending))
                                         r     (m/read-responses @acc heads)]
                                     (or (nil? (:error r))
                                         (contains? #{:incomplete-headers :incomplete-body
                                                      :incomplete-chunked :no-status-line}
                                                    (:error r))))))]})
                (finally (net/close! fd)))))))))))

;; --- runner ----------------------------------------------------------------

(defn run-properties!
  "Run the gating loopback properties. Returns the number of failed properties."
  []
  (println "\n-- jolt-http loopback generative properties (jolt-hegel) --")
  (prop-request-framing-over-tcp)
  (prop-response-body-round-trip)
  (prop-request-body-backpressure)
  (prop-pipelining-over-tcp)
  (prop-half-close-answered)
  (prop-expect-continue)
  (prop-oversize-boundary)
  (prop-connection-stateful)
  (failure-count))

(defn run-known-flaky-properties!
  "Compatibility helper for focused stress of the two formerly quarantined
  churn properties. Both now run in the default gate.

  Their timeout chain was eliminated by EOF-notification ordering, atomic flags,
  EOF-aware resume, a single bounded client reader, and finally reactor-owned
  close plus `(fd, generation)` pending identities. A separate 64-request
  pipeline witness also removed redundant inline resume events that could fill
  the bounded control queue. The pair completed 30 consecutive stress runs
  cleanly before being promoted."
  []
  (println "\n-- jolt-http loopback churn properties --")
  (prop-request-framing-over-tcp)
  (prop-connection-stateful)
  (failure-count))
