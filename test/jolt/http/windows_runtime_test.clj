(ns jolt.http.windows-runtime-test
  "Native Windows x86-64 HTTP runtime gate for jolt-http.

  Replaces the former portable-only Windows coverage, whose Windows claim was
  limited to the pure date/status/protocol layers because jolt.net had no
  Windows readiness backend. The pinned jolt-tcp W6A revision ships a reviewed
  Windows backend and a public client, so this gate instead requires a real
  loopback HTTP server: a port-zero listen, real request/response, keep-alive
  and pipelining, a request body large enough to force reader backpressure, a
  half-close that is still answered, and a deterministic stop.

  Deliberately dependency-free. It requires only the production namespaces,
  jolt-tcp's public client and `clojure.test` — never jolt-hegel and never an
  installed native artifact — so Windows HTTP socket coverage cannot silently
  disappear because an optional test dependency failed to resolve or install.
  The Hegel-required suite is a separate lane.

  Layering: production jolt-http reaches the network only through jolt-tcp.
  This gate drives the client side through `teensyp.client`, jolt-tcp's public
  API, and touches `jolt.net` only to assert that a readiness poller really
  opens on this target. Nothing here reaches into jolt.ffi.

  Every wait is bounded. Each blocking client call carries an explicit
  operation deadline whose expiry throws, and each scenario additionally runs
  under a watchdog that turns a wedged operation into a failure rather than a
  hang. No sleep is used as a correctness oracle: the oracles are the response
  framing itself and bounded derefs."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [jolt.http.body :as body]
            [jolt.http.server :as http]
            [jolt.net :as net]
            [teensyp.client :as client]))

;; --- bounded synchronization ------------------------------------------------

(def ^:private watchdog-ms 20000)

;; The request-body backpressure scenario deliberately runs a small read buffer
;; and a short stream queue so the parser's blocking channel put parks, the
;; connection stays WORKING, and the reactor stops reading -- the only way to
;; reach the flow-controlled path. That fixture also maximizes the number of
;; reactor read cycles (a 93 KB body over a 1 KB buffer is ~92 of them), so it
;; is the scenario most sensitive to any per-cycle reactor latency.
;;
;; Measured on this target, it is not sensitive at all: the equivalent
;; generative property (20 cases over the same fixture) completes in ~1.4 s of
;; native Windows wall time. The POSIX-side re-arm latency recorded in
;; docs/runtime/windows-http-runtime.md is not observed here.
;;
;; This is therefore a LIVENESS bound with headroom for a loaded CI runner, not
;; a performance assertion. Exhausting it means the exchange stopped making
;; progress, which is a real failure.
(def ^:private backpressure-watchdog-ms 60000)

(defn- settled
  "Run `f` on another thread and return {:value v} or {:error e}, or ::watchdog
  if it did not finish within `ms`."
  ([f] (settled watchdog-ms f))
  ([ms f]
   (deref (future (try {:value (f)}
                       (catch :default e {:error e})))
          ms ::watchdog)))

(defn- err-of [outcome]
  (:err (ex-data (:error outcome))))

(defn- kind-of [outcome]
  (:jolt.net/kind (ex-data (:error outcome))))

(defn- classified?
  "True when a failure carries a classification from either boundary: teensyp's
  own :err, or jolt.net's transport :jolt.net/kind. A refused connect is
  reported by the latter, so requiring only :err would reject a correctly
  classified error."
  [outcome]
  (or (some? (err-of outcome)) (some? (kind-of outcome))))

(defn- utf8 ^bytes [^String s] (.getBytes s "UTF-8"))
(defn- ->str [^bytes b] (when b (String. b "UTF-8")))

;; --- a small, independent HTTP/1.1 response reader ---------------------------
;;
;; Written against RFC 9112 rather than derived from jolt.http.protocol: a gate
;; whose expected value is computed by the code under test proves nothing. It is
;; intentionally minimal -- the exhaustive reader lives in jolt.http.http-model,
;; which needs jolt-hegel and therefore cannot be used here.

(def ^:private hex-digits "0123456789abcdef")

(defn- ->hex
  "Lower-case hex for a non-negative chunk size. Written out rather than using
  Long/toHexString, which jolt does not provide."
  [n]
  (if (zero? (long n))
    "0"
    (loop [n (long n) out ""]
      (if (zero? n)
        out
        (recur (quot n 16)
               (str (nth hex-digits (rem n 16)) out))))))

(defn- split-head [^String s]
  (when-let [i (str/index-of s "\r\n\r\n")]
    [(subs s 0 i) (subs s (+ i 4))]))

(defn- parse-head [^String head]
  (let [[status-line & field-lines] (str/split-lines head)
        [_ version status] (re-find #"^(HTTP/1\.[01]) (\d{3})" (str status-line))]
    {:version version
     :status (some-> status parse-long)
     :headers (reduce (fn [m line]
                        (if-let [i (str/index-of line ":")]
                          (assoc m (str/lower-case (str/trim (subs line 0 i)))
                                 (str/trim (subs line (inc i))))
                          m))
                      {} field-lines)}))

(defn- read-chunked
  "Decode a chunked body. Returns [body remainder] or nil when incomplete."
  [^String s]
  (loop [s s acc ""]
    (if-let [i (str/index-of s "\r\n")]
      (let [size-line (subs s 0 i)
            size (try (Long/parseLong (str/trim (first (str/split size-line #";"))) 16)
                      (catch :default _ nil))]
        (cond
          (nil? size) nil
          (zero? size) (when-let [j (str/index-of (subs s (+ i 2)) "\r\n")]
                         [acc (subs s (+ i 2 j 2))])
          :else (let [start (+ i 2)
                      end (+ start size)]
                  (when (>= (count s) (+ end 2))
                    (recur (subs s (+ end 2)) (str acc (subs s start end)))))))
      nil)))

(defn- read-one-response
  "Parse one complete response off the front of `s`. Returns [response rest] or
  nil when `s` does not yet hold a whole message."
  [^String s head-request?]
  (when-let [[head tail] (split-head s)]
    (let [{:keys [status headers] :as parsed} (parse-head head)
          cl (some-> (get headers "content-length") parse-long)
          chunked? (= "chunked" (str/lower-case (get headers "transfer-encoding" "")))
          bodyless? (or head-request? (= 204 status) (= 304 status)
                        (and status (<= 100 status 199)))]
      (cond
        bodyless? [(assoc parsed :body "") tail]
        chunked? (when-let [[body remainder] (read-chunked tail)]
                   [(assoc parsed :body body) remainder])
        cl (when (>= (count tail) (long cl))
             [(assoc parsed :body (subs tail 0 cl)) (subs tail cl)])
        :else nil))))

(defn- read-responses
  "Parse as many complete responses as `s` holds. Returns {:responses [..]
  :rest \"..\"}."
  ([s] (read-responses s nil))
  ([s head-flags]
   (loop [s s i 0 out []]
     (if-let [[response remainder] (read-one-response s (boolean (nth head-flags i nil)))]
       (recur remainder (inc i) (conj out response))
       {:responses out :rest s}))))

(defn- n-responses?
  ([n] (n-responses? n nil))
  ([n head-flags]
   (fn [s] (>= (count (:responses (read-responses s head-flags))) (long n)))))

;; --- bounded client ---------------------------------------------------------

(defn- read-until
  "Accumulate from `connection` until `done?` holds over what has arrived, the
  peer signals EOF, or the absolute deadline lapses.

  The deadline is enforced twice on purpose: each `receive-at-most!` carries the
  remaining budget as an operation deadline (whose expiry throws), and the loop
  itself refuses to make another call once the budget is gone. Returns the
  accumulated text, or ::deadline / ::eof when `done?` was never satisfied."
  [connection done? deadline-ms]
  (let [end (+ (System/currentTimeMillis) (long deadline-ms))]
    (loop [acc ""]
      (cond
        (done? acc) acc
        (>= (System/currentTimeMillis) end) ::deadline
        :else
        (let [remaining (- end (System/currentTimeMillis))
              chunk (client/receive-at-most! connection 16384
                                             {:timeout-ms remaining})]
          (if chunk
            (recur (str acc (->str chunk)))
            (if (done? acc) acc ::eof)))))))

(defn- connect! [port]
  (client/connect "127.0.0.1" port {:connect-timeout-ms 5000 :no-delay? true}))

(defn- with-server*
  "Start a port-zero HTTP server, run `f` with the handle, and always stop it."
  [opts handler f]
  (let [server (apply http/run-server handler
                      (apply concat (merge {:port 0 :reuse-address? true
                                            :error-logger (fn [_])}
                                           opts)))]
    (try (f server)
         (finally (http/stop-server server)))))

(defn- exchange
  "Connect, send `text`, half-close, read until `done?`, always closing. This is
  the h1spec client pattern: send, shutdown(SHUT_WR), read the reply."
  ([port text done?] (exchange port text done? watchdog-ms))
  ([port text done? deadline-ms]
   (let [connection (connect! port)]
     (try
       (client/send-all! connection (utf8 text) {:timeout-ms deadline-ms})
       (client/shutdown-write! connection)
       (read-until connection done? deadline-ms)
       (finally (client/close! connection))))))

;; --- handlers ---------------------------------------------------------------

(defn- hello-handler [_]
  {:status 200 :headers {"Content-Type" "text/plain"} :body "Hello World"})

(defn- path-handler [request]
  {:status 200 :headers {"Content-Type" "text/plain"} :body (str (:uri request))})

(defn- fnv1a
  "FNV-1a over unsigned octets. A digest lets the gate compare a 96 KB body
  without shipping the whole body back through the response."
  [^bytes bs]
  (let [n (alength bs)]
    (loop [i 0 h (long 2166136261)]
      (if (>= i n)
        h
        (recur (inc i)
               (bit-and (* (bit-xor h (bit-and (long (aget bs i)) 0xFF)) 16777619)
                        0xFFFFFFFF))))))

(defn- digest-handler [request]
  (let [bs (body/body-bytes (:body request))]
    {:status 200 :headers {"Content-Type" "text/plain"}
     :body (str (alength bs) ":" (fnv1a bs))}))

;; --- contracts --------------------------------------------------------------

(deftest port-zero-listen-serves-a-real-request
  (with-server* {} hello-handler
    (fn [server]
      (testing "a port-zero listen reports the real bound port"
        (is (pos? (:port server))))
      (let [got (exchange (:port server)
                          "GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"
                          (n-responses? 1))]
        (testing "the request is answered over real loopback"
          (is (string? got) (str "expected a response, got " got))
          (when (string? got)
            (let [{:keys [responses rest]} (read-responses got)]
              (is (= 1 (count responses)))
              (is (= "" rest))
              (is (= 200 (:status (first responses))))
              (is (= "HTTP/1.1" (:version (first responses))))
              (is (= "Hello World" (:body (first responses))))
              (is (= "11" (get-in (first responses) [:headers "content-length"]))))))))))

(deftest keep-alive-serves-sequential-requests-on-one-connection
  (with-server* {} path-handler
    (fn [server]
      (let [connection (connect! (:port server))]
        (try
          (doseq [path ["/one" "/two" "/three"]]
            (testing (str "request " path " is answered on the reused connection")
              (client/send-all! connection
                                (utf8 (str "GET " path " HTTP/1.1\r\nHost: h\r\n\r\n"))
                                {:timeout-ms watchdog-ms})
              (let [got (read-until connection (n-responses? 1) watchdog-ms)]
                (is (string? got) (str "no response for " path ": " got))
                (when (string? got)
                  (let [{:keys [responses rest]} (read-responses got)]
                    (is (= 1 (count responses)))
                    (is (= "" rest) "the connection must not run ahead of framing")
                    (is (= 200 (:status (first responses))))
                    (is (= path (:body (first responses)))))))))
          (testing "the connection is still open after three keep-alive exchanges"
            (is (false? (client/closed? connection)))
            (is (= :open (:state (client/connection-info connection)))))
          (finally (client/close! connection)))))))

(deftest pipelined-requests-are-answered-in-order
  (with-server* {} path-handler
    (fn [server]
      (let [paths ["/a" "/bb" "/ccc" "/dddd"]
            text (apply str (map #(str "GET " % " HTTP/1.1\r\nHost: h\r\n\r\n") paths))
            got (exchange (:port server) text (n-responses? (count paths)))]
        (testing "every pipelined request is answered exactly once, in order"
          (is (string? got) (str "expected " (count paths) " responses, got " got))
          (when (string? got)
            (let [{:keys [responses rest]} (read-responses got)]
              (is (= (count paths) (count responses)))
              (is (= "" rest) "no trailing bytes may follow the last response")
              (is (every? #(= 200 (:status %)) responses))
              (is (= paths (mapv :body responses))))))))))

(deftest request-body-is-delivered-whole-under-backpressure
  ;; A small read buffer and a short stream queue mean the parser's blocking
  ;; channel put parks, the socket stays WORKING and the reactor stops reading,
  ;; so TCP flow-controls the sender. That path is only reachable with a body far
  ;; larger than the buffers.
  (with-server* {:read-buffer-size 1024 :stream-queue-size 2} digest-handler
    (fn [server]
      (doseq [framing [:content-length :chunked]]
        (testing (str "a large request body is delivered whole under "
                      "backpressure (" (name framing) ")")
          (let [n 93388
                payload (apply str (map #(char (+ 33 (mod % 90))) (range n)))
                expected (str n ":" (fnv1a (utf8 payload)))
                text (if (= framing :chunked)
                       (str "POST /x HTTP/1.1\r\nHost: h\r\n"
                            "Transfer-Encoding: chunked\r\n\r\n"
                            (apply str
                                   (map (fn [part]
                                          (str (->hex (count part))
                                               "\r\n" part "\r\n"))
                                        (map #(apply str %)
                                             (partition-all 4096 payload))))
                            "0\r\n\r\n")
                       (str "POST /x HTTP/1.1\r\nHost: h\r\n"
                            "Content-Length: " n "\r\n\r\n" payload))
                outcome (settled backpressure-watchdog-ms
                                 #(exchange (:port server) text (n-responses? 1)
                                            backpressure-watchdog-ms))]
            (is (not= ::watchdog outcome) "the exchange stopped making progress")
            (is (nil? (:error outcome))
                (str "exchange threw: " (some-> (:error outcome) ex-message)))
            (let [got (:value outcome)]
              (is (string? got) (str "expected a response, got " got))
              (when (string? got)
                (let [{:keys [responses]} (read-responses got)]
                  (is (= 1 (count responses)))
                  (is (= 200 (:status (first responses))))
                  (testing "every byte arrived, in order, exactly once"
                    (is (= expected (:body (first responses))))))))))))))

(deftest half-close-is-answered-and-then-reaches-eof
  (with-server* {} hello-handler
    (fn [server]
      (let [connection (connect! (:port server))]
        (try
          (client/send-all! connection
                            (utf8 "GET /x HTTP/1.1\r\nHost: h\r\n\r\n")
                            {:timeout-ms watchdog-ms})
          (testing "half-closing the write side is idempotent and observable"
            (is (true? (client/shutdown-write! connection)))
            (is (false? (client/shutdown-write! connection)))
            (is (true? (:write-shutdown? (client/connection-info connection)))))

          (testing "the server still answers the request sent before the half-close"
            (let [got (read-until connection (n-responses? 1) watchdog-ms)]
              (is (string? got) (str "half-closed request was not answered: " got))
              (when (string? got)
                (let [{:keys [responses]} (read-responses got)]
                  (is (= 1 (count responses)))
                  (is (= 200 (:status (first responses))))
                  (is (= "Hello World" (:body (first responses))))))))

          (testing "the server then releases the connection, so the client sees EOF"
            (let [outcome (settled #(client/receive-at-most!
                                     connection 4096 {:timeout-ms watchdog-ms}))]
              (is (not= ::watchdog outcome))
              (is (nil? (:error outcome))
                  (str "expected a clean EOF, got "
                       (some-> (:error outcome) ex-message)))
              (is (nil? (:value outcome)))))
          (finally (client/close! connection)))))))

(deftest stop-server-is-deterministic-and-idempotent
  (let [server (http/run-server hello-handler :port 0 :reuse-address? true
                                :error-logger (fn [_]))
        port (:port server)]
    (testing "the server serves before it is stopped"
      (let [got (exchange port "GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"
                          (n-responses? 1))]
        (is (string? got))
        (when (string? got)
          (is (= 200 (:status (first (:responses (read-responses got)))))))))

    (testing "stop completes within a bounded time and is idempotent"
      (is (not= ::watchdog (settled #(http/stop-server server))))
      (is (not= ::watchdog (settled #(http/stop-server server))))
      (is (false? @(:running? server))))

    (testing "a stopped listener no longer accepts, and fails closed"
      ;; Windows reports a refused loopback connect only after a SYN
      ;; retransmit, so this deadline is generous on purpose; the assertion is
      ;; still that the attempt fails rather than succeeding.
      (let [outcome (settled 30000 #(connect! port))]
        (is (not= ::watchdog outcome))
        (if (some? (:error outcome))
          ;; The ordinary case: nothing is listening any more, so the connect is
          ;; refused and classified rather than hanging.
          (is (classified? outcome)
              "a refused connect must carry a classified error")
          ;; A port can legitimately be recycled by an unrelated listener between
          ;; stop and this probe, so a successful connect is not by itself a
          ;; failure. What must never happen is this port still answering HTTP:
          ;; the server that was serving it has been stopped.
          (let [connection (:value outcome)]
            (try
              (client/send-all! connection
                                (utf8 "GET / HTTP/1.1\r\nHost: h\r\n\r\n")
                                {:timeout-ms 5000})
              (let [got (settled #(read-until connection (n-responses? 1) 3000))]
                (is (not= ::watchdog got))
                (is (not (string? (:value got)))
                    "a stopped server answered an HTTP request"))
              (catch :default _
                ;; Writing to or reading from a socket nothing owns fails. That
                ;; is the expected outcome and agrees with the claim above.
                nil)
              (finally (client/close! connection)))))))))

(deftest connection-close-is-idempotent-and-fails-closed
  (with-server* {} hello-handler
    (fn [server]
      (let [connection (connect! (:port server))]
        (testing "only the call that begins close returns true"
          (is (true? (client/close! connection)))
          (is (false? (client/close! connection)))
          (is (true? (client/closed? connection)))
          (is (= :closed (:state (client/connection-info connection)))))
        (testing "operations after close fail closed rather than blocking"
          (let [outcome (settled #(client/receive-at-most! connection 1
                                                           {:deadline-nanos 0}))]
            (is (not= ::watchdog outcome))
            (is (= :teensyp.client/closed (err-of outcome)))))))))

(deftest no-native-descriptor-leaks-into-the-http-boundary
  (with-server* {} hello-handler
    (fn [server]
      (let [connection (connect! (:port server))]
        (try
          (testing "the public connection exposes no socket, poller, or fd"
            (let [info (client/connection-info connection)]
              (is (not (contains? info :fd)))
              (is (not (contains? info :jolt.net/raw)))
              (is (= (:port server)
                     (get-in info [:remote-address :port])))))
          (finally (client/close! connection)))))))

;; --- gate driver ------------------------------------------------------------

(defn- test-vars-in [namespace]
  (->> (ns-interns namespace)
       vals
       (filter #(contains? (meta %) :test))
       (sort-by #(str (:name (meta %))))))

(defn -main [& _]
  (let [observed (jolt.host/target)]
    (when-not (= [:windows :x86-64 64]
                 [(:os observed) (:arch observed) (:pointer-bits observed)])
      (throw
       (ex-info "Windows HTTP runtime gate did not run on native Windows x86-64"
                {:target observed})))

    ;; A readiness poller must really open here. The predecessor Windows lane
    ;; asserted the opposite, so keeping an explicit positive check makes the
    ;; W6A promotion the thing this gate fails on if it ever regresses.
    (let [poller (net/open-poller)]
      (net/close! poller))

    (let [vars (vec (test-vars-in 'jolt.http.windows-runtime-test))]
      (when-not (= 8 (count vars))
        (throw (ex-info "Windows HTTP runtime gate inventory changed"
                        {:count (count vars)
                         :tests (mapv #(-> % meta :name) vars)})))

      (t/test-vars vars)

      (let [failed (+ (t/n-fail) (t/n-error))]
        (when-not (pos? (t/n-pass))
          (throw (ex-info "Windows HTTP runtime gate was vacuous" {})))
        (println "Windows HTTP runtime gate:" (count vars) "tests,"
                 (t/n-pass) "assertions passed,"
                 (t/n-fail) "failures," (t/n-error) "errors")
        (flush)
        (System/exit (if (zero? failed) 0 1))))))
