(ns jolt.http.body-property-test
  "Generative properties for the pure layers of jolt-http: the RFC-1123 date
  arithmetic in jolt.http.date, and the writer/sink machinery in jolt.http.body.

  These need no socket and no server, so they run in milliseconds and cannot
  flake. They are also the parts of the codebase with the least direct coverage:
  jolt.http.date is pinned by six hand-picked dates, and `bytes-writer`,
  `limit-writer`, `chunk-writer`, `file-writer`, `chunked-sink` and
  `limited-sink` have no direct test at all — they are only reached incidentally
  through the server, at buffer sizes where their partial-fill and
  flush-and-resume loops never trigger. That is index arithmetic driven by a
  buffer that fills at an arbitrary point, which is exactly where hand-picked
  examples pass and off-by-ones survive.

  Each property runs under hegel.clojure-test/with, which shrinks a failure to a
  minimal counterexample and reports only the final replay assertions. Failures
  print a seed; replay it with (parse-long seed) as :seed."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hegel.clojure-test :refer [with]]
            [hegel.core :as h]
            [hegel.generator :as g]
            [jolt.http.body :as body]
            [jolt.http.date :as date]
            [jolt.http.fake-socket :as fs]
            [jolt.http.hegel-support :as hegel-support]
            [jolt.http.http-model :as m]
            [teensyp.buffer :as buf]))

(def ^:private opts
  (hegel-support/run-opts
   {:test-cases 200 :database "" :verbosity :quiet}))

;; --- date ------------------------------------------------------------------

;; Howard Hinnant's days_from_civil — the *forward* direction. jolt.http.date
;; implements the inverse (civil_from_days); using the inverse to check itself
;; would prove nothing, so this is written out separately. The four terms of the
;; era/yoe correction are the ones that go wrong when mis-signed, and they are
;; independent between the two directions.
(defn- days-from-civil [^long y ^long mo ^long d]
  (let [y   (if (<= mo 2) (dec y) y)
        era (quot (if (>= y 0) y (- y 399)) 400)
        yoe (- y (* era 400))
        doy (+ (quot (+ (* 153 (+ mo (if (> mo 2) -3 9))) 2) 5) (dec d))
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (+ (* era 146097) doe -719468)))

(def ^:private day-index
  (zipmap ["Sun" "Mon" "Tue" "Wed" "Thu" "Fri" "Sat"] (range)))

(def ^:private month-index
  (zipmap ["Jan" "Feb" "Mar" "Apr" "May" "Jun"
           "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"]
          (map inc (range))))

(def ^:private rfc1123-re
  #"([A-Z][a-z]{2}), (\d{2}) ([A-Z][a-z]{2}) (-?\d+) (\d{2}):(\d{2}):(\d{2}) GMT")

(defn- parse-rfc1123 [s]
  (when-some [mm (re-matches rfc1123-re s)]
    {:dow (day-index (mm 1))
     :day (parse-long (mm 2))
     :mon (month-index (mm 3))
     :year (parse-long (mm 4))
     :hour (parse-long (mm 5))
     :min (parse-long (mm 6))
     :sec (parse-long (mm 7))}))

;; Years 1000..9999. Below 1000 the year formats to three digits, so the
;; four-digit wire format simply does not apply — and no HTTP Date field ever
;; carries such an instant. Restricting the generator is a domain statement, not
;; a way to dodge a failure: the leap-year and century-correction terms this is
;; aimed at are all exercised inside the range.
(def ^:private millis-gen (g/integer -30610224000000 253402300799999))

(deftest date-round-trips-through-an-independent-model
  (with (assoc opts :name "date/round-trip")
        [millis millis-gen]
        (let [s (date/format-millis millis)
              p (parse-rfc1123 s)]
          (h/fprn :minimal-millis millis :formatted s)
          (is (some? p) (str "output is RFC-1123 shaped: " (pr-str s)))
          (when p
            (let [days (days-from-civil (:year p) (:mon p) (:day p))
                  secs (+ (* days 86400) (* 3600 (:hour p)) (* 60 (:min p)) (:sec p))]
              (is (= (Math/floorDiv (long millis) 1000) secs)
                  "formatted instant round-trips to the same epoch second")
              (is (= (Math/floorMod (+ days 4) 7) (:dow p))
                  "day-of-week agrees with the epoch-day count (1970-01-01 was a Thursday)"))))))

(deftest date-is-structurally-valid
  (with (assoc opts :name "date/shape")
        [millis millis-gen]
        (let [s (date/format-millis millis)]
          (h/fprn :minimal-millis millis :formatted s)
      ;; The four-digit year is part of the wire format, so a year that formats
      ;; to three digits is a defect even though it parses.
          (is (re-matches #"[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT" s)))))

(deftest date-day-of-week-advances-by-one-per-day
  (with (assoc opts :name "date/dow-cycle")
        [millis millis-gen]
        (let [a (parse-rfc1123 (date/format-millis millis))
              b (parse-rfc1123 (date/format-millis (+ (long millis) 86400000)))]
          (h/fprn :minimal-millis millis)
          (is (and a b))
          (when (and a b)
            (is (= (mod (inc (:dow a)) 7) (:dow b))
                "tomorrow's weekday is the next one in a Sunday-first week")))))

(deftest date-is-constant-within-a-second
  (with (assoc opts :name "date/second-stability")
        [millis millis-gen
         offset (g/integer 0 999)]
    ;; The Date header changes at most once a second, which is what makes the
    ;; cache in date/now sound. Note the cache keys on (quot millis 1000) while
    ;; the formatter floors — they agree for every non-negative instant, and an
    ;; HTTP Date is never negative, so this pins the boundary rather than
    ;; asserting the two are interchangeable.
        (let [base (* (Math/floorDiv (long millis) 1000) 1000)]
          (h/fprn :minimal-second base :offset offset)
          (is (= (date/format-millis base) (date/format-millis (+ base offset)))))))

;; --- content-charset -------------------------------------------------------

(def ^:private charset-token-gen
  (g/string {:min-size 1 :max-size 12
             :alphabet "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_."}))

(deftest content-charset-round-trip
  (with (assoc opts :name "body/charset")
        [charset charset-token-gen
         quoted? (g/boolean)
         media   (g/sampled-from ["text/html" "text/plain" "application/json"
                                  "application/x-www-form-urlencoded"])
         extra   (g/sampled-from ["" "; boundary=xyz" "; foo=bar; baz=qux"])]
        (let [rendered (if quoted? (str "\"" charset "\"") charset)
              ct       (str media extra "; charset=" rendered)]
          (h/fprn :minimal-content-type ct)
          (is (= charset (body/content-charset {"content-type" ct}))))))

(deftest content-charset-absent-is-nil
  (with (assoc opts :name "body/charset-absent")
        [media (g/sampled-from ["text/html" "text/plain" "application/json"])
         extra (g/sampled-from ["" "; boundary=xyz" "; foo=bar"])]
        (is (nil? (body/content-charset {"content-type" (str media extra)})))))

(deftest content-charset-never-throws
  (with (assoc opts :name "body/charset-total")
    ;; A Content-Type is attacker-controlled; the parser must be total over the
    ;; whole field-value domain, not merely over well-formed media types.
        [ct (g/string {:max-size 64 :codec :ascii})]
        (is (or (nil? (body/content-charset {"content-type" ct}))
                (string? (body/content-charset {"content-type" ct}))))))

;; --- Content-Length --------------------------------------------------------

(deftest content-length-is-bounded-by-the-parser-counter
  (with (assoc opts :name "body/content-length-boundary")
        [delta (g/integer -16 16)]
        (let [n      (+ body/max-content-length delta)
              parsed (body/parse-content-length (str n))]
          (h/fprn :minimal-delta delta :value (str n))
          (if (pos? delta)
            (is (nil? parsed)
                "a decimal larger than signed long is rejected before body parsing")
            (is (= (long n) parsed)
                "every representable decimal at the upper boundary is accepted")))))

(deftest content-length-parser-is-total-over-decimal-input
  (with (assoc opts :name "body/content-length-total")
        [digits (g/string {:min-size 1 :max-size 40
                           :alphabet "0123456789"})]
        (let [result (body/parse-content-length digits)]
          (h/fprn :minimal-digits digits)
          (is (or (nil? result)
                  (and (integer? result)
                       (<= 0 result body/max-content-length)))))))

(deftest content-length-range-check-does-not-need-a-wide-integer
  (let [zero-padded (str (apply str (repeat 8192 "0")) "1")
        over-wide   (apply str (repeat 8192 "9"))]
    (is (= 1 (body/parse-content-length zero-padded))
        "arbitrarily many leading zeroes retain the represented value")
    (is (nil? (body/parse-content-length over-wide))
        "an over-wide decimal is rejected from its text width before parsing")))

;; --- writer functions ------------------------------------------------------

(defn- drive-writer
  "Drive a writer function to completion the way body/run-writer does — fill the
  buffer, flush the filled region, clear, repeat — and return everything it
  emitted, as unsigned octets.

  `run-writer` itself needs a socket, so the loop is reproduced here; it is four
  lines and keeping it socket-free is what makes these properties pure. The
  guard bounds a writer that never reports done, so a bug shows up as a failed
  assertion instead of a hung suite."
  [writerf cap]
  (loop [b (buf/buffer cap) acc [] guard 0]
    (if (> guard 20000)
      ::runaway
      (let [done? (writerf b)
            n     (buf/position b)
            out   (into acc (m/->octets (buf/get-bytes! (buf/flip b) n)))]
        (if done?
          out
          (recur (buf/clear b) out (inc guard)))))))

(deftest bytes-writer-conserves-every-byte
  (with (assoc opts :name "body/bytes-writer")
        [payload (g/vector {:max-size 2048} (g/octet))
         cap     (g/integer 1 256)]
    ;; Capacity 1 is in-domain and is where the partial-fill path lives: the
    ;; server's response buffer fills at an arbitrary point relative to the body.
        (let [bs  (m/->ba payload)
              got (drive-writer (body/bytes-writer bs 0 (alength bs)) cap)]
          (h/fprn :minimal-len (count payload) :cap cap)
          (is (= payload got)))))

(deftest bytes-writer-honours-offset-and-length
  (with (assoc opts :name "body/bytes-writer-range")
        [payload (g/vector {:min-size 1 :max-size 512} (g/octet))
         cap     (g/integer 1 128)]
        (g/let [off (g/integer 0 (dec (count payload)))
                len (g/integer 0 (- (count payload) off))]
          (let [bs  (m/->ba payload)
                got (drive-writer (body/bytes-writer bs off len) cap)]
            (h/fprn :minimal-len (count payload) :off off :len len :cap cap)
            (is (= (subvec payload off (+ off len)) got))))))

(deftest chunk-writer-emits-one-chunk-and-a-terminator
  (with (assoc opts :name "body/chunk-writer")
        [payload (g/vector {:max-size 1024} (g/octet))
         cap     (g/integer 16 256)]
    ;; RFC 9112 7.1: chunk-size in hex, CRLF, the data, CRLF, then the
    ;; zero-length chunk and an empty trailer section. The three-state index in
    ;; chunk-writer has to hold that shape across a buffer that fills anywhere.
        (let [bs       (m/->ba payload)
              n        (alength bs)
              expected (into (m/ascii (str (format "%X" n) "\r\n"))
                             (into (vec payload) (m/ascii "\r\n0\r\n\r\n")))
              got      (drive-writer (body/chunk-writer (body/bytes-writer bs 0 n) n) cap)]
          (h/fprn :minimal-len n :cap cap)
          (is (= expected got)))))

(deftest chunk-writer-output-decodes-back-to-the-payload
  (with (assoc opts :name "body/chunk-writer-decode")
        [payload (g/vector {:max-size 1024} (g/octet))
         cap     (g/integer 16 256)]
    ;; Encode/decode agreement against the independent dechunker in http-model,
    ;; which is the same decoder the response properties trust.
        (let [bs   (m/->ba payload)
              n    (alength bs)
              wire (drive-writer (body/chunk-writer (body/bytes-writer bs 0 n) n) cap)
              resp (m/read-response
                    (into (m/ascii "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n")
                          wire))]
          (h/fprn :minimal-len n :cap cap)
          (is (nil? (:error resp)))
          (is (= payload (:body resp))))))

(deftest limit-writer-emits-exactly-the-declared-prefix
  (with (assoc opts :name "body/limit-writer")
        [payload (g/vector {:max-size 1024} (g/octet))
         limit   (g/integer 0 1024)
         cap     (g/integer 1 128)]
    ;; This backs the "handler declared a Content-Length shorter than the body"
    ;; branch in protocol/write-known-length-to-socket: sending more than the
    ;; declared length desynchronises a keep-alive connection.
        (let [bs  (m/->ba payload)
              n   (alength bs)
              got (drive-writer (body/limit-writer (body/bytes-writer bs 0 n) limit) cap)
              k   (min limit n)]
          (h/fprn :minimal-len n :limit limit :cap cap)
          (is (not= ::runaway got) "limit-writer terminates")
          (when (not= ::runaway got)
            (is (= k (count got)) "emits exactly min(limit, body length) bytes")
            (is (= (subvec payload 0 k) got) "and they are the body's prefix")))))

(deftest file-writer-conserves-the-file
  (with (assoc opts :test-cases 60 :name "body/file-writer")
        [payload (g/vector {:max-size 4096} (g/octet))
         cap     (g/integer 1 256)]
        (let [f (java.io.File. (str "/tmp/jolt-http-prop-" (count payload) "-" cap ".bin"))]
          (try
            (with-open [out (java.io.FileOutputStream. f)]
              (.write out (m/->ba payload)))
            (let [in  (java.io.FileInputStream. f)
                  got (drive-writer (body/file-writer in) cap)]
              (h/fprn :minimal-len (count payload) :cap cap)
              (is (= payload got)))
            (finally (.delete f))))))

;; --- sinks -----------------------------------------------------------------

(defn- recording-sink
  "A Sink that records what it was handed. The sink protocol is byte-oriented
  and socket-free, so the encoders can be checked with no server at all."
  [log]
  (reify body/Sink
    (sink-write! [_ bs off len]
      (swap! log into (subvec (m/->octets bs) off (+ (long off) (long len)))))
    (sink-close! [_] (swap! log identity))))

(deftest coalescing-sink-bounds-sequential-write-handoffs
  (let [payload (vec (range 201))
        chunks  (mapv (fn [n]
                        (byte-array (repeat 997 (unchecked-byte n))))
                      payload)
        octets  (atom [])
        calls   (atom 0)
        target  (reify body/Sink
                  (sink-write! [_ bs off len]
                    (swap! calls inc)
                    (swap! octets into
                           (subvec (m/->octets bs)
                                   (long off)
                                   (+ (long off) (long len)))))
                  (sink-close! [_]))
        sink    (body/coalescing-sink target 8192)]
    (doseq [chunk chunks]
      (body/sink-write! sink chunk 0 (alength chunk)))
    (body/sink-close! sink)
    (is (= (vec (mapcat m/->octets chunks)) @octets)
        "coalescing preserves every byte in order")
    (is (= 25 @calls)
        "201 element writes become ceil(201*997/8192) downstream writes")))

(deftest coalescing-sink-flushes-before-framing-close
  (let [wire   (atom [])
        target (recording-sink wire)
        sink   (body/coalescing-sink (body/chunked-sink target) 16)
        data   (m/->ba [1 2 3 4 5])]
    (body/sink-write! sink data 0 (alength data))
    (body/sink-close! sink)
    (let [resp (m/read-response
                (into (m/ascii
                       "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n")
                      @wire))]
      (is (nil? (:error resp)))
      (is (= [1 2 3 4 5] (:body resp))))))

(deftest sink-chain-finalization-is-observably-exactly-once
  (let [wire   (atom [])
        closes (atom 0)
        target (reify body/Sink
                 (sink-write! [_ bs off len]
                   (swap! wire into
                          (subvec (m/->octets bs)
                                  (long off)
                                  (+ (long off) (long len)))))
                 (sink-close! [_] (swap! closes inc)))
        sink   (body/coalescing-sink (body/chunked-sink target) 16)
        data   (m/->ba [1 2 3 4 5])]
    (body/sink-write! sink data 0 (alength data))
    ;; A custom StreamableBody may close explicitly; the HTTP adapter closes in
    ;; finally as well. Both calls must collapse to one terminator and one
    ;; downstream close.
    (body/sink-close! sink)
    (body/sink-close! sink)
    (let [{:keys [responses error trailing]}
          (m/read-responses
           (into (m/ascii
                  "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n")
                 @wire))]
      (is (= 1 @closes) "the downstream sink is finalized once")
      (is (nil? error) "the single chunk terminator is valid")
      (is (empty? trailing) "a second terminator is not emitted")
      (is (= [[1 2 3 4 5]] (mapv :body responses))))))

(deftest guarded-nonzero-offset-sinks-exclude-guards
  (let [payload [1 2 3 4 5 6 7]
        guarded (m/->ba (concat [240 241] payload [242 243]))]
    (let [wire   (atom [])
          sink   (body/coalescing-sink (recording-sink wire) 3)]
      (body/sink-write! sink guarded 2 (count payload))
      (body/sink-close! sink)
      (is (= payload @wire)
          "coalescing copies only the requested nonzero-offset range"))
    (let [wire   (atom [])
          sink   (body/chunked-sink (recording-sink wire))]
      (body/sink-write! sink guarded 2 (count payload))
      (body/sink-close! sink)
      (let [resp (m/read-response
                  (into (m/ascii
                         "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n")
                        @wire))]
        (is (nil? (:error resp)))
        (is (= payload (:body resp))
            "chunk framing excludes prefix and suffix guards")))))

(deftest chunked-sink-round-trips-through-the-model-dechunker
  (with (assoc opts :name "body/chunked-sink")
        [writes (g/vector {:max-size 8} (g/vector {:max-size 256} (g/octet)))]
    ;; Each write becomes its own chunk and close emits the terminator, so the
    ;; decoded stream must be the concatenation of the writes regardless of how
    ;; the caller split them. Empty writes emit no chunk (a zero-length chunk
    ;; would terminate the body early — that is the bug this pins).
        (let [log  (atom [])
              sink (body/chunked-sink (recording-sink log))]
          (doseq [w writes]
            (let [bs (m/->ba w)] (body/sink-write! sink bs 0 (alength bs))))
          (body/sink-close! sink)
          (let [resp (m/read-response
                      (into (m/ascii "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n")
                            @log))]
            (h/fprn :minimal-write-sizes (mapv count writes))
            (is (nil? (:error resp)))
            (is (= (vec (apply concat writes)) (:body resp)))))))

(deftest limited-sink-never-exceeds-its-limit
  (with (assoc opts :name "body/limited-sink")
        [writes (g/vector {:max-size 8} (g/vector {:max-size 256} (g/octet)))
         limit  (g/integer 0 1024)]
        (let [log  (atom [])
              sock (fs/fake-socket)
              sink (body/limited-sink (recording-sink log) limit sock)]
          (doseq [w writes]
            (let [bs (m/->ba w)] (body/sink-write! sink bs 0 (alength bs))))
          (body/sink-close! sink)
          (let [all (vec (apply concat writes))
                k   (min limit (count all))]
            (h/fprn :minimal-write-sizes (mapv count writes) :limit limit)
            (is (= k (count @log)) "emits exactly min(limit, total written)")
            (is (= (subvec all 0 k) @log) "and they are the prefix of what was written")))))

;; --- request bodies --------------------------------------------------------

(deftest request-body-concatenates-its-chunks
  (with (assoc opts :test-cases 100 :name "body/request-body")
        [chunks (g/vector {:max-size 8} (g/vector {:min-size 1 :max-size 256} (g/octet)))]
    ;; body-bytes rebuilds one array from the chunks the parser pushed through
    ;; the fork's overlap-safe System/arraycopy implementation.
        (let [{:keys [ch body]} (body/request-body (max 1 (count chunks)))
              feeder (future (doseq [c chunks] (a/>!! ch (m/->ba c)))
                             (a/close! ch))
              got    (m/->octets (body/body-bytes body))]
          @feeder
          (h/fprn :minimal-chunk-sizes (mapv count chunks))
          (is (= (vec (apply concat chunks)) got)))))

(deftest request-body-decodes-as-utf8
  (with (assoc opts :test-cases 100 :name "body/request-body-string")
        [parts (g/vector {:max-size 6} (g/string {:max-size 32 :codec :ascii}))]
        (let [{:keys [ch body]} (body/request-body (max 1 (count parts)))
              feeder (future (doseq [p parts] (a/>!! ch (.getBytes ^String p "UTF-8")))
                             (a/close! ch))
              got    (body/body-string body "UTF-8")]
          @feeder
          (h/fprn :minimal-parts parts)
          (is (= (apply str parts) got)))))
