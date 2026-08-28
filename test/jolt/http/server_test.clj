(ns jolt.http.server-test
  "Framework-less acceptance tests driven over real loopback TCP.

  The scenarios are ported from Capra's `capra.server-test`, which is the
  acceptance spec for the adapter. Raw sockets are used rather than an HTTP
  client so that malformed requests, pipelining and half-written requests can be
  driven exactly.

  `-main` exits non-zero if anything fails, so `jolt -M:test` gates CI. The
  explicit `System/exit` is required: core.async keeps non-daemon threads alive
  and the process would otherwise hang on return."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test]
            [jolt.http.body :as body]
            [jolt.http.date :as date]
            [jolt.http.http-model :as model]
            [jolt.http.protocol :as protocol]
            [jolt.http.server :as http]
            [jolt.net :as jnet]
            [teensyp.ffi-net :as net]
            ;; Loaded for their side effects on the run: the deftests below are
            ;; discovered by clojure.test/run-tests, the loopback properties by
            ;; run-properties!.
            [jolt.http.body-property-test]
            [jolt.http.protocol-property-test]
            [jolt.http.server-property-test]))

(def ^:private failures (atom 0))
(def ^:private checks (atom 0))

(defn- check [label expected actual]
  (swap! checks inc)
  (if (= expected actual)
    (println "ok  " label)
    (do (swap! failures inc)
        (println "FAIL" label
                 "\n   expected:" (pr-str expected)
                 "\n   actual:  " (pr-str actual)))))

(defn- check-pred [label pred actual]
  (swap! checks inc)
  (if (pred actual)
    (println "ok  " label)
    (do (swap! failures inc)
        (println "FAIL" label "\n   got:" (pr-str actual)))))

(defn- utf8 ^bytes [s] (.getBytes ^String s "UTF-8"))
(defn- ->str [^bytes b] (when b (String. b "UTF-8")))

(defn- recv-until-eof
  "Read until the peer closes; return the accumulated String.

  A server that rejects an oversized message can close while unread request
  bytes remain, which POSIX reports to the client as ECONNRESET rather than a
  clean EOF. The response bytes received before that terminal signal remain
  valid and must still be checked."
  [fd]
  (loop [acc ""]
    (let [b (try
              (net/client-recv fd 8192)
              (catch :default e
                (if (= :connection-reset (:jolt.net/kind (ex-data e)))
                  ::connection-reset
                  (throw e))))]
      (if (= ::connection-reset b)
        acc
        (if b
          (recur (str acc (->str b)))
          acc)))))

(defn- recv-for
  "Read for a bounded number of attempts, stopping once `done?` is satisfied.
  Used where the server keeps the connection open (keep-alive), so there is no
  EOF to wait for."
  [fd done?]
  (loop [acc "" tries 0]
    (if (or (done? acc) (>= tries 40))
      acc
      (let [b (net/client-recv fd 8192)]
        (if b
          (recur (str acc (->str b)) (inc tries))
          acc)))))

(defn- complete-response?
  "True once `s` holds a full response: headers plus either the declared
  Content-Length of body or a terminating chunk."
  [s]
  (when-let [i (str/index-of s "\r\n\r\n")]
    (let [head (subs s 0 i)
          rest (subs s (+ i 4))]
      (if-let [m (re-find #"(?i)content-length:\s*(\d+)" head)]
        (>= (alength (utf8 rest)) (parse-long (m 1)))
        (str/ends-with? s "0\r\n\r\n")))))

(defn- with-server [handler-or-opts f]
  (let [opts (if (map? handler-or-opts) handler-or-opts {:handler handler-or-opts})
        srv  (apply http/run-server (:handler opts)
                    (apply concat (merge {:port 0 :reuse-address? true}
                                         (dissoc opts :handler))))
        p    (:port srv)]
    ;; jolt-tcp does not return until listener registration is live, and stop
    ;; does not return until cleanup is complete. No readiness/cleanup sleeps.
    (try (f p) (finally (http/stop-server srv)))))

(defn- request
  "Send a raw request string, read until EOF (or a complete response), return it."
  ([p raw] (request p raw false))
  ([p raw keep-alive?]
   (let [fd (net/connect-loopback p)]
     (try
       (net/client-send-all fd (utf8 raw))
       (if keep-alive?
         (recv-for fd complete-response?)
         (recv-until-eof fd))
       (finally (net/close! fd))))))

(defn- get-request [path & {:keys [host] :or {host "localhost"}}]
  (str "GET " path " HTTP/1.1\r\nHost: " host "\r\nConnection: close\r\n\r\n"))

(defn- status-of [resp]
  (when-let [line (first (str/split-lines (or resp "")))]
    (when-let [m (re-find #"HTTP/1\.1 (\d+)" line)]
      (parse-long (m 1)))))

(defn- body-of [resp]
  (when-let [i (str/index-of (or resp "") "\r\n\r\n")]
    (subs resp (+ i 4))))

(defn- header-of [resp name]
  (when-let [i (str/index-of (or resp "") "\r\n\r\n")]
    (let [head (subs resp 0 i)
          re   (re-pattern (str "(?i)^" name ":\\s*(.*)$"))]
      (some #(when-let [m (re-find re %)] (str/trim (m 1)))
            (str/split-lines head)))))

;; --- handlers --------------------------------------------------------------

(defn- hello-handler [_req]
  {:status 200 :headers {"Content-Type" "text/plain"} :body "Hello World"})

(defn- echo-handler [req]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (str "echo:" (body/body-string (:body req) "UTF-8"))})

(defn- request-info-handler [req]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (pr-str (select-keys req [:request-method :uri :query-string :scheme
                                   :protocol :server-port :server-name
                                   :remote-addr]))})

(defrecord GuardedOffsetBody [data off len])

(extend-type GuardedOffsetBody
  body/StreamableBody
  (write-body-to-sink [this _response sink]
    (body/sink-write! sink (:data this) (:off this) (:len this))))

(defrecord SelfClosingBody [data])

(extend-type SelfClosingBody
  body/StreamableBody
  (write-body-to-sink [this _response sink]
    (let [data (:data this)]
      (body/sink-write! sink data 0 (alength data))
      ;; Custom implementations written against the original SPI may close
      ;; explicitly. The adapter also owns finalization, and the Sink chain is
      ;; deliberately idempotent so this still emits one terminator.
      (body/sink-close! sink))))

(defrecord FlushBeforeCloseBody [flushed release])

(extend-type FlushBeforeCloseBody
  body/StreamableBody
  (write-body-to-sink [this _response sink]
    (let [event (utf8 "event: ready\n\n")]
      (body/sink-write! sink event 0 (alength event))
      (body/sink-flush! sink)
      (deliver (:flushed this) true)
      @(:release this))))

;; --- scenarios -------------------------------------------------------------

(defn- test-basic []
  (with-server hello-handler
    (fn [p]
      (let [resp (request p (get-request "/"))]
        (check "basic status"        200 (status-of resp))
        (check "basic body"          "Hello World" (body-of resp))
        (check "basic content-type"  "text/plain" (header-of resp "Content-Type"))
        (check "basic content-length" "11" (header-of resp "Content-Length"))
        (check "basic server header" "jolt-http" (header-of resp "Server"))
        (check-pred "date header present" some? (header-of resp "Date"))))))

(defn- test-request-map []
  (with-server request-info-handler
    (fn [p]
      (let [resp (request p (get-request "/some/path?a=1&b=2"))
            m    (read-string (body-of resp))]
        (check "method"       :get (:request-method m))
        (check "uri"          "/some/path" (:uri m))
        (check "query-string" "a=1&b=2" (:query-string m))
        (check "scheme"       :http (:scheme m))
        (check "protocol"     "HTTP/1.1" (:protocol m))
        (check-pred "port 0 returns an actual bound port" pos? p)
        (check "server-port comes from the actual local endpoint"
               p (:server-port m))
        (check "server-name comes from the actual local endpoint"
               "127.0.0.1" (:server-name m))
        (check "remote-addr comes from the actual peer endpoint"
               "127.0.0.1" (:remote-addr m)))
      (let [resp (request p (get-request "/no-query"))
            m    (read-string (body-of resp))]
        (check "nil query-string" nil (:query-string m)))))
  (with-server {:handler request-info-handler
                :server-name "public.example"
                :remote-addr "forwarded-client"}
    (fn [p]
      (let [m (read-string (body-of (request p (get-request "/override"))))]
        (check "explicit server-name overrides the local endpoint"
               "public.example" (:server-name m))
        (check "explicit remote-addr overrides the peer endpoint"
               "forwarded-client" (:remote-addr m))))))

(defn- test-methods []
  (with-server request-info-handler
    (fn [p]
      ;; HEAD is checked separately: it must not return a body, so there is
      ;; nothing to read the method back out of.
      (doseq [[verb kw] [["GET" :get] ["POST" :post] ["PUT" :put]
                         ["DELETE" :delete] ["OPTIONS" :options]
                         ["PATCH" :patch]]]
        (let [raw  (str verb " /x HTTP/1.1\r\nHost: localhost\r\n"
                        "Connection: close\r\n\r\n")
              resp (request p raw)]
          (check (str "method " verb) kw (:request-method (read-string (body-of resp)))))))))

(defn- test-headers []
  (with-server (fn [req] {:status 200 :headers {} :body (pr-str (:headers req))})
    (fn [p]
      (let [raw  (str "GET / HTTP/1.1\r\nHost: localhost\r\n"
                      "X-Upper-Case: Value\r\n"
                      "X-Multi: one\r\nX-Multi: two\r\n"
                      "X-Spaced:    padded   \r\n"
                      "Connection: close\r\n\r\n")
            hs   (read-string (body-of (request p raw)))]
        (check "header lower-cased"  "Value" (hs "x-upper-case"))
        (check "repeated headers joined" "one,two" (hs "x-multi"))
        (check "header value trimmed" "padded" (hs "x-spaced"))))))

(defn- test-response-headers []
  (with-server (fn [_] {:status 201
                        :headers {"X-Single" "a" "X-Vector" ["v1" "v2"]}
                        :body "x"})
    (fn [p]
      (let [resp (request p (get-request "/"))]
        (check "custom status" 201 (status-of resp))
        (check "single header" "a" (header-of resp "X-Single"))
        (check-pred "vector header sent twice"
                    #(= 2 (count (filter (fn [l] (str/starts-with? (str/lower-case l) "x-vector:"))
                                         (str/split-lines %))))
                    resp)))))

(defn- test-response-metadata-fails-closed []
  (with-server
    {:error-logger (fn [_])
     :handler
     (fn [req]
       (case (:uri req)
         "/both" {:status 200
                  :headers {"Content-Length" "5"
                            "Transfer-Encoding" "chunked"}
                  :body "HELLO"}
         "/same-cl" {:status 200
                     :headers {"Content-Length" "5"
                               "content-length" "5"}
                     :body "HELLO"}
         "/different-cl" {:status 200
                          :headers {"Content-Length" "5"
                                    "content-length" "6"}
                          :body "HELLO"}
         "/huge-cl" {:status 200
                     :headers {"Content-Length" "9223372036854775808"}
                     :body "x"}
         "/bad-te" {:status 200
                    :headers {"Transfer-Encoding" "gzip"}
                    :body "x"}
         "/bad-status-text" {:status (str "200" (char 13) (char 10)
                                          "X-Injected: yes")
                             :headers {}
                             :body "x"}
         "/bad-status-low" {:status 99 :headers {} :body "x"}
         "/bad-status-high" {:status 1000 :headers {} :body "x"}
         "/bad-status-fraction" {:status 200.5 :headers {} :body "x"}
         "/bad-status-false" {:status false :headers {} :body "x"}
         "/bad-status-nil" {:status nil :headers {} :body "x"}
         {:status 404 :headers {} :body "nf"}))}
    (fn [p]
      (let [resp   (request p (get-request "/both"))
            parsed (model/read-response (model/->octets resp))]
        (check "response Transfer-Encoding wins over Content-Length"
               nil (header-of resp "Content-Length"))
        (check "response Transfer-Encoding is canonical chunked"
               "chunked" (header-of resp "Transfer-Encoding"))
        (check "canonicalized response framing parses cleanly"
               nil (:error parsed))
        (check "canonicalized response keeps the body"
               "HELLO" (model/body-str parsed)))
      (let [resp (request p (get-request "/same-cl"))]
        (check "identical response Content-Length values collapse"
               "5" (header-of resp "Content-Length"))
        (check "collapsed response Content-Length is emitted once"
               1 (count (re-seq #"(?i)content-length:" resp))))
      (doseq [[path label]
              [["/different-cl" "conflicting response Content-Length"]
               ["/huge-cl" "unrepresentable response Content-Length"]
               ["/bad-te" "unsupported response Transfer-Encoding"]
               ["/bad-status-text" "text response status"]
               ["/bad-status-low" "response status below 100"]
               ["/bad-status-high" "response status above 999"]
               ["/bad-status-fraction" "fractional response status"]
               ["/bad-status-false" "false response status"]
               ["/bad-status-nil" "nil response status"]]]
        (let [resp (request p (get-request path))]
          (check (str label " -> 500") 500 (status-of resp))
          (check (str label " emits one status line")
                 1 (count (re-seq #"HTTP/1\.1 " resp)))
          (check (str label " cannot inject a header")
                 nil (header-of resp "X-Injected")))))))

(defn- test-body-types []
  (with-server (fn [req]
                 (case (:uri req)
                   "/string" {:status 200 :headers {} :body "a string"}
                   "/bytes"  {:status 200 :headers {} :body (utf8 "raw bytes")}
                   "/nil"    {:status 204 :headers {} :body nil}
                   "/seq"    {:status 200 :headers {} :body ["chunk1" "chunk2"]}
                   {:status 404 :headers {} :body "nf"}))
    (fn [p]
      (check "string body" "a string"  (body-of (request p (get-request "/string"))))
      (check "bytes body"  "raw bytes" (body-of (request p (get-request "/bytes"))))
      (let [resp (request p (get-request "/nil"))]
        (check "nil body status" 204 (status-of resp))
        (check "nil body empty"  "" (body-of resp))
        ;; RFC 9112 6.2: a 204 must not carry Content-Length at all — not even
        ;; "0". This previously asserted "0", encoding the violation.
        (check "204 sends no Content-Length" nil (header-of resp "Content-Length"))
        (check "204 sends no Transfer-Encoding" nil (header-of resp "Transfer-Encoding")))
      (let [resp (request p (get-request "/seq"))]
        (check "seq body chunked" "chunked" (header-of resp "Transfer-Encoding"))
        (check-pred "seq body content"
                    #(str/includes? % "chunk1") (body-of resp)))
      (check "404 status" 404 (status-of (request p (get-request "/missing")))))))

(defn- test-guarded-offset-streamable-body []
  (let [prefix  "prefix-guard:"
        payload "only-this-nonzero-offset-range-may-reach-the-wire"
        suffix  ":suffix-guard"
        data    (utf8 (str prefix payload suffix))
        body    (->GuardedOffsetBody data
                                     (alength (utf8 prefix))
                                     (alength (utf8 payload)))]
    (with-server (fn [_] {:status 200 :headers {} :body body})
      (fn [p]
        (let [resp   (request p (get-request "/"))
              parsed (model/read-response (model/->octets resp))]
          (check "offset StreamableBody uses chunked framing"
                 "chunked" (header-of resp "Transfer-Encoding"))
          (check "offset StreamableBody response parses cleanly"
                 nil (:error parsed))
          (check "StreamableBody/coalescing/chunked preserve only the guarded range"
                 payload (model/body-str parsed)))))))

(defn- test-file-body []
  (let [f (java.io.File. "test/jolt/http/test_file.txt")]
    (with-server (fn [_] {:status 200 :headers {"Content-Type" "text/plain"} :body f})
      (fn [p]
        (let [resp (request p (get-request "/"))]
          (check "file status" 200 (status-of resp))
          (check "file uses content-length (not chunked)"
                 (str (.length f)) (header-of resp "Content-Length"))
          (check "file not chunked" nil (header-of resp "Transfer-Encoding"))
          (check "file body" (slurp f) (body-of resp)))))))

(defn- test-request-body []
  (with-server echo-handler
    (fn [p]
      (let [raw  (str "POST /x HTTP/1.1\r\nHost: localhost\r\n"
                      "Content-Length: 11\r\nConnection: close\r\n\r\nhello world")]
        (check "content-length body" "echo:hello world" (body-of (request p raw))))
      (let [raw (str "POST /x HTTP/1.1\r\nHost: localhost\r\n"
                     "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                     "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n")]
        (check "chunked body" "echo:hello world" (body-of (request p raw))))
      (let [raw (str "POST /x HTTP/1.1\r\nHost: localhost\r\n"
                     "Content-Length: 0\r\nConnection: close\r\n\r\n")]
        (check "empty body" "echo:" (body-of (request p raw)))))))

(defn- test-large-request-body []
  (with-server (fn [req] {:status 200 :headers {}
                          :body (str (alength (body/body-bytes (:body req))))})
    (fn [p]
      (let [payload (str/join (repeat 5000 "0123456789"))   ; 50_000 bytes
            raw     (str "POST /x HTTP/1.1\r\nHost: localhost\r\n"
                         "Content-Length: " (count payload) "\r\n"
                         "Connection: close\r\n\r\n" payload)]
        (check "50KB body fully read (backpressure)"
               "50000" (body-of (request p raw)))))))

(defn- test-large-response-body []
  (let [payload (str/join (repeat 20000 "0123456789"))]     ; 200_000 bytes
    (with-server (fn [req]
                   {:status 200
                    :headers {}
                    :body (if (= "/seq" (:uri req))
                            (vec (map #(apply str %)
                                      (partition-all 997 payload)))
                            payload)})
      (fn [p]
        (let [resp (request p (get-request "/"))]
          (check "200KB response length"
                 (str (count payload)) (header-of resp "Content-Length"))
          (check "200KB response body intact" payload (body-of resp)))
        (let [resp (request p (get-request "/seq"))]
          (check "200KB sequential response is chunked"
                 "chunked" (header-of resp "Transfer-Encoding"))
          (let [{:keys [responses error trailing]}
                (model/read-responses (model/->octets resp))]
            (check "200KB sequential response parses cleanly"
                   [nil []] [error trailing])
            (check "200KB sequential response body intact"
                   payload (model/body-str (first responses)))))))))

(defn- test-keep-alive []
  (with-server hello-handler
    (fn [p]
      (let [fd (net/connect-loopback p)]
        (try
          (net/client-send-all fd (utf8 "GET /a HTTP/1.1\r\nHost: localhost\r\n\r\n"))
          (let [r1 (recv-for fd complete-response?)]
            (check "keep-alive first"  200 (status-of r1))
            (check "keep-alive no close header" nil (header-of r1 "Connection")))
          (net/client-send-all fd (utf8 "GET /b HTTP/1.1\r\nHost: localhost\r\n\r\n"))
          (let [r2 (recv-for fd complete-response?)]
            (check "keep-alive second on same conn" 200 (status-of r2)))
          (finally (net/close! fd)))))))

(defn- test-pipelining []
  (with-server hello-handler
    (fn [p]
      (let [fd (net/connect-loopback p)]
        (try
          ;; both requests sent before reading any response
          (net/client-send-all
           fd (utf8 (str "GET /a HTTP/1.1\r\nHost: localhost\r\n\r\n"
                         "GET /b HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")))
          (let [resp (recv-until-eof fd)
                n    (count (re-seq #"HTTP/1\.1 200" resp))]
            (check "pipelined: two responses" 2 n))
          (finally (net/close! fd))))))
  ;; A small synchronous response completes inside the parser's current read
  ;; invocation. Re-queueing resume-reads for every such response is unnecessary
  ;; and used to overflow the bounded control queue on request 33, closing an
  ;; otherwise valid pipeline and then leaving cleanup to read a spent transient.
  (let [n 64
        errors (atom [])]
    (with-server {:handler hello-handler
                  :control-queue-size 32
                  :write-queue-size 128
                  :error-logger (fn [e] (swap! errors conj e))}
      (fn [p]
        (let [fd (net/connect-loopback p)
              raw (apply str
                         (for [i (range n)]
                           (str "GET /" i " HTTP/1.1\r\nHost: localhost\r\n"
                                (when (= i (dec n)) "Connection: close\r\n")
                                "\r\n")))]
          (try
            (net/client-send-all fd (utf8 raw))
            (let [resp (recv-until-eof fd)]
              (check "pipelined: 64 synchronous responses do not overflow controls"
                     n (count (re-seq #"HTTP/1\.1 200" resp)))
              (check "pipelined: control queue stays within capacity"
                     [] (mapv #(-> % ex-data :err) @errors)))
            (finally (net/close! fd))))))))

(defn- test-connection-close []
  (with-server hello-handler
    (fn [p]
      (let [resp (request p (get-request "/"))]
        (check "close requested -> close header" "close" (header-of resp "Connection"))))))

(defn- test-split-request []
  (with-server hello-handler
    (fn [p]
      (let [fd (net/connect-loopback p)]
        (try
          ;; a request split mid-header across two writes must still parse
          (net/client-send-all fd (utf8 "GET / HTTP/1.1\r\nHo"))
          (Thread/sleep 120)
          (net/client-send-all fd (utf8 "st: localhost\r\nConnection: close\r\n\r\n"))
          (check "split request parsed" 200 (status-of (recv-until-eof fd)))
          (finally (net/close! fd)))))))

(defn- test-errors []
  (with-server hello-handler
    (fn [p]
      (check "bad start line -> 400" 400
             (status-of (request p "NOTAREQUEST\r\n\r\n")))
      (check "missing host -> 400" 400
             (status-of (request p "GET / HTTP/1.1\r\n\r\n")))
      (check "bad http version -> 505" 505
             (status-of (request p "GET / HTTP/1.0\r\nHost: localhost\r\n\r\n")))
      (check "bad header line -> 400" 400
             (status-of (request p (str "GET / HTTP/1.1\r\nHost: localhost\r\n"
                                        "nocolon\r\n\r\n"))))
      ;; RFC 9112 6.3 — when the final coding is not chunked the body length
      ;; cannot be determined, so this is a 400 (unframeable), not a 501
      ;; (merely unsupported).
      (check "transfer-encoding not ending in chunked -> 400" 400
             (status-of (request p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                                        "Transfer-Encoding: gzip\r\n\r\n")))))))

;; --- RFC conformance / request-smuggling hardening -------------------------

(defn- test-head-has-no-body []
  (with-server hello-handler
    (fn [p]
      (let [resp (request p (str "HEAD / HTTP/1.1\r\nHost: localhost\r\n"
                                 "Connection: close\r\n\r\n"))]
        ;; RFC 9110 9.3.2: same headers as the equivalent GET, but no content.
        (check "HEAD status" 200 (status-of resp))
        (check "HEAD keeps Content-Length" "11" (header-of resp "Content-Length"))
        (check "HEAD sends no body" "" (body-of resp))))))

(defn- test-head-keep-alive
  "A body on a HEAD response desynchronises the connection: the client reads
  those bytes as the start of the next response."
  []
  (with-server hello-handler
    (fn [p]
      (let [fd (net/connect-loopback p)]
        (try
          (net/client-send-all fd (utf8 "HEAD /a HTTP/1.1\r\nHost: localhost\r\n\r\n"))
          ;; A HEAD response is complete at the end of the header block: it
          ;; carries the Content-Length a GET would, but no body to wait for.
          (let [r1 (recv-for fd #(str/includes? % "\r\n\r\n"))]
            (check "HEAD keep-alive: no body" "" (body-of r1)))
          (net/client-send-all fd (utf8 "GET /b HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"))
          (let [r2 (recv-until-eof fd)]
            (check "GET after HEAD stays in sync" "Hello World" (body-of r2)))
          (finally (net/close! fd)))))))

(defn- test-request-smuggling-hardening []
  (with-server echo-handler
    (fn [p]
      ;; RFC 9112 6.3 — ambiguous framing must be rejected, not guessed at.
      (check "Content-Length + Transfer-Encoding -> 400" 400
             (status-of (request p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                                        "Content-Length: 6\r\n"
                                        "Transfer-Encoding: chunked\r\n"
                                        "Connection: close\r\n\r\n0\r\n\r\n"))))
      (check "conflicting duplicate Content-Length -> 400" 400
             (status-of (request p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                                        "Content-Length: 5\r\nContent-Length: 6\r\n"
                                        "Connection: close\r\n\r\nhello"))))
      (check "identical duplicate Content-Length is accepted" "echo:hello"
             (body-of (request p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                                      "Content-Length: 5\r\nContent-Length: 5\r\n"
                                      "Connection: close\r\n\r\nhello"))))
      (check "non-numeric Content-Length -> 400" 400
             (status-of (request p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                                        "Content-Length: abc\r\n"
                                        "Connection: close\r\n\r\n"))))
      ;; RFC 9112 5.1 — whitespace between field name and colon must be rejected.
      (check "whitespace before colon -> 400" 400
             (status-of (request p (str "GET / HTTP/1.1\r\nHost: localhost\r\n"
                                        "Foo : bar\r\nConnection: close\r\n\r\n")))))))

(defn- test-chunked-trailers-and-extensions []
  (with-server echo-handler
    (fn [p]
      (check "chunk extensions are ignored" "echo:hello"
             (body-of (request p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                                      "Transfer-Encoding: chunked\r\n"
                                      "Connection: close\r\n\r\n"
                                      "5;ext=1\r\nhello\r\n0\r\n\r\n"))))
      (check "trailer section is consumed" "echo:hello"
             (body-of (request p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                                      "Transfer-Encoding: chunked\r\n"
                                      "Connection: close\r\n\r\n"
                                      "5\r\nhello\r\n0\r\nX-Trailer: v\r\n\r\n"))))))
  ;; The real hazard: unconsumed trailer bytes are read as the next request.
  (with-server echo-handler
    (fn [p]
      (let [fd (net/connect-loopback p)]
        (try
          (net/client-send-all
           fd (utf8 (str "POST /a HTTP/1.1\r\nHost: localhost\r\n"
                         "Transfer-Encoding: chunked\r\n\r\n"
                         "5\r\nhello\r\n0\r\nX-Trailer: v\r\n\r\n"
                         "POST /b HTTP/1.1\r\nHost: localhost\r\n"
                         "Content-Length: 5\r\nConnection: close\r\n\r\nworld")))
          (let [resp (recv-until-eof fd)]
            (check "trailers do not corrupt the next request" 2
                   (count (re-seq #"HTTP/1\.1 200" resp))))
          (finally (net/close! fd)))))))

;; Rules ported from cispa/http-conformance (MIT), the test suite from "Who's
;; Breaking the Rules? Studying Conformance to the HTTP Specifications and its
;; Security Impact" (ACM AsiaCCS 2024). Only the rules that an adapter is
;; responsible for are included; the rest of their 106 govern things a Ring
;; application emits (cookies, CSP, CORS, caching), not the server.
(defn- test-conformance-message-syntax []
  (with-server hello-handler
    (fn [p]
      ;; RFC 9112 2.2 — ignore empty line(s) before the request-line.
      (check "leading CRLF is ignored" 200
             (status-of (request p (str "\r\nGET / HTTP/1.1\r\nHost: localhost\r\n"
                                        "Connection: close\r\n\r\n"))))
      ;; RFC 9112 5.2 — obs-fold must be rejected by a non-proxy.
      (check "obs-fold / leading whitespace header -> 400" 400
             (status-of (request p (str "GET / HTTP/1.1\r\n Host: localhost\r\n"
                                        "Connection: close\r\n\r\n"))))
      ;; RFC 9112 2.2 — a bare CR is not a line terminator.
      (check "bare CR in header value -> 400" 400
             (status-of (request p (str "GET / HTTP/1.1\r\nHost: localhost\r\n"
                                        "X-Bad: a" (char 13) "b\r\n"
                                        "Connection: close\r\n\r\n"))))
      ;; RFC 9110 5.6.2 — a field name is a token.
      (check "space inside field name -> 400" 400
             (status-of (request p (str "GET / HTTP/1.1\r\nHost: localhost\r\n"
                                        "X Bad: v\r\nConnection: close\r\n\r\n"))))
      (check "control char in field name -> 400" 400
             (status-of (request p (str "GET / HTTP/1.1\r\nHost: localhost\r\n"
                                        "X" (char 1) "Bad: v\r\nConnection: close\r\n\r\n"))))
      ;; RFC 9112 3.2 — exactly one Host.
      (check "duplicate Host -> 400" 400
             (status-of (request p (str "GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n"
                                        "Connection: close\r\n\r\n")))))))

(defn- test-conformance-no-content-statuses []
  (with-server (fn [req]
                 (case (:uri req)
                   ;; a handler that wrongly supplies framing headers on a 204
                   "/204" {:status 204
                           :headers {"Content-Length" "5" "Transfer-Encoding" "chunked"}
                           :body nil}
                   "/304" {:status 304 :headers {} :body nil}
                   {:status 200 :headers {} :body "x"}))
    (fn [p]
      (let [resp (request p (get-request "/204"))]
        ;; RFC 9112 6.2 — never on a 1xx or 204, even if the handler asked.
        (check "204 strips Content-Length" nil (header-of resp "Content-Length"))
        (check "204 strips Transfer-Encoding" nil (header-of resp "Transfer-Encoding"))
        (check "204 has no body" "" (body-of resp)))
      (let [resp (request p (get-request "/304"))]
        (check "304 status" 304 (status-of resp))
        (check "304 has no body" "" (body-of resp))))))

(defn- test-response-header-injection
  "HTTP response splitting (CWE-113). A handler that puts unescaped input into
  a header value must not be able to forge headers or a second response."
  []
  (let [logged (atom [])]
    (with-server {:error-logger (fn [e] (swap! logged conj e))
                  :handler
                  (fn [req]
                    (case (:uri req)
                      "/crlf" {:status 200
                               :headers {"X-Evil" (str "a" (char 13) (char 10)
                                                       "X-Injected: yes")}
                               :body "i"}
                      "/lf"   {:status 200
                               :headers {"X-Evil" (str "a" (char 10) "X-Injected: yes")}
                               :body "i"}
                      "/nul"  {:status 200
                               :headers {"X-Evil" (str "a" (char 0) "b")}
                               :body "i"}
                      "/name" {:status 200 :headers {"X Bad" "v"} :body "i"}
                      {:status 200 :headers {} :body "x"}))}
      (fn [p]
        (doseq [[path label] [["/crlf" "CRLF"] ["/lf" "LF"] ["/nul" "NUL"]]]
          (let [resp (request p (get-request path))]
            (check (str label " in header value -> 500") 500 (status-of resp))
            (check (str label " header is not injected") nil
                   (header-of resp "X-Injected"))))
        (check "non-token response field name -> 500" 500
               (status-of (request p (get-request "/name"))))
        (check "unsafe response header was logged" true (pos? (count @logged)))))))

;; Adversarial cases covering the parsing-discrepancy classes catalogued by the
;; HTTP Garden project (arXiv:2405.17737), which found 100+ such bugs across
;; widely deployed servers. HTTP Garden itself is GPLv3, so nothing is copied
;; from it: these are the bug *classes* it documents, with payloads written here
;; from the RFC grammar. Each class below has produced a real CVE somewhere.
(defn- test-smuggling-chunk-size
  "Classes 1/22/25/32: chunk-size parsed by a permissive numeric routine.
  `parseLong` accepts a leading '+', `strtoll` accepts '0x' and '-', so a
  front-end and a back-end can disagree about where a chunk ends."
  []
  (with-server echo-handler
    (fn [p]
      (doseq [[label size] [["'+5'" "+5"] ["'-5'" "-5"] ["'0x5'" "0x5"]
                            ["' 5' (leading OWS)" " 5"] ["empty" ""]
                            ["'5_'" "5_"] ["'5 '" "5 "]]]
        (check (str "chunk-size " label " -> 400") 400
               (status-of (request p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                                          "Transfer-Encoding: chunked\r\n\r\n"
                                          size "\r\nhello\r\n0\r\n\r\n")))))
      ;; and the well-formed forms still work, including uppercase hex
      (check "chunk-size 'A' (hex) accepted" "echo:aaaaaaaaaa"
             (body-of (request p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                                      "Transfer-Encoding: chunked\r\n"
                                      "Connection: close\r\n\r\n"
                                      "A\r\naaaaaaaaaa\r\n0\r\n\r\n")))))))

(defn- test-smuggling-content-length
  "Classes 10/16/26/29/32: Content-Length parsed permissively. A negative value
  has been used to drive servers into an infinite busy loop."
  []
  (with-server echo-handler
    (fn [p]
      (doseq [[label v] [["'+5'" "+5"] ["'-5'" "-5"] ["empty" ""]
                         ["'5abc'" "5abc"] ["'0x5'" "0x5"] ["'5_'" "5_"]]]
        (check (str "Content-Length " label " -> 400") 400
               (status-of (request p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                                          "Content-Length: " v "\r\n"
                                          "Connection: close\r\n\r\nhello"))))))))

(defn- test-smuggling-transfer-encoding
  "Classes 11/21/44: multiple or unusual transfer codings. Anything that is not
  exactly `chunked` must be refused rather than guessed at."
  []
  (with-server echo-handler
    (fn [p]
      ;; Two distinct outcomes, per RFC 9112 6.1/6.3:
      ;;   final coding is not chunked -> 400, the body cannot be framed at all
      ;;   final coding is chunked but other codings apply -> 501, unsupported
      (doseq [[label v expected] [["duplicated chunked" "chunked, chunked" 501]
                                  ["',chunked'"         ",chunked"         501]
                                  ["'gzip, chunked'"    "gzip, chunked"    501]
                                  ["unknown coding"     "bogus"            400]
                                  ["'chunked, gzip'"    "chunked, gzip"    400]]]
        (check (str "Transfer-Encoding " label " -> " expected) expected
               (status-of (request p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                                          "Transfer-Encoding: " v "\r\n\r\n"
                                          "0\r\n\r\n"))))))))

(defn- test-smuggling-request-line
  "Classes 5/6/15/24/40: method and version taken as their longest valid prefix,
  or not validated at all."
  []
  (with-server hello-handler
    (fn [p]
      (doseq [[label m] [["'GE(T'" "GE(T"] ["'GET\\x0b'" (str "GET" (char 11))]
                         ["empty" ""] ["'GET,POST'" "GET,POST"]
                         ["'GET\\x00'" (str "GET" (char 0))]]]
        (check (str "method " label " rejected") true
               (contains? #{400 501} (status-of
                                      (request p (str m " / HTTP/1.1\r\nHost: localhost\r\n"
                                                      "Connection: close\r\n\r\n"))))))
      (doseq [v ["HTTP/1.10" "HTTP/9.9" "HTTP/1.1x" "HTTP/01.1" "http/1.1"]]
        (check (str "version " v " -> 505") 505
               (status-of (request p (str "GET / " v "\r\nHost: localhost\r\n"
                                          "Connection: close\r\n\r\n"))))))))

(defn- test-smuggling-pipelining
  "Classes 45/48: a pipelined request must not be swallowed as the body of a
  preceding Content-Length: 0 request, and a bad request after a good one must
  not lose the good one's response."
  []
  (with-server hello-handler
    (fn [p]
      (let [resp (request p (str "POST /a HTTP/1.1\r\nHost: localhost\r\n"
                                 "Content-Length: 0\r\n\r\n"
                                 "GET /b HTTP/1.1\r\nHost: localhost\r\n"
                                 "Connection: close\r\n\r\n"))]
        (check "CL:0 does not swallow the pipelined request" 2
               (count (re-seq #"HTTP/1\.1 200" resp))))
      (let [resp (request p (str "GET /a HTTP/1.1\r\nHost: localhost\r\n\r\n"
                                 "BADREQUEST\r\n\r\n"))]
        (check "good request answered before the bad one" true
               (str/includes? resp "200"))
        (check "bad pipelined request gets an error" true
               (str/includes? resp "400"))))))

;; --- parser vectors --------------------------------------------------------
;; Table-driven request-parsing checks in the style of Go's
;; net/http/readrequest_test.go and the h11 test-suite: a raw byte stream in,
;; the parsed request fields out. Written here rather than copied.
(def ^:private parse-vectors
  [{:label "simple GET"
    :raw   "GET /path HTTP/1.1\r\nHost: h\r\n\r\n"
    :want  {:request-method :get :uri "/path" :query-string nil}}
   {:label "query string"
    :raw   "GET /p?a=1&b=2 HTTP/1.1\r\nHost: h\r\n\r\n"
    :want  {:request-method :get :uri "/p" :query-string "a=1&b=2"}}
   {:label "empty query string"
    :raw   "GET /p? HTTP/1.1\r\nHost: h\r\n\r\n"
    :want  {:request-method :get :uri "/p" :query-string ""}}
   {:label "root"
    :raw   "GET / HTTP/1.1\r\nHost: h\r\n\r\n"
    :want  {:uri "/"}}
   {:label "encoded path is not decoded"
    :raw   "GET /a%2Fb%20c HTTP/1.1\r\nHost: h\r\n\r\n"
    :want  {:uri "/a%2Fb%20c"}}
   {:label "asterisk-form target"
    :raw   "OPTIONS * HTTP/1.1\r\nHost: h\r\n\r\n"
    :want  {:request-method :options :uri "*"}}
   {:label "lowercase method is distinct"
    :raw   "get / HTTP/1.1\r\nHost: h\r\n\r\n"
    :want  {:request-method :get}}
   {:label "header name case folded"
    :raw   "GET / HTTP/1.1\r\nHost: h\r\nX-MiXeD: v\r\n\r\n"
    :want  {:headers {"x-mixed" "v"}}}
   {:label "header value OWS trimmed"
    :raw   "GET / HTTP/1.1\r\nHost: h\r\nX-A:   v \r\n\r\n"
    :want  {:headers {"x-a" "v"}}}
   {:label "repeated headers comma-joined"
    :raw   "GET / HTTP/1.1\r\nHost: h\r\nX-A: 1\r\nX-A: 2\r\n\r\n"
    :want  {:headers {"x-a" "1,2"}}}
   {:label "empty header value"
    :raw   "GET / HTTP/1.1\r\nHost: h\r\nX-A:\r\n\r\n"
    :want  {:headers {"x-a" ""}}}
   {:label "colon inside header value"
    :raw   "GET / HTTP/1.1\r\nHost: h\r\nX-A: a:b\r\n\r\n"
    :want  {:headers {"x-a" "a:b"}}}
   {:label "tab as OWS in value"
    :raw   "GET / HTTP/1.1\r\nHost: h\r\nX-A:\tv\r\n\r\n"
    :want  {:headers {"x-a" "v"}}}])

(defn- test-parser-vectors []
  (with-server (fn [req]
                 {:status 200 :headers {}
                  :body (pr-str (select-keys req [:request-method :uri
                                                  :query-string :headers]))})
    (fn [p]
      (doseq [{:keys [label raw want]} parse-vectors]
        (let [resp (request p (str/replace raw "\r\n\r\n"
                                           "\r\nConnection: close\r\n\r\n"))
              got  (some-> (body-of resp) read-string)]
          (if (nil? got)
            (check (str "parse: " label) want :no-response)
            ;; compare only the keys the vector pins down; headers are compared
            ;; as a subset so a vector need not list Host/Connection.
            (check (str "parse: " label)
                   want
                   (reduce-kv (fn [m k v]
                                (assoc m k (if (= k :headers)
                                             (select-keys (:headers got) (keys v))
                                             (get got k))))
                              {} want))))))))

;; Found by h1spec (MIT), an HTTP/1.1 conformance checker in the spirit of
;; h2spec, pointed at a running server.
(defn- half-close-request
  "Send a request, shut down the write side, and read until the server closes —
  the pattern many HTTP clients use. The server must still answer."
  [p raw]
  (let [fd (net/connect-loopback p)]
    (try
      (net/client-send-all fd (utf8 raw))
      (net/shutdown-write! fd)
      (recv-until-eof fd)
      (finally (net/close! fd)))))

(defn- test-half-close []
  (with-server (fn [req]
                 (if (= :post (:request-method req))
                   {:status 200 :headers {}
                    :body (str "posted:" (body/body-string (:body req) "UTF-8"))}
                   {:status 200 :headers {} :body "Hello World"}))
    (fn [p]
      ;; Closing on EOF would discard the response to the request just sent.
      (check "half-close GET answered" "Hello World"
             (body-of (half-close-request p "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")))
      ;; The streaming path is the harder case: the handler runs on another
      ;; thread, so at EOF the reactor sees no work in flight.
      (check "half-close POST answered" "posted:hello"
             (body-of (half-close-request
                       p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                              "Content-Length: 5\r\n\r\nhello"))))
      (check "half-close chunked answered" "posted:hello"
             (body-of (half-close-request
                       p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                              "Transfer-Encoding: chunked\r\n\r\n"
                              "5\r\nhello\r\n0\r\n\r\n"))))
      (check "half-close error answered" 400
             (status-of (half-close-request p "GET / HTTP/1.1\r\n\r\n"))))))

(defn- test-incomplete-request-eof-is-terminal []
  (with-server echo-handler
    (fn [p]
      (doseq [[label raw]
              [["partial request line"
                "GET / HTT"]
               ["partial header field"
                "GET / HTTP/1.1\r\nHost: h\r\nX-Part"]
               ["missing end of header section"
                "GET / HTTP/1.1\r\nHost: h\r\n"]
               ["short fixed-length body"
                (str "POST / HTTP/1.1\r\nHost: h\r\n"
                     "Content-Length: 5\r\nConnection: close\r\n\r\nabc")]
               ["partial chunk-size line"
                (str "POST / HTTP/1.1\r\nHost: h\r\n"
                     "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n5")]
               ["short chunk data"
                (str "POST / HTTP/1.1\r\nHost: h\r\n"
                     "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                     "5\r\nabc")]
               ["partial chunk data terminator"
                (str "POST / HTTP/1.1\r\nHost: h\r\n"
                     "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                     "5\r\nhello\r")]
               ["partial trailer section"
                (str "POST / HTTP/1.1\r\nHost: h\r\n"
                     "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                     "0\r\nX-Trailer: v\r\n")]]]
        (let [resp (half-close-request p raw)]
          (check (str label " at EOF -> 400") 400 (status-of resp))
          (check (str label " gets exactly one terminal response")
                 1 (count (re-seq #"HTTP/1\.1 " resp))))))))

(defn- test-expect-continue []
  (with-server (fn [req] {:status 200 :headers {}
                          :body (str "got:" (body/body-string (:body req) "UTF-8"))})
    (fn [p]
      (let [fd (net/connect-loopback p)]
        (try
          ;; RFC 9110 10.1.1 — the client waits for permission before sending
          ;; the body; without the interim response it stalls until its own
          ;; timeout (curl does this for bodies over 1KB).
          (net/client-send-all
           fd (utf8 (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                         "Content-Length: 5\r\nExpect: 100-continue\r\n\r\n")))
          (let [interim (recv-for fd #(str/includes? % "\r\n\r\n"))]
            (check "Expect: 100-continue gets interim 100" 100 (status-of interim)))
          (net/client-send-all fd (utf8 "hello"))
          (let [final (recv-for fd complete-response?)]
            (check "body accepted after 100" "got:hello" (body-of final)))
          (finally (net/close! fd))))
      ;; RFC 9110 10.1.1 — an expectation we do not understand is a 417.
      (check "unknown expectation -> 417" 417
             (status-of (request p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                                        "Content-Length: 0\r\n"
                                        "Expect: something-else\r\n"
                                        "Connection: close\r\n\r\n")))))))

(defn- test-invalid-host-value []
  (with-server hello-handler
    (fn [p]
      ;; RFC 9112 3.2 — a Host that is not a valid host[:port] is a 400.
      (doseq [[label h] [["space in host" "bad host"]
                         ["tab in host" (str "bad" (char 9) "host")]]]
        (check (str label " -> 400") 400
               (status-of (request p (str "GET / HTTP/1.1\r\nHost: " h "\r\n"
                                          "Connection: close\r\n\r\n")))))
      (check "host with port still accepted" 200
             (status-of (request p (str "GET / HTTP/1.1\r\nHost: example.com:8080\r\n"
                                        "Connection: close\r\n\r\n"))))
      (check "IPv6 literal host accepted" 200
             (status-of (request p (str "GET / HTTP/1.1\r\nHost: [::1]:80\r\n"
                                        "Connection: close\r\n\r\n")))))))

(defn- test-chunk-terminator []
  (with-server echo-handler
    (fn [p]
      ;; chunk-data must be followed by CRLF; skipping two bytes unchecked
      ;; silently accepts a mis-sized chunk, which is how a desync gets through.
      (check "chunk data not followed by CRLF -> 400" 400
             (status-of (request p (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                                        "Transfer-Encoding: chunked\r\n\r\n"
                                        "5\r\nhello0\r\n\r\n")))))))

(defn- test-oversized []
  (with-server {:handler hello-handler :read-buffer-size 1024}
    (fn [p]
      (check "long uri -> 414" 414
             (status-of (request p (str "GET /" (str/join (repeat 2000 "x"))
                                        " HTTP/1.1\r\nHost: localhost\r\n\r\n"))))
      (check "long header -> 431" 431
             (status-of (request p (str "GET / HTTP/1.1\r\nHost: localhost\r\n"
                                        "X-Big: " (str/join (repeat 2000 "y"))
                                        "\r\n\r\n")))))))

(defn- test-aggregate-header-limits []
  ;; The section-byte limit counts every field's CRLF and the final blank CRLF.
  ;; "Host: h\r\nX: 12345\r\n\r\n" is therefore exactly 21 octets.
  (with-server {:handler hello-handler :max-header-bytes 21 :max-header-count 10}
    (fn [p]
      (check "aggregate header bytes accept the exact boundary"
             200
             (status-of
              (request p "GET / HTTP/1.1\r\nHost: h\r\nX: 12345\r\n\r\n" true)))))
  (with-server {:handler hello-handler :max-header-bytes 20 :max-header-count 10}
    (fn [p]
      (check "aggregate header bytes reject boundary plus one"
             431
             (status-of
              (request p "GET / HTTP/1.1\r\nHost: h\r\nX: 12345\r\n\r\n" true)))))
  (with-server {:handler hello-handler :max-header-bytes 1024 :max-header-count 2}
    (fn [p]
      (check "aggregate header count accepts the exact boundary"
             200
             (status-of
              (request p "GET / HTTP/1.1\r\nHost: h\r\nX: v\r\n\r\n" true)))
      (check "aggregate header count rejects boundary plus one"
             431
             (status-of
              (request p
                       "GET / HTTP/1.1\r\nHost: h\r\nX: v\r\nY: v\r\n\r\n"
                       true))))))

(defn- test-content-length-overflow []
  (let [invoked (atom 0)]
    (with-server
      (fn [_]
        (swap! invoked inc)
        {:status 200 :headers {} :body "unexpected"})
      (fn [p]
        (let [resp (request p
                            (str "POST / HTTP/1.1\r\nHost: h\r\n"
                                 "Content-Length: 9223372036854775808\r\n"
                                 "Connection: close\r\n\r\n"))]
          (check "Content-Length above signed long -> 400" 400 (status-of resp))
          (check "overflowing Content-Length never reaches the handler"
                 0 @invoked))))))

(defn- test-exception-handling []
  (with-server {:handler (fn [_] (throw (ex-info "boom" {})))
                :error-logger (fn [_])}
    (fn [p]
      (let [resp (request p (get-request "/"))]
        (check "handler exception -> 500" 500 (status-of resp))
        (check "500 body" "Internal Server Error" (body-of resp))))))

(defn- test-close-after-spent-parser-state []
  (let [logged  (atom [])
        cause   (ex-info "original parser failure" {:err ::original})
        state   (transient {::protocol/chan :unreachable})
        handler (protocol/tcp-handler
                 (fn [_] nil)
                 {:error-logger #(swap! logged conj %)})
        _       (persistent! state)]
    (check "close cleanup tolerates parser state spent by the original failure"
           :ok
           (try (handler state cause) :ok (catch :default _ :threw)))
    (check "close cleanup preserves the original error"
           ["original parser failure"]
           (mapv ex-message @logged))))

(defn- test-peer-disconnect-is-not-logged []
  (let [logged  (atom [])
        handler (protocol/tcp-handler
                 (fn [_] nil)
                 {:error-logger #(swap! logged conj %)})]
    (handler {} (ex-info "peer reset" {:jolt.net/kind :connection-reset}))
    (handler {} (ex-info "socket retired" {:err :teensyp.server/socket-closed}
                              (ex-info "peer reset"
                                       {:jolt.net/kind :connection-reset})))
    (check "peer disconnect is normal response cancellation" [] @logged)
    (handler {} (ex-info "application failure" {:err ::application-failure}))
    (check "non-peer failures still reach the error logger"
           ["application failure"]
           (mapv ex-message @logged))))

(defn- test-custom-error-handler []
  (with-server {:handler (fn [_] (throw (ex-info "boom" {})))
                :error-logger (fn [_])
                :error-handler (fn [_req respond _ex]
                                 (respond {:status 503 :headers {} :body "custom"}))}
    (fn [p]
      (let [resp (request p (get-request "/"))]
        (check "custom error status" 503 (status-of resp))
        (check "custom error body" "custom" (body-of resp))))))

(defn- test-async-handler []
  (with-server {:async? true
                :handler (fn [_req respond _raise]
                           (future (Thread/sleep 60)
                                   (respond {:status 200 :headers {} :body "async!"})))}
    (fn [p]
      (let [resp (request p (get-request "/"))]
        (check "async status" 200 (status-of resp))
        (check "async body" "async!" (body-of resp))))))

(defn- test-async-streamable-bodies-finalize []
  (let [custom-open (->GuardedOffsetBody
                     (utf8 "prefix-custom-open-suffix")
                     (alength (utf8 "prefix-"))
                     (alength (utf8 "custom-open")))]
  (with-server
    {:async? true
     :handler
     (fn [req respond _raise]
       (future
         (respond
          {:status 200
           :headers {}
           :body (case (:uri req)
                   "/seq" ["abc" "def"]
                   "/custom-open" custom-open
                   "/custom-close" (->SelfClosingBody (utf8 "custom-close"))
                   ["missing"])})))}
    (fn [p]
      (doseq [[path expected] [["/seq" "abcdef"]
                               ["/custom-open" "custom-open"]
                               ["/custom-close" "custom-close"]]]
        (let [wire   (request p (get-request path))
              {:keys [responses error trailing]}
              (model/read-responses (model/->octets wire))
              parsed (first responses)]
          (check (str "async " path " response is fully terminated")
                 [nil [] 1] [error trailing (count responses)])
          (check (str "async " path " response body is intact")
                 expected (model/body-str parsed))
          (check (str "async " path " emits exactly one status line")
                 1 (count (re-seq #"HTTP/1\.1 " wire)))))))))

(defn- test-flushable-stream-emits-before-close []
  (let [flushed (promise)
        release (promise)
        stream (->FlushBeforeCloseBody flushed release)]
    (with-server (fn [_] {:status 200
                          :headers {"Content-Type" "text/event-stream"}
                          :body stream})
      (fn [p]
        (let [fd (net/connect-loopback p)]
          (try
            (net/client-send-all fd (utf8 (get-request "/events")))
            (check "live body reaches its explicit flush boundary"
                   true (deref flushed 2000 false))
            (let [received (future (some-> (net/client-recv fd 8192) ->str))
                  prefix (deref received 2000 :timeout)]
              (check "flushed live event arrives before response close"
                     true
                     (and (string? prefix)
                          (str/includes? prefix "event: ready\n\n"))))
            (finally
              (deliver release true)
              (net/close! fd))))))))

(defn- test-async-raise []
  (with-server {:async? true
                :error-logger (fn [_])
                :handler (fn [_req _respond raise]
                           (future (Thread/sleep 40) (raise (ex-info "async boom" {}))))}
    (fn [p]
      (check "async raise -> 500" 500 (status-of (request p (get-request "/")))))))

(defn- test-async-synchronous-throw []
  (let [logged (atom [])]
    (with-server {:async? true
                  :error-logger #(swap! logged conj %)
                  :handler (fn [_request _respond _raise]
                             (throw (ex-info "async handler threw" {})))}
      (fn [p]
        (check "bodyless async handler throw -> 500"
               500
               (status-of (request p (get-request "/throw"))))
        (check "streaming-request async handler throw -> 500"
               500
               (status-of
                (request p
                         (str "POST /throw HTTP/1.1\r\n"
                              "Host: localhost\r\n"
                              "Content-Length: 3\r\n"
                              "Connection: close\r\n\r\nabc"))))
        (check "both synchronous async-handler failures remain observable"
               ["async handler threw" "async handler threw"]
               (mapv ex-message @logged))))))

(defn- test-concurrency
  "The pool-size deadlock guard: many simultaneous connections, each with a
  handler slow enough that they overlap."
  []
  (with-server {:handler (fn [_] (Thread/sleep 120)
                           {:status 200 :headers {} :body "concurrent"})}
    (fn [p]
      (let [n       24
            results (atom [])
            fs      (doall
                     (for [_ (range n)]
                       (future
                         (try
                           (let [r (request p (get-request "/"))]
                             (swap! results conj (status-of r)))
                           (catch :default e
                             (swap! results conj (str "ERR " (ex-message e))))))))]
        (doseq [f fs] (deref f))
        (check (str n " concurrent requests all 200")
               (repeat n 200) (seq @results))))))

(defn- test-pool-size-concurrent-sequential-responses []
  ;; Every handler in the bounded pool reaches the generic sequential-body path
  ;; together. Each then blocks on socket write completion; only TCP's separate
  ;; callback executor can release them.
  (let [pool-size 3
        started   (atom 0)
        release   (promise)
        part      (apply str (repeat 997 "x"))
        parts     (vec (repeat 201 part))
        payload   (apply str parts)
        handler   (fn [_]
                    (when (= pool-size (swap! started inc))
                      (deliver release true))
                    (deref release 3000 :timeout)
                    {:status 200 :headers {} :body parts})]
    (with-server {:handler handler :pool-size pool-size}
      (fn [p]
        (let [calls   (mapv (fn [_]
                              (future (request p (get-request "/seq"))))
                            (range pool-size))
              results (mapv #(deref % 15000 :timeout) calls)]
          (check "exactly pool-size sequential handlers overlap"
                 pool-size @started)
          (check "pool-size sequential responses all complete"
                 false (boolean (some #(= :timeout %) results)))
          (check "pool-size 200KB sequential responses stay byte-exact"
                 (vec (repeat pool-size payload))
                 (mapv (fn [resp]
                         (when (string? resp)
                           (model/body-str
                            (model/read-response (model/->octets resp)))))
                       results)))))))

(defn- test-executor-ownership-and-idempotent-stop []
  (let [srv   (http/run-server hello-handler :port 0 :reuse-address? true)
        owned (get-in srv [:srv :executor])]
    (try
      (check "HTTP-created executor starts live" false (.isShutdown owned))
      (check "first stop succeeds" nil (http/stop-server srv))
      (check "HTTP-created executor is adopted and shut down"
             true (.isShutdown owned))
      (check "stop is idempotent after complete cleanup"
             nil (http/stop-server srv))
      (finally
        (when @(:running? srv) (http/stop-server srv))
        (when-not (.isShutdown owned) (.shutdown owned)))))
  (let [borrowed (java.util.concurrent.Executors/newFixedThreadPool 2)
        srv      (http/run-server hello-handler :port 0 :reuse-address? true
                                  :executor borrowed)]
    (try
      (http/stop-server srv)
      (check "supplied executor remains borrowed by default"
             false (.isShutdown borrowed))
      (finally
        (when @(:running? srv) (http/stop-server srv))
        (.shutdown borrowed))))
  (let [adopted (java.util.concurrent.Executors/newFixedThreadPool 2)
        srv     (http/run-server hello-handler :port 0 :reuse-address? true
                                 :executor adopted :shutdown-executor? true)]
    (try
      (http/stop-server srv)
      (check "supplied executor can be explicitly adopted"
             true (.isShutdown adopted))
      (finally
        (when @(:running? srv) (http/stop-server srv))
        (when-not (.isShutdown adopted) (.shutdown adopted))))))

(defn- test-stop-unblocks-incomplete-streaming-body []
  (let [first-chunk (promise)
        finished    (promise)
        handler     (fn [req _respond _raise]
                      (let [request-body (:body req)
                            first        (body/body-recv request-body)]
                        (deliver first-chunk (->str first))
                        ;; This second receive is deliberately blocked until
                        ;; TCP invokes the connection close arity and HTTP closes
                        ;; the request-body channel.
                        (deliver finished
                                 (nil? (body/body-recv request-body)))))
        srv         (http/run-server handler :async? true :port 0
                                     :reuse-address? true
                                     :stop-timeout-ms 3000)
        client      (net/connect-loopback (:port srv))]
    (try
      (net/client-send-all
       client
       (utf8 (str "POST / HTTP/1.1\r\nHost: localhost\r\n"
                  "Content-Length: 100\r\n\r\nabc")))
      (check "streaming handler receives the available prefix"
             "abc" (deref first-chunk 2000 :timeout))
      (let [stopping (future (http/stop-server srv))]
        (check "stop completes with an incomplete streaming request"
               nil (deref stopping 5000 :timeout))
        (check "stop closes the request-body channel and unblocks its handler"
               true (deref finished 1000 false)))
      (finally
        (net/close! client)
        (when @(:running? srv) (http/stop-server srv))))))

(defn- test-peer-reset-unblocks-streaming-response []
  ;; Deterministically inject the transport outcome a real reset produces, but
  ;; only after the client request is fully sent and the async handler is parked
  ;; at a gate. This avoids timing a kernel RST while still exercising the whole
  ;; jolt-http -> jolt-tcp failure path.
  (let [native-write jnet/try-write-bytes!
        fail?        (atom false)
        started      (promise)
        release      (promise)
        observed     (promise)
        reset-ex     (ex-info "synthetic peer reset"
                              {:jolt.net/kind :connection-reset})
        chunk        (byte-array 65536)
        handler      (fn [_req respond _raise]
                       (deliver started true)
                       @release
                       (try
                         (respond {:status 200 :headers {} :body [chunk chunk]})
                         (deliver observed :returned)
                         (catch :default e
                           (deliver observed e))))]
    (with-redefs
      [jnet/try-write-bytes!
       (fn [socket bs off len]
         (if @fail?
           (throw reset-ex)
           (native-write socket bs off len)))]
      (let [srv    (http/run-server handler :async? true :port 0
                                    :reuse-address? true
                                    :error-logger (fn [_])
                                    :stop-timeout-ms 3000)
            client (net/connect-loopback (:port srv))]
        (try
          (net/client-send-all client (utf8 (get-request "/reset")))
          (check "peer-reset handler reached its deterministic gate"
                 true (deref started 2000 false))
          (reset! fail? true)
          (deliver release true)
          (let [outcome (deref observed 3000 :timeout)]
            (check "peer reset unblocks the async response task"
                   false (= :timeout outcome))
            (check-pred
             "peer reset is surfaced directly or as the closed-socket cause"
             (fn [[kind cause-kind]]
               (or (= :connection-reset kind)
                   (and (= :teensyp.server/socket-closed kind)
                        (= :connection-reset cause-kind))))
             (when (and (not= :timeout outcome)
                        (not= :returned outcome))
               [(or (:jolt.net/kind (ex-data outcome))
                    (:err (ex-data outcome)))
                (some-> outcome ex-cause ex-data :jolt.net/kind)])))
          (check "server quiesces after a response write failure"
                 nil (http/stop-server srv))
          (finally
            (net/close! client)
            (when @(:running? srv) (http/stop-server srv))))))))

(defn- test-date-header-format []
  (with-server hello-handler
    (fn [p]
      (let [d (header-of (request p (get-request "/")) "Date")]
        (check-pred "date is RFC-1123"
                    #(re-matches #"[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT" %)
                    d)))))

;; --- unit-ish checks -------------------------------------------------------

(defn- test-date-formatting []
  (check "epoch"        "Thu, 01 Jan 1970 00:00:00 GMT" (date/format-millis 0))
  (check "rfc example"  "Sun, 06 Nov 1994 08:49:37 GMT" (date/format-millis 784111777000))
  (check "leap 2000"    "Tue, 29 Feb 2000 00:00:00 GMT" (date/format-millis 951782400000))
  ;; 2024-02-29 was a Thursday; the century/leap terms of the civil-date
  ;; conversion are what get this wrong when mis-signed.
  (check "leap 2024"    "Thu, 29 Feb 2024 00:00:00 GMT" (date/format-millis 1709164800000))
  (check "pre-1970"     "Wed, 31 Dec 1969 23:59:59 GMT" (date/format-millis -1000))
  (check "non-leap 1900" "Thu, 01 Mar 1900 00:00:00 GMT" (date/format-millis -2203891200000)))

(defn- test-charset-parsing []
  (check "charset token"  "utf-8"
         (body/content-charset {"content-type" "text/html; charset=utf-8"}))
  (check "charset quoted" "utf-8"
         (body/content-charset {"content-type" "text/html; charset=\"utf-8\""}))
  (check "charset absent" nil
         (body/content-charset {"content-type" "text/html"})))

;; --- generative layers -----------------------------------------------------

(defn- run-clojure-test-ns [ns-sym]
  (let [s (clojure.test/run-tests ns-sym)]
    (swap! failures + (+ (:fail s 0) (:error s 0)))
    (swap! checks + (:pass s 0))))

(defn- test-pure-properties []
  (run-clojure-test-ns 'jolt.http.body-property-test))

(defn- test-protocol-properties []
  (run-clojure-test-ns 'jolt.http.protocol-property-test))

(defn- test-loopback-properties []
  (let [before (jolt.http.server-property-test/failure-count)]
    (jolt.http.server-property-test/run-properties!)
    (swap! failures + (- (jolt.http.server-property-test/failure-count) before))))

(defn- test-inert-aspect-manifest []
  (let [resource (io/resource "META-INF/jolt/aspects/http-server.edn")
        manifest (some-> resource slurp edn/read-string)
        [handler-aspect response-aspect] (:aspects manifest)]
    (check-pred "server aspect manifest is on the ordinary classpath" some? resource)
    (check "server aspect manifest schema" 1 (:schema manifest))
    (check "server aspect manifest library"
           {:id 'casselc/jolt-http
            :version "3ef772262308bbf6039412366ae80690cec348b0"}
           (:library manifest))
    (check "server aspect selects the normalized Ring lifecycle owner"
           {:entry 'jolt.http.protocol/invoke-handler :arity 8}
           (:match handler-aspect))
    (check "server aspect is inert and provider-neutral"
           {:id :http/server-ring-handler
            :advice-role :http/server
            :expect {:matches 1}}
           (select-keys handler-aspect [:id :advice-role :expect]))
    (check "server response aspect observes the sanitized response"
           {:id :http/server-sanitized-response
            :match {:entry 'jolt.http.protocol/sanitize-response :arity 1}
            :advice-role :http/server-response
            :expect {:matches 1}}
           response-aspect)))

;; --- runner ----------------------------------------------------------------

(def ^:private scenarios
  [["inert aspect manifest" test-inert-aspect-manifest]
   ["date formatting"      test-date-formatting]
   ["charset parsing"      test-charset-parsing]
   ["basic response"       test-basic]
   ["request map"          test-request-map]
   ["request methods"      test-methods]
   ["request headers"      test-headers]
   ["response headers"     test-response-headers]
   ["response metadata"    test-response-metadata-fails-closed]
   ["body types"           test-body-types]
   ["offset streamable body" test-guarded-offset-streamable-body]
   ["file body"            test-file-body]
   ["request body"         test-request-body]
   ["large request body"   test-large-request-body]
   ["large response body"  test-large-response-body]
   ["keep-alive"           test-keep-alive]
   ["pipelining"           test-pipelining]
   ["connection close"     test-connection-close]
   ["split request"        test-split-request]
   ["protocol errors"      test-errors]
   ["HEAD no body"         test-head-has-no-body]
   ["HEAD keep-alive"      test-head-keep-alive]
   ["smuggling hardening"  test-request-smuggling-hardening]
   ["chunk trailers"       test-chunked-trailers-and-extensions]
   ["message syntax"       test-conformance-message-syntax]
   ["no-content statuses"  test-conformance-no-content-statuses]
   ["header injection"     test-response-header-injection]
   ["parser vectors"       test-parser-vectors]
   ["half-close"           test-half-close]
   ["incomplete request EOF" test-incomplete-request-eof-is-terminal]
   ["expect 100-continue"  test-expect-continue]
   ["invalid host value"   test-invalid-host-value]
   ["chunk terminator"     test-chunk-terminator]
   ["smuggling: chunk-size"        test-smuggling-chunk-size]
   ["smuggling: content-length"    test-smuggling-content-length]
   ["smuggling: transfer-encoding" test-smuggling-transfer-encoding]
   ["smuggling: request-line"      test-smuggling-request-line]
   ["smuggling: pipelining"        test-smuggling-pipelining]
   ["oversized request"    test-oversized]
   ["aggregate header limits" test-aggregate-header-limits]
   ["Content-Length overflow" test-content-length-overflow]
   ["exception handling"   test-exception-handling]
   ["exception cleanup"    test-close-after-spent-parser-state]
   ["peer disconnect logging" test-peer-disconnect-is-not-logged]
   ["custom error handler" test-custom-error-handler]
   ["async handler"        test-async-handler]
   ["async streamable bodies" test-async-streamable-bodies-finalize]
   ["flushable stream"     test-flushable-stream-emits-before-close]
   ["async raise"          test-async-raise]
   ["async synchronous throw" test-async-synchronous-throw]
   ["date header format"   test-date-header-format]
   ["concurrency"          test-concurrency]
   ["pool-size sequential responses"
    test-pool-size-concurrent-sequential-responses]
   ["executor ownership"   test-executor-ownership-and-idempotent-stop]
   ["stop streaming body"  test-stop-unblocks-incomplete-streaming-body]
   ["peer reset response"  test-peer-reset-unblocks-streaming-response]

   ;; Generative layers (jolt-hegel). The pure and in-process properties run
   ;; under clojure.test via hegel.clojure-test/with; the loopback properties
   ;; use hegel.core/run-test! directly and count their own failures. Both fold
   ;; into the same total, so `jolt -M:test` stays the single gate.
   ;;
   ;; They get a longer watchdog than the acceptance scenarios: each runs
   ;; hundreds of generated cases, and on a shrink it replays the property again
   ;; from scratch.
   ["pure properties"      test-pure-properties      180000]
   ["protocol properties"  test-protocol-properties  300000]
   ["loopback properties"  test-loopback-properties  600000]])

;; Progress is written to a file as well as printed: jolt block-buffers stdout
;; when it is piped or redirected, so on a hang the printed output is lost and
;; this file is the only record of how far the run got.
(def ^:private progress-file "/tmp/jolt-http-test-progress.log")

(defn -main [& args]
  (let [only (set args)]
    (spit progress-file "start\n")
    (doseq [[label f timeout-ms] scenarios]
      (when (or (empty? only) (contains? only label))
        (println (str "\n== " label " =="))
        (spit progress-file (str "BEGIN " label "\n") :append true)
        ;; Watchdog. The loopback client does a blocking recv with no socket
        ;; timeout, so a response that never arrives would hang the run forever
        ;; instead of failing it. Bound each scenario and report a timeout as a
        ;; failure so CI stays informative.
        (let [done (future
                     (try (f) :ok
                          (catch :default e
                            (swap! failures inc)
                            (println "FAIL" label "threw:" (ex-message e))
                            :threw)))]
          (when (= :TIMEOUT (deref done (or timeout-ms 60000) :TIMEOUT))
            (swap! failures inc)
            (println "FAIL" label "timed out after" (or timeout-ms 60000) "ms")))
        (spit progress-file (str "END   " label "\n") :append true))))
  (println (str "\n" @checks " checks, " @failures " failures"))
  (flush)
  ;; core.async holds non-daemon threads; the process will not exit on its own.
  (System/exit (if (pos? @failures) 1 0)))
