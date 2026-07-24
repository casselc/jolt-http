(ns jolt.http.protocol-property-test
  "Generative conformance properties for the HTTP/1.1 state machine, driven
  in-process through the `teensyp.server/Socket` fake in jolt.http.fake-socket.

  The acceptance suite in jolt.http.server-test pins one input to one expected
  output — and, for the framing tests, one *split point*: `\"GET / HTTP/1.1\\r\\nHo\"`
  then `\"st: localhost...\"`. But an incremental parser is correct only if it
  holds for every split, and a keep-alive connection is correct only if it holds
  for every sequence of requests. Those are the two axes these properties let
  Hegel choose.

  Running in-process rather than over loopback is what makes that affordable: no
  connect, no port, no sleep, so a property explores hundreds of generated
  message streams in the time a handful of loopback cases take. The loopback
  properties in jolt.http.server-property-test cover the same behaviour over
  real transport, so a divergence shows up as one layer passing and the other
  not.

  Expected values come from jolt.http.http-model — an HTTP/1.1 response reader
  written from RFC 9112, never from jolt.http.protocol. A property whose oracle
  is the code under test proves nothing.

  Failures print a seed; replay it with (parse-long seed) as :seed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hegel.clojure-test :refer [with]]
            [hegel.core :as h]
            [hegel.generator :as g]
            [hegel.stateful :as hs]
            [jolt.http.body :as body]
            [jolt.http.fake-socket :as fs]
            [jolt.http.http-model :as m]
            [jolt.http.reason :as reason]))

(def ^:private opts
  {:test-cases 200 :database "" :verbosity :quiet})

;; --- handlers --------------------------------------------------------------

(defn- echo-request-handler
  "Answers with a readable rendering of the parsed request, so a property can
  compare the whole request map against its model. The body is rendered as
  unsigned octets rather than a string: a generated body is arbitrary bytes and
  a UTF-8 round trip would silently replace the invalid ones."
  [req]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    (pr-str {:request-method (:request-method req)
                     :uri            (:uri req)
                     :query-string   (:query-string req)
                     :protocol       (:protocol req)
                     :headers        (:headers req)
                     :body           (m/->octets (body/body-bytes (:body req)))})})

(defn- hello-handler [_]
  {:status 200 :headers {"Content-Type" "text/plain"} :body "Hello World"})

(defn- path-handler
  "Echoes the request target, so a pipelining property can check that response
  *i* is the answer to request *i* and not merely that N responses arrived."
  [req]
  {:status 200 :headers {} :body (str (:uri req))})

;; --- helpers ---------------------------------------------------------------

(defn- run-conn
  "Run `f` over a fresh connection and always release it.

  Releasing matters beyond tidiness: a handler parked in `body-bytes` on a body
  that never finished arriving — precisely what a truncated request in the fuzz
  property produces — holds a thread from the harness's shared pool until the
  connection's close arity closes the body channel."
  ([handler f] (run-conn handler {} f))
  ([handler conn-opts f]
   (let [conn (fs/ring-conn handler conn-opts)]
     (try (f conn) (finally (fs/close-conn! conn))))))

(defn- responses-of
  "Every complete response the connection has written so far. `heads` marks the
  positions that answered a HEAD request, because a HEAD response's framing
  headers describe a body that was not sent."
  ([conn] (responses-of conn nil))
  ([conn heads] (m/read-responses (fs/written conn) heads)))

(defn- await-responses!
  "Wait until `n` complete responses have been written, or give up. A response
  from the streaming-body path is produced on an executor thread, so it is not
  visible the instant feed! returns; reaching the bound leaves the caller's
  assertions to fail, which is the point — it is never a reason to look again
  later."
  ([conn n] (await-responses! conn n nil))
  ([conn n heads]
   (fs/await! conn #(>= (count (:responses (responses-of conn heads))) n))
   (responses-of conn heads)))

(defn- feed-chunked!
  "Deliver `octets` to the connection in the given chunk sizes — the reactor's
  view of a client that wrote in several pieces."
  [conn chunks]
  (doseq [c chunks] (fs/feed-all! conn c)))

;; --- 1. framing is invariant under chunking --------------------------------

(deftest request-parsing-is-invariant-under-chunking
  (with (assoc opts :test-cases 150 :name "protocol/chunking-invariance")
        []
    ;; The whole point: the acceptance suite proves one split works. Framing
    ;; correctness has to hold for *every* split, including a byte at a time,
    ;; and with a read buffer small enough that the buffer-full/compact path
    ;; runs repeatedly inside a single request.
        (g/let [{:keys [bytes model]} (m/draw-request!)
                chunks (g/chunkings bytes)]
          (run-conn
           echo-request-handler {:read-buffer-size 512}
           (fn [conn]
             (feed-chunked! conn chunks)
             (let [{:keys [responses error trailing]} (await-responses! conn 1)
                   r (first responses)]
               (h/fprn :minimal-request (m/octets->str (take 80 bytes))
                       :chunk-sizes (mapv count chunks))
               (is (nil? error) (str "response stream is well formed: " (pr-str error)))
               (is (empty? trailing) "no bytes left over after the response")
               (is (= 1 (count responses)) "exactly one response")
               (when r
                 (is (= 200 (:status r)))
                 (let [got (read-string (m/body-str r))]
                   (is (= (:request-method model) (:request-method got)))
                   (is (= (:uri model) (:uri got)))
                   (is (= (:query-string model) (:query-string got)))
                   (is (= (:protocol model) (:protocol got)))
                   (is (= (:body model) (:body got)) "request body arrived intact")))))))))

;; --- 2. the request map agrees with the model ------------------------------

(deftest request-headers-agree-with-the-model
  (with (assoc opts :test-cases 150 :name "protocol/header-model")
        []
    ;; Names lower-cased, values OWS-trimmed, repeated fields comma-joined in
    ;; arrival order. The parse-vector table in server-test pins thirteen
    ;; examples of this; the rule holds over the whole token/field-value domain.
        (g/let [{:keys [bytes model]} (m/draw-request!)]
          (run-conn
           echo-request-handler
           (fn [conn]
             (fs/feed-all! conn bytes)
             (let [{:keys [responses error]} (await-responses! conn 1)
                   r (first responses)]
               (h/fprn :minimal-request (m/octets->str (take 120 bytes)))
               (is (nil? error))
               (when r
                 (let [got (:headers (read-string (m/body-str r)))]
               ;; The generated fields must all be present and exactly right;
               ;; the server also adds host and may add framing fields, so this
               ;; compares the generated names only.
                   (doseq [[k v] (:headers model)]
                     (is (= v (get got k)) (str "header " (pr-str k))))))))))))

(deftest aggregate-header-count-is-an-exact-boundary
  (with (assoc opts :test-cases 120 :name "protocol/header-count-boundary")
        [extra-fields (g/integer 0 8)
         cap          (g/integer 1 9)]
        (let [field-lines (mapv (fn [i] [(str "X-" i) "v"])
                                (range extra-fields))
              raw         (m/render-request {:method "GET"
                                             :target "/"
                                             :field-lines field-lines
                                             :body []
                                             :body-framing :none})
              actual      (inc extra-fields)]
          (run-conn
           hello-handler {:max-header-count cap :max-header-bytes 65536}
           (fn [conn]
             (fs/feed-all! conn raw)
             (let [{:keys [responses error trailing]} (await-responses! conn 1)
                   r (first responses)]
               (h/fprn :minimal-extra-fields extra-fields
                       :actual-fields actual :cap cap)
               (is (nil? error))
               (is (empty? trailing))
               (is (= 1 (count responses)))
               (when r
                 (is (= (if (<= actual cap) 200 431) (:status r))))))))))

(deftest aggregate-header-bytes-include-the-final-crlf
  (with (assoc opts :test-cases 120 :name "protocol/header-byte-boundary")
        [extra-fields (g/integer 0 6)
         value-size   (g/integer 0 16)
         delta        (g/integer -1 1)]
        (let [value       (apply str (repeat value-size "v"))
              field-lines (mapv (fn [i] [(str "X-" i) value])
                                (range extra-fields))
              raw         (m/render-request {:method "GET"
                                             :target "/"
                                             :field-lines field-lines
                                             :body []
                                             :body-framing :none})
              request-line-end (str/index-of (m/octets->str raw) "\r\n")
              section-bytes (- (count raw) (+ request-line-end 2))
              cap           (+ section-bytes delta)]
          (run-conn
           hello-handler {:max-header-count 100 :max-header-bytes cap}
           (fn [conn]
             (fs/feed-all! conn raw)
             (let [{:keys [responses error trailing]} (await-responses! conn 1)
                   r (first responses)]
               (h/fprn :minimal-extra-fields extra-fields
                       :value-size value-size
                       :section-bytes section-bytes :cap cap)
               (is (nil? error))
               (is (empty? trailing))
               (is (= 1 (count responses)))
               (when r
                 (is (= (if (<= section-bytes cap) 200 431)
                        (:status r))))))))))

;; --- 3. the two body framings agree ----------------------------------------

(deftest content-length-and-chunked-deliver-the-same-body
  (with (assoc opts :test-cases 100 :name "protocol/framing-agreement")
        [payload (g/vector {:max-size 1024} (g/octet))]
    ;; Two independent decoders in the parser must agree. The chunked side gets
    ;; generated boundaries, mixed-case hex, chunk extensions and a trailer
    ;; section — each of which the acceptance suite pins exactly once.
        (g/let [sizes (if (seq payload)
                        (g/vector {:max-size 6} (g/integer 1 (max 1 (count payload))))
                        (g/just []))
                upper? (g/boolean)
                ext?   (g/boolean)
                trailer? (g/boolean)]
          (let [cl-bytes (m/render-request {:method "POST" :target "/x"
                                            :field-lines [] :body payload
                                            :body-framing :content-length})
                chunks   (if (seq payload) (m/split-by-sizes payload sizes) [])
                hex      (fn [n] (let [s (format "%X" n)]
                                   (if upper? s (str/lower-case s))))
                ch-body  (into (vec (mapcat (fn [c]
                                              (into (m/ascii (str (hex (count c))
                                                                  (if ext? ";ext=1" "")
                                                                  "\r\n"))
                                                    (into (vec c) (m/ascii "\r\n"))))
                                            chunks))
                               (m/ascii (if trailer?
                                          "0\r\nX-Trailer: v\r\n\r\n"
                                          "0\r\n\r\n")))
                ch-bytes (into (m/ascii (str "POST /x HTTP/1.1\r\nHost: localhost\r\n"
                                             "Transfer-Encoding: chunked\r\n\r\n"))
                               ch-body)
                body-of  (fn [raw]
                           (run-conn
                            echo-request-handler
                            (fn [conn]
                              (fs/feed-all! conn raw)
                              (let [{:keys [responses]} (await-responses! conn 1)]
                                (when-some [r (first responses)]
                                  (:body (read-string (m/body-str r))))))))]
            (h/fprn :minimal-len (count payload) :chunk-sizes (mapv count chunks)
                    :upper? upper? :ext? ext? :trailer? trailer?)
            (is (= payload (body-of cl-bytes)) "Content-Length framing delivers the body")
            (is (= payload (body-of ch-bytes)) "chunked framing delivers the same body")))))

;; --- 4. pipelining conserves requests --------------------------------------

(deftest pipelined-requests-are-answered-in-order
  (with (assoc opts :test-cases 100 :name "protocol/pipelining")
        [n (g/integer 1 6)]
    ;; The acceptance suite sends two fixed requests in one write. Keep-alive
    ;; state carried between requests — protocol/buffer-reads and the reuse of
    ;; the per-connection response buffer — is where a desync lives, and two
    ;; requests do not explore it. Here the count, the targets and the write
    ;; boundaries are all generated, and the check is positional: response i
    ;; must be the answer to request i.
        (g/let [paths  (g/vector {:size n} m/path-gen)
                stream (vec (mapcat (fn [p]
                                      (m/ascii (str "GET " p " HTTP/1.1\r\nHost: h\r\n\r\n")))
                                    paths))
                chunks (g/chunkings stream)]
          (run-conn
           path-handler {:read-buffer-size 512}
           (fn [conn]
             (feed-chunked! conn chunks)
             (let [{:keys [responses error trailing]} (await-responses! conn n)]
               (h/fprn :minimal-paths paths :chunk-sizes (mapv count chunks))
               (is (nil? error) (str "stream is well framed: " (pr-str error)))
               (is (empty? trailing) "nothing left over")
               (is (= n (count responses)) "one response per request")
               (is (= n (m/status-lines (fs/written conn)))
                   "and no extra status lines anywhere in the stream")
               (is (= paths (mapv m/body-str responses))
                   "each response answers its own request, in order")))))))

;; --- 5. HEAD is a GET without the body -------------------------------------

(deftest head-matches-get-and-carries-no-body
  (with (assoc opts :test-cases 100 :name "protocol/head")
        [path m/path-gen
         body-kind (g/sampled-from [:string :bytes :seq :nil])]
    ;; RFC 9110 9.3.2. A body on a HEAD response is not merely redundant: on a
    ;; keep-alive connection the client reads those bytes as the next response.
        (let [handler (fn [_]
                        (case body-kind
                          :string {:status 200 :headers {} :body "Hello World"}
                          :bytes  {:status 200 :headers {} :body (.getBytes "raw bytes" "UTF-8")}
                          :seq    {:status 200 :headers {} :body ["chunk1" "chunk2"]}
                          :nil    {:status 200 :headers {} :body nil}))
              drop-date #(dissoc (:headers %) "date")
              g-resp (run-conn handler
                               (fn [c]
                                 (fs/feed-all! c (m/ascii (str "GET " path " HTTP/1.1\r\nHost: h\r\n\r\n")))
                                 (first (:responses (await-responses! c 1)))))
              h-resp (run-conn handler
                               (fn [c]
                                 (fs/feed-all! c (m/ascii (str "HEAD " path " HTTP/1.1\r\nHost: h\r\n\r\n")))
                                 (first (:responses (await-responses! c 1 [true])))))]
          (h/fprn :minimal-path path :body-kind body-kind)
          (is (some? g-resp))
          (is (some? h-resp))
          (when (and g-resp h-resp)
            (is (= (:status g-resp) (:status h-resp)))
            (is (= (drop-date g-resp) (drop-date h-resp))
                "HEAD carries the header block the equivalent GET would")
            (is (empty? (:body h-resp)) "and no body")))))

(deftest head-does-not-desynchronise-a-connection
  (with (assoc opts :test-cases 60 :name "protocol/head-keepalive")
        [n (g/integer 1 4)]
    ;; A HEAD followed by more requests on the same connection: if the HEAD
    ;; response carried a body, every later response would be read at the wrong
    ;; offset. Stated over a generated sequence rather than one fixed pair.
        (g/let [paths (g/vector {:size n} m/path-gen)]
          (run-conn
           path-handler
           (fn [conn]
             (let [stream (vec (mapcat (fn [[i p]]
                                         (m/ascii (str (if (zero? i) "HEAD" "GET")
                                                       " " p " HTTP/1.1\r\nHost: h\r\n\r\n")))
                                       (map-indexed vector paths)))
                   heads  (cons true (repeat (dec n) false))]
               (fs/feed-all! conn stream)
               (let [{:keys [responses error trailing]} (await-responses! conn n heads)]
                 (h/fprn :minimal-paths paths)
                 (is (nil? error) (str "stream stays framed after a HEAD: " (pr-str error)))
                 (is (empty? trailing))
                 (is (= n (count responses)))
                 (when (seq responses)
                   (is (empty? (:body (first responses))) "the HEAD response has no body")
                   (is (= (rest paths) (map m/body-str (rest responses)))
                       "the GETs after it are still answered in order")))))))))

;; --- 6. status lines and the no-content statuses ---------------------------

(deftest status-line-carries-the-right-reason-phrase
  (with (assoc opts :test-cases 150 :name "protocol/status-line")
        [status (g/integer 100 599)]
        (run-conn
         (fn [_] {:status status :headers {} :body "x"})
         (fn [conn]
           (fs/feed-all! conn (m/ascii "GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"))
           (let [{:keys [responses error]} (await-responses! conn 1)
                 r (first responses)]
             (h/fprn :minimal-status status)
             (is (nil? error))
             (when r
               (is (= status (:status r)))
               (is (= (or (reason/status->reason status) "Unknown") (:reason r))
                   "reason phrase comes from the status table, or is Unknown")
           ;; RFC 9110 15.4.5 / RFC 9112 6.2: these statuses never carry content
           ;; whatever the handler returned.
               (when (or (< status 200) (= status 204) (= status 304))
                 (is (empty? (:body r)) "1xx, 204 and 304 carry no body"))))))))

(deftest no-content-statuses-strip-handler-framing-headers
  (with (assoc opts :test-cases 120 :name "protocol/no-content-framing")
        [status (g/one-of [(g/integer 100 199) (g/just 204)])]
    ;; RFC 9112 6.2 — a Content-Length or Transfer-Encoding must never be sent
    ;; on these, *even when the handler supplies one*. The acceptance suite
    ;; checks this for 204 only; the rule covers the whole 1xx range too.
        (run-conn
         (fn [_] {:status status
                  :headers {"Content-Length" "5" "Transfer-Encoding" "chunked"}
                  :body nil})
         (fn [conn]
           (fs/feed-all! conn (m/ascii "GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"))
           (let [{:keys [responses error]} (await-responses! conn 1)
                 r (first responses)]
             (h/fprn :minimal-status status)
             (is (nil? error))
             (when r
               (is (= status (:status r)))
               (is (nil? (m/header r "Content-Length")) "Content-Length is stripped")
               (is (nil? (m/header r "Transfer-Encoding")) "Transfer-Encoding is stripped")
               (is (empty? (:body r)) "and no body is sent")))))))

;; --- 7. response headers round trip, and cannot split the response ---------

(deftest safe-response-headers-round-trip
  (with (assoc opts :test-cases 150 :name "protocol/response-headers")
        []
        (g/let [n      (g/integer 0 4)
                names  (g/vector {:size n :unique? true} m/token-char-gen)
                values (g/vector {:size n} m/field-value-gen)]
          (let [;; Three restrictions, each from the field-value grammar rather
            ;; than from convenience:
            ;;
            ;; - a blank value cannot round-trip, because the field is written
            ;;   as "Name: <value>" and a reader trims OWS (RFC 9110 5.5);
            ;; - the fields the server owns are excluded, since it writes its
            ;;   own and the handler's would be a second copy;
            ;; - names are deduplicated case-insensitively. `:unique?` gives
            ;;   distinct strings, but field names are case-insensitive
            ;;   (RFC 9110 5.1), so "T000" and "t000" are one field emitted
            ;;   twice — a repeated header, which is a different scenario with a
            ;;   different expected value.
                pairs (->> (map vector names values)
                           (remove (fn [[_ v]] (str/blank? v)))
                           (remove (fn [[k _]]
                                     (contains? #{"content-length" "transfer-encoding"
                                                  "connection" "date" "server"}
                                                (str/lower-case k))))
                           (reduce (fn [acc [k v]]
                                     (if (some (fn [[k2 _]]
                                                 (= (str/lower-case k) (str/lower-case k2)))
                                               acc)
                                       acc
                                       (conj acc [k v])))
                                   []))]
            (run-conn
             (fn [_] {:status 200 :headers (into {} pairs) :body "x"})
             (fn [conn]
               (fs/feed-all! conn (m/ascii "GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"))
               (let [{:keys [responses error]} (await-responses! conn 1)
                     r (first responses)]
                 (h/fprn :minimal-pairs (vec pairs))
                 (is (nil? error))
                 (when r
                   (is (= 200 (:status r)))
                   (doseq [[k v] pairs]
                     (is (= (str/trim v) (m/header r k))
                         (str "response header " (pr-str k) " round-trips")))))))))))

(deftest unsafe-response-headers-cannot-split-the-response
  (with (assoc opts :test-cases 150 :name "protocol/response-splitting")
        [prefix   (g/string {:max-size 12 :codec :ascii :exclude-characters "\r\n "})
         evil     (g/sampled-from ["\r\n" "\n" "\r" " " "\r\n\r\n"])
         suffix   (g/sampled-from ["X-Injected: yes" "HTTP/1.1 200 OK" "" "evil"])
         in-name? (g/boolean)]
    ;; HTTP response splitting (CWE-113). The acceptance suite pins three fixed
    ;; payloads; the rule is that *no* CR, LF or NUL reaching a header — in a
    ;; name or a value — may produce a second status line or a header the
    ;; handler did not emit.
        (let [payload (str prefix evil suffix)]
          (run-conn
           (fn [_] {:status 200
                    :headers (if in-name? {payload "v"} {"X-Evil" payload})
                    :body "i"})
           (fn [conn]
             (fs/feed-all! conn (m/ascii "GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"))
             (let [{:keys [responses error]} (await-responses! conn 1)
                   r    (first responses)
                   wire (fs/written conn)]
               (h/fprn :minimal-payload (pr-str payload) :in-name? in-name?)
               (is (= 1 (m/status-lines wire))
                   "exactly one status line on the wire — the response was not split")
               (is (nil? error))
               (when r
                 (is (nil? (m/header r "X-Injected")) "nothing was injected"))))))))

(deftest response-metadata-is-canonical-or-fails-closed
  (with (assoc opts :test-cases 180 :name "protocol/response-metadata")
        [status-class (g/sampled-from [:valid :low :high :text :fraction
                                      :false :nil])
         cl-class     (g/sampled-from [:none :valid :duplicate :conflict
                                       :overflow :syntax])
         te-class     (g/sampled-from [:none :chunked :unsupported :duplicate])]
        (let [status  (case status-class
                        :valid 200
                        :low 99
                        :high 1000
                        :text "200"
                        :fraction 200.5
                        :false false
                        :nil nil)
              cl      (case cl-class
                        :none {}
                        :valid {"Content-Length" "5"}
                        :duplicate {"Content-Length" "5"
                                    "content-length" "5"}
                        :conflict {"Content-Length" "5"
                                   "content-length" "6"}
                        :overflow {"Content-Length" "9223372036854775808"}
                        :syntax {"Content-Length" "+5"})
              te      (case te-class
                        :none {}
                        :chunked {"Transfer-Encoding" "chunked"}
                        :unsupported {"Transfer-Encoding" "gzip"}
                        :duplicate {"Transfer-Encoding" "chunked"
                                    "transfer-encoding" "chunked"})
              invalid? (or (not= status-class :valid)
                           (contains? #{:conflict :overflow :syntax} cl-class)
                           (contains? #{:unsupported :duplicate} te-class))]
          (run-conn
           (fn [_] {:status status :headers (merge cl te) :body "HELLO"})
           (fn [conn]
             (fs/feed-all!
              conn
              (m/ascii
               "GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"))
             (let [{:keys [responses error trailing]} (await-responses! conn 1)
                   r (first responses)]
               (h/fprn :status-class status-class
                       :content-length-class cl-class
                       :transfer-encoding-class te-class)
               (is (nil? error))
               (is (empty? trailing))
               (is (= 1 (count responses)))
               (when r
                 (is (= (if invalid? 500 200) (:status r)))
                 (when-not invalid?
                   (if (= te-class :chunked)
                     (do
                       (is (= "chunked" (m/header r "Transfer-Encoding")))
                       (is (nil? (m/header r "Content-Length"))))
                     (when (contains? #{:valid :duplicate} cl-class)
                       (is (= "5" (m/header r "Content-Length")))
                       (is (= 1
                              (count
                               (get (:headers r) "content-length"))))))))))))))

;; --- 8. mutation fuzz: never two answers, never a spin ---------------------

(defn- mutate
  "Apply one structural mutation to a rendered request. These are the
  parsing-discrepancy classes the HTTP Garden project catalogues, generated
  rather than enumerated."
  [octets kind k]
  (let [n (count octets)
        i (if (pos? n) (mod k n) 0)]
    (case kind
      :truncate     (subvec octets 0 i)
      :flip-byte    (if (pos? n) (assoc octets i (bit-xor (nth octets i) 0xff)) octets)
      :insert-cr    (vec (concat (subvec octets 0 i) [13] (subvec octets i)))
      :insert-lf    (vec (concat (subvec octets 0 i) [10] (subvec octets i)))
      :insert-nul   (vec (concat (subvec octets 0 i) [0] (subvec octets i)))
      :insert-space (vec (concat (subvec octets 0 i) [32] (subvec octets i)))
      :drop-byte    (if (pos? n) (vec (concat (subvec octets 0 i) (subvec octets (inc i)))) octets)
      :duplicate    (vec (concat octets (subvec octets 0 i)))
      octets)))

(def ^:private mutation-kinds
  [:truncate :flip-byte :insert-cr :insert-lf :insert-nul :insert-space
   :drop-byte :duplicate])

(def ^:private documented-error-statuses
  "Every status jolt.http.error can produce, plus the 500 a handler fault gives.
  A malformed request answered with anything else is unclassified."
  #{400 414 417 431 500 501 505})

(deftest a-mutated-request-is-answered-safely-and-terminally
  (with (assoc opts :test-cases 200 :name "protocol/mutation-fuzz")
        []
    ;; Whatever byte stream arrives, four things must hold. None of them can be
    ;; stated by a fixed payload, because the interesting inputs are the ones
    ;; nobody thought to write down.
    ;;
    ;;   1. everything written is a well-formed response, with nothing trailing;
    ;;   2. every status is one the server is documented to produce;
    ;;   3. an error response is *terminal* — it is the last thing written and
    ;;      the connection closes behind it. This is what
    ;;      protocol/write-error-response promises: re-entering the error step
    ;;      would write the response twice and tear the socket down before the
    ;;      first had drained;
    ;;   4. the read pump always makes progress (fake-socket/pump! throws
    ;;      otherwise), so no input can spin the parser.
    ;;
    ;; Note what is deliberately NOT asserted: that a mutated stream produces at
    ;; most one response. A mutation can turn one request into two well-framed
    ;; messages — inserting a bare LF is enough, since RFC 9112 2.2 lets a
    ;; recipient treat a lone LF as a line terminator — and answering both is
    ;; correct pipelining rather than a defect. Whether that framing latitude
    ;; should be given up is a separate question; see the bare-LF note in the
    ;; README's conformance section.
        (g/let [{:keys [bytes]} (m/draw-request!)
                kind   (g/sampled-from mutation-kinds)
                k      (g/integer 0 4096)
                chunks (g/chunkings (mutate (vec bytes) kind k))]
          (run-conn
           hello-handler {:read-buffer-size 512}
           (fn [conn]
             (feed-chunked! conn chunks)
         ;; A truncated request legitimately produces nothing: the server is
         ;; waiting for the rest, and accepting none is correct.
             (fs/await! conn #(seq (:responses (responses-of conn))) 150)
             (let [{:keys [responses error trailing]} (responses-of conn)]
               (h/fprn :minimal-kind kind :k k :bytes-len (count bytes))
               (when (seq responses)
                 (is (nil? error) (str "everything written is well formed: " (pr-str error)))
                 (is (empty? trailing) "with nothing trailing it")
                 (doseq [r responses]
                   (is (or (= 200 (:status r))
                           (contains? documented-error-statuses (:status r)))
                       (str "status " (:status r) " is one the server is documented to send")))
                 (let [errs (keep-indexed
                             (fn [i r]
                               (when (contains? documented-error-statuses (:status r)) i))
                             responses)]
                   (when (seq errs)
                     (is (= [(dec (count responses))] (vec errs))
                         "an error response is the last one written")
                     (is (fs/closed? conn)
                         "and the connection is closed behind it"))))))))))

(deftest terminal-eof-rejects-every-nonempty-request-prefix
  (with (assoc opts :test-cases 180 :name "protocol/truncated-eof")
        []
        (g/let [{:keys [bytes]} (m/draw-request!)
                cut (g/integer 0 (dec (count bytes)))]
          (run-conn
           ;; The handler drains the request body. A handler that intentionally
           ;; answers before consuming a streaming body may legitimately win
           ;; the response slot before a later EOF exposes truncation; this
           ;; property is about parser states that are still waiting.
           echo-request-handler {:read-buffer-size 512}
           (fn [conn]
             (fs/feed-all! conn (subvec (vec bytes) 0 cut))
             (fs/half-close! conn)
             (fs/await! conn #(fs/closed? conn))
             (let [{:keys [responses error trailing]} (responses-of conn)]
               (h/fprn :minimal-cut cut :request-size (count bytes))
               (is (fs/closed? conn)
                   "terminal EOF cannot leave a parser state waiting for bytes")
               (is (nil? error))
               (is (empty? trailing))
               (if (zero? cut)
                 (is (empty? responses)
                     "an idle connection can close without an HTTP response")
                 (do
                   (is (= 1 (count responses))
                       "a nonempty incomplete message gets one response")
                   (when-some [r (first responses)]
                     (is (= 400 (:status r))))))))))))

(deftest terminal-eof-does-not-duplicate-an-already-claimed-response
  (run-conn
   ;; Deliberately answer without consuming the streaming body. Once this 202
   ;; has claimed the request's response slot, later truncation must close the
   ;; connection rather than append a 400.
   (fn [_] {:status 202 :headers {} :body "accepted"})
   (fn [conn]
     (fs/feed-all!
      conn
      (m/ascii (str "POST / HTTP/1.1\r\nHost: h\r\n"
                    "Content-Length: 5\r\n\r\n")))
     (let [{before :responses} (await-responses! conn 1)]
       (is (= [202] (mapv :status before))
           "the handler claims the response before terminal EOF"))
     (fs/feed-all! conn (m/ascii "abc"))
     (fs/half-close! conn)
     (fs/await! conn #(fs/closed? conn))
     (let [{:keys [responses error trailing]} (responses-of conn)]
       (is (fs/closed? conn))
       (is (nil? error))
       (is (empty? trailing))
       (is (= [202] (mapv :status responses))
           "body truncation does not append a second response")
       (is (= 1 (m/status-lines (fs/written conn)))
           "exactly one response was accepted")))))

(deftest a-well-formed-request-is-never-rejected
  (with (assoc opts :test-cases 150 :name "protocol/no-false-rejection")
        []
    ;; The direction conformance suites usually miss. Every rule in the README's
    ;; rejection list has a payload that must be refused; nothing tests that a
    ;; request obeying all of them is accepted. A parser that rejects valid
    ;; traffic is as broken as one that accepts invalid traffic.
        (g/let [{:keys [bytes]} (m/draw-request!)
                chunks (g/chunkings bytes)]
          (run-conn
           hello-handler {:read-buffer-size 2048}
           (fn [conn]
             (feed-chunked! conn chunks)
             (let [{:keys [responses error]} (await-responses! conn 1)
                   r (first responses)]
               (h/fprn :minimal-request (m/octets->str (take 200 bytes)))
               (is (nil? error))
               (is (= 1 (count responses)))
               (when r
                 (is (= 200 (:status r))
                     (str "well-formed request answered " (:status r) ": "
                          (pr-str (m/octets->str (take 200 bytes))))))))))))

;; --- 9. stateful connection model ------------------------------------------

(defn- expected-response-for [kind path]
  (case kind
    :head {:head? true :status 200 :body ""}
    {:head? false :status 200 :body path}))

(deftest connection-stateful-model
  (with (assoc opts :test-cases 120 :name "protocol/stateful")
        []
    ;; The properties above each drive one message stream. A real connection is
    ;; a *sequence*: requests arrive interleaved with partial writes, HEADs, and
    ;; eventually a half-close, and the parser carries state between them. This
    ;; generates those sequences and checks a model of what must have been
    ;; answered, in order, at every step.
    ;;
    ;; The connection is built inside the property body, so every generated case
    ;; gets a fresh one.
        (run-conn
         path-handler {:read-buffer-size 1024}
         (fn [conn]
           (hs/run!
            {:initial-state {:conn conn :pending [] :drained [] :partial nil :eof? false}
             :rules
             [;; a complete request, written in one go
              (hs/rule :send-request
                       {:precondition (fn [{:keys [partial eof?]}] (and (nil? partial) (not eof?)))}
                       (fn [{:keys [conn pending] :as state}]
                         (let [path (h/draw! m/path-gen)]
                           (fs/feed-all! conn (m/ascii (str "GET " path " HTTP/1.1\r\nHost: h\r\n\r\n")))
                           (assoc state :pending (conj pending (expected-response-for :get path))))))

          ;; a HEAD, whose response must carry no body — the framing hazard
              (hs/rule :send-head
                       {:precondition (fn [{:keys [partial eof?]}] (and (nil? partial) (not eof?)))}
                       (fn [{:keys [conn pending] :as state}]
                         (let [path (h/draw! m/path-gen)]
                           (fs/feed-all! conn (m/ascii (str "HEAD " path " HTTP/1.1\r\nHost: h\r\n\r\n")))
                           (assoc state :pending (conj pending (expected-response-for :head path))))))

          ;; half a request now, the rest later: the parser must hold its state
          ;; across the gap, including when another request was answered between
              (hs/rule :send-partial
                       {:precondition (fn [{:keys [partial eof?]}] (and (nil? partial) (not eof?)))}
                       (fn [{:keys [conn] :as state}]
                         (let [path (h/draw! m/path-gen)
                               raw  (m/ascii (str "GET " path " HTTP/1.1\r\nHost: h\r\n\r\n"))
                               cut  (h/draw! (g/integer 1 (dec (count raw))))]
                           (fs/feed-all! conn (subvec raw 0 cut))
                           (assoc state :partial {:rest (subvec raw cut) :path path}))))

              (hs/rule :finish-partial
                       {:precondition (fn [{:keys [partial eof?]}] (and (some? partial) (not eof?)))}
                       (fn [{:keys [conn partial pending] :as state}]
                         (fs/feed-all! conn (:rest partial))
                         (assoc state
                                :partial nil
                                :pending (conj pending (expected-response-for :get (:path partial))))))

          ;; the peer half-closes: everything already sent must still be answered
              (hs/rule :half-close
                       {:precondition (fn [{:keys [eof?]}] (not eof?))}
                       (fn [{:keys [conn partial pending] :as state}]
                         (fs/half-close! conn)
                         (cond-> (assoc state :eof? true)
                           partial
                           (assoc :partial nil
                                  :pending
                                  (conj pending
                                        {:head? false
                                         :status 400
                                         :body "Request ended before its framing was complete."})))))

          ;; read one response off the wire and check it is the right one
              (hs/rule :drain-one
                       {:precondition (fn [{:keys [pending]}] (seq pending))}
                       (fn [{:keys [conn pending drained] :as state}]
                         (let [want  (count drained)
                               heads (map :head? (concat drained pending))
                               got   (await-responses! conn (inc want) heads)
                               rs    (:responses got)]
                           (when (<= (count rs) want)
                             (throw (ex-info "a queued response never arrived"
                                             {:hegel/origin "protocol/stateful/missing-response"
                                              :expected (inc want) :got (count rs)
                                              :error (:error got)})))
                           (let [r    (nth rs want)
                                 spec (first pending)]
                             (when (not= (:status spec) (:status r))
                               (throw (ex-info "unexpected status"
                                               {:hegel/origin "protocol/stateful/status"
                                                :want (:status spec)
                                                :status (:status r)})))
                             (when-not (= (:body spec) (m/body-str r))
                               (throw (ex-info "response does not match its request"
                                               {:hegel/origin "protocol/stateful/out-of-order"
                                                :want (:body spec) :got (m/body-str r)})))
                             (assoc state
                                    :pending (vec (rest pending))
                                    :drained (conj drained spec))))))]

             :invariants
             [;; Never more answers on the wire than requests that have been fully
          ;; sent. More status lines than that is a duplicated or split response.
              (hs/invariant :no-extra-responses
                            (fn [{:keys [conn pending drained]}]
                              (<= (m/status-lines (fs/written conn))
                                  (+ (count pending) (count drained)))))
          ;; Everything on the wire parses cleanly, which is what "the
          ;; connection is still in sync" means. A response still being written
          ;; is incomplete, not malformed.
              (hs/invariant :stream-stays-framed
                            (fn [{:keys [conn pending drained]}]
                              (let [heads (map :head? (concat drained pending))
                                    r     (m/read-responses (fs/written conn) heads)]
                                (or (nil? (:error r))
                                    (contains? #{:incomplete-headers :incomplete-body
                                                 :incomplete-chunked :no-status-line}
                                               (:error r))))))
          ;; A ::close must be the last thing queued: anything after it can
          ;; never reach the client.
              (hs/invariant :close-is-final
                            (fn [{:keys [conn]}] (fs/close-is-last? conn)))]})
           (is true "stateful sequence completed without an invariant violation")))))
