(ns ^:no-doc jolt.http.body
  "Response body writing, and the buffer/stream plumbing it needs.

  This namespace absorbs the two things Capra gets from the JVM that jolt has no
  equivalent for:

  - `ring.core.protocols/StreamableResponseBody` becomes the [[StreamableBody]]
    protocol defined here (there is no Ring on jolt).
  - `java.io.OutputStream` becomes the [[Sink]] protocol. jolt's `proxy` is just
    `reify` and cannot subclass OutputStream, so a byte-oriented protocol takes
    its place.

  Two ways of writing a body coexist, exactly as in Capra:

  - **Writer functions** — `(f buffer) -> done?` — fill the response buffer and
    are driven asynchronously by [[run-writer]] through the socket's write
    callback. Nothing blocks. Used for the known-length bodies (byte-array,
    String, File) that make up the hot path.
  - **Sinks** — blocking, for arbitrary bodies whose length isn't known up front.
    A sink write parks the calling worker thread until the socket drains it."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [teensyp.buffer :as buf]
            [teensyp.concurrent :refer [with-lock]]
            [teensyp.server :as tcp]))

(def ^:const SPACE 0x20)
(def ^:const COLON 0x3A)
(def ^:const CR 0x0D)
(def ^:const LF 0x0A)

(defn ascii-bytes ^bytes [s]
  (.getBytes ^String s "US-ASCII"))

(def crlf (ascii-bytes "\r\n"))
(def ^:private end-chunk (ascii-bytes "\r\n0\r\n\r\n"))
(def empty-chunk (ascii-bytes "0\r\n\r\n"))

;; --- buffer helpers --------------------------------------------------------
;; teensyp.buffer has no (wrap arr off len) or scoped limit, which Capra gets
;; from ByteBuffer; both are small and defined here.

(defn wrap-range
  "Wrap `len` bytes of `bs` starting at `off` as a readable Buffer."
  [^bytes bs off len]
  (doto (buf/wrap bs)
    (buf/set-position! off)
    (buf/set-limit! (+ (long off) (long len)))))

(defn put!
  "Write a whole byte-array into `b` at its position."
  [b ^bytes bs]
  (buf/put-bytes! b bs 0 (alength bs)))

(defn put-byte! [b n]
  (let [a (byte-array 1)]
    (aset a 0 (byte n))
    (buf/put-bytes! b a 0 1)))

(defn write-ascii [b s]
  (put! b (ascii-bytes (str s))))

(defn write-crlf [b]
  (put! b crlf))

(defn- limit-buffer
  "Run `(f buffer)` with the buffer's limit temporarily narrowed, then restore."
  [f b new-limit]
  (let [lim (buf/limit b)]
    (buf/set-limit! b new-limit)
    (try (f b) (finally (buf/set-limit! b lim)))))

;; --- header helpers --------------------------------------------------------

(def ^:const max-content-length
  "Largest Content-Length the parser's signed 64-bit remaining-byte counter can
  represent."
  9223372036854775807)

(def ^:private max-content-length-text "9223372036854775807")

(defn- ascii-decimal-digit? [c]
  (let [n (int c)]
    (and (<= 0x30 n) (<= n 0x39))))

(defn parse-content-length
  "Parse one canonical decimal Content-Length, or return nil.

  Range is checked from the decimal text before parsing. This keeps the function
  total without constructing an arbitrary-precision integer for an attacker-
  supplied field, and guarantees the later signed-long body counter conversion
  cannot overflow. Leading zeroes remain valid HTTP decimal syntax."
  [value]
  (when (and (string? value)
             (pos? (count value))
             (every? ascii-decimal-digit? value))
    (let [n     (count value)
          start (loop [i 0]
                  (if (and (< i n) (= \0 (nth value i)))
                    (recur (inc i))
                    i))]
      (if (= start n)
        0
        (let [digits (subs value start)
              width  (count digits)]
          (when (or (< width (count max-content-length-text))
                    (and (= width (count max-content-length-text))
                         (not (pos? (compare digits max-content-length-text)))))
            (long (parse-long digits))))))))

(defn content-length [{:strs [content-length]}]
  (parse-content-length content-length))

(defn chunked-transfer? [{:strs [transfer-encoding]}]
  (and transfer-encoding
       (= "chunked" (str/lower-case transfer-encoding))))

(defn chunked-response? [{:strs [transfer-encoding content-length]}]
  (or (and transfer-encoding (= "chunked" (str/lower-case transfer-encoding)))
      (and (nil? transfer-encoding) (nil? content-length))))

(def ^:private re-charset
  #"(?x);(?:.*\s)?(?i:charset)=(?:
      ([!\#$%&'*\-+.0-9A-Z\^_`a-z\|~]+)|  # token
      \"((?:\\\"|[^\"])*)\"               # quoted
    )\s*(?:;|$)")

(defn content-charset [{:strs [content-type]}]
  (when content-type
    (when-some [m (re-find re-charset content-type)]
      (or (m 1) (m 2)))))

;; --- writer functions ------------------------------------------------------
;; A writer adds to a buffer and returns true when there is nothing more to
;; write. They let the different known-length body types share one code path.

(defn queue-write!
  "Queue the filled region of `buffer` to the socket, as a **copy**.

  The copy is essential, not an optimisation. `buffer` is the connection's
  shared response buffer, and jolt-tcp's `write` only ever queues: unlike JVM
  teensyp there is no `try-write` fast path that drains it synchronously (all
  socket I/O happens on the reactor thread). Handing the shared buffer over
  directly means the next response on the same connection — the very next
  pipelined request, or the next keep-alive request — clears and rewrites those
  bytes while the reactor is still sending them, which corrupts the in-flight
  response, desynchronises jolt-tcp's write-credit accounting, and wedges the
  reactor.

  jolt-tcp now borrows the exact queued Buffer range through jolt.net until its
  callback fires. The snapshot is therefore the explicit ownership handoff: it
  is the one copy that lets the connection response buffer be reused safely."
  ([socket buffer] (queue-write! socket buffer nil))
  ([socket buffer callback]
   (buf/flip buffer)
   (let [snapshot (buf/wrap (buf/get-bytes! buffer (buf/remaining buffer)))]
     (if callback
       (tcp/write socket snapshot callback)
       (tcp/write socket snapshot)))))

(defn run-writer
  "Drive `writerf` to completion, flushing the buffer to the socket whenever it
  fills and resuming from the write callback. Calls `callback` when done."
  [writerf socket buffer callback]
  (if (writerf buffer)
    (do (queue-write! socket buffer)
        (callback))
    (queue-write! socket buffer
                  #(run-writer writerf socket (buf/clear buffer) callback))))

(defn- copy-buffer [src dest]
  (buf/copy src dest)
  (not (buf/has-remaining? src)))

(defn bytes-writer [^bytes bs off len]
  (let [read-buf (wrap-range bs off len)]
    (fn [write-buf] (copy-buffer read-buf write-buf))))

(defn file-writer
  "A writer that pulls successive chunks out of a FileInputStream. jolt has no
  FileChannel, so the read goes via a temporary array sized to the free space."
  [in]
  (fn [write-buf]
    (let [rem (buf/remaining write-buf)]
      (if (zero? rem)
        false
        (let [tmp (byte-array rem)
              n   (.read ^java.io.FileInputStream in tmp 0 rem)]
          (if (neg? n)
            (do (.close ^java.io.FileInputStream in) true)
            (do (buf/put-bytes! write-buf tmp 0 n) false)))))))

(defn limit-writer
  "Wrap a writer so it emits at most `len` bytes."
  [writerf len]
  (let [bytes-left (volatile! (long len))]
    (fn [write-buf]
      (let [^long len @bytes-left]
        (or (not (pos? len))
            (let [pos   (buf/position write-buf)
                  done? (if (< len (buf/remaining write-buf))
                          (limit-buffer writerf write-buf (+ pos len))
                          (writerf write-buf))]
              (or done?
                  (let [len (- len (- (buf/position write-buf) pos))]
                    (vreset! bytes-left len)
                    (not (pos? len))))))))))

(defn chunk-writer
  "Wrap a known-length writer as a single HTTP chunk plus the terminating
  zero-length chunk."
  [writerf len]
  (let [header (buf/wrap (ascii-bytes (format "%X\r\n" len)))
        end    (buf/wrap end-chunk)
        index  (volatile! 0)]
    (fn [write-buf]
      (let [idx (long @index)]
        (when (case idx
                0 (copy-buffer header write-buf)
                1 (writerf write-buf)
                2 (copy-buffer end write-buf))
          (vreset! index (inc idx))
          (>= idx 2))))))

;; --- sinks -----------------------------------------------------------------

(defprotocol Sink
  "The OutputStream replacement: a byte destination that blocks until written."
  (sink-write! [sink bs off len])
  (sink-close! [sink]))

(defn socket-sink
  "A blocking Sink over a teensyp Socket. Each write queues to the socket and
  parks the calling thread on a failure-aware completion until the bytes are
  flushed or the connection retires.

  This consumes a handler-pool thread for the duration of the body. jolt-tcp
  deliberately runs the write callback which unparks it on a separate callback
  executor, avoiding handler-pool self-starvation; see the `:pool-size` note in
  jolt.http.server."
  ([socket] (socket-sink socket nil))
  ([socket on-close]
   (let [lock     (java.util.concurrent.locks.ReentrantLock.)
         closed   (volatile! false)
         on-close (or on-close (fn [sock] (tcp/close sock)))]
     (reify Sink
       (sink-write! [_ bs off len]
         (with-lock lock
           (when @closed
             (throw (ex-info "Sink closed" {:err ::sink-closed})))
           (when (pos? (long len))
             (let [{:keys [status exception] :as outcome}
                   @(tcp/write-completion socket (wrap-range bs off len))]
               (case status
                 :written nil
                 :failed  (throw exception)
                 (throw
                  (ex-info "Unknown socket write completion"
                           {:err ::invalid-write-completion
                            :outcome outcome})))))))
       (sink-close! [_]
         (with-lock lock
           (when-not @closed
             (vreset! closed true)
             (on-close socket))))))))

(defn coalescing-sink
  "Buffer small writes into chunks of at most `capacity` before forwarding them.

  Generic sequential response bodies commonly produce hundreds of sub-kilobyte
  elements. A socket Sink waits for one reactor completion per forwarded write,
  so forwarding every element turns a 200KB response into hundreds of scheduler
  round trips. This wrapper preserves byte order and blocking completion while
  bounding those round trips by the number of filled coalescing buffers."
  [sink capacity]
  (let [capacity (max 1 (long capacity))
        storage  (byte-array capacity)
        used     (volatile! 0)
        closed   (volatile! false)
        lock     (java.util.concurrent.locks.ReentrantLock.)]
    (letfn [(flush-buffer! []
              (let [n (long @used)]
                (when (pos? n)
                  ;; The downstream socket Sink blocks until the write drains,
                  ;; so storage is no longer borrowed when this call returns.
                  (sink-write! sink storage 0 n)
                  (vreset! used 0))))
            (append! [bs off len]
              (loop [src-off (long off)
                     left    (long len)]
                (when (pos? left)
                  (let [free (- capacity (long @used))
                        n    (min free left)
                        dst  (long @used)]
                    (System/arraycopy bs src-off storage dst n)
                    (vswap! used + n)
                    (when (= capacity (long @used))
                      (flush-buffer!))
                    (recur (+ src-off n) (- left n))))))]
      (reify Sink
        (sink-write! [_ bs off len]
          (with-lock lock
            (when @closed
              (throw (ex-info "Sink closed" {:err ::sink-closed})))
            (when (pos? (long len))
              (append! bs off len))))
        (sink-close! [_]
          (with-lock lock
            (when-not @closed
              (vreset! closed true)
              (flush-buffer!)
              (sink-close! sink))))))))

(defn chunked-sink
  "Wrap a Sink so each write becomes an HTTP chunk, and close emits the
  terminating zero-length chunk."
  [sink]
  (let [lock   (java.util.concurrent.locks.ReentrantLock.)
        closed (volatile! false)]
    (reify Sink
      (sink-write! [_ bs off len]
        (when (pos? (long len))
          (let [header (ascii-bytes (format "%X\r\n" len))
                hlen   (alength header)
                wire   (byte-array (+ hlen (long len) (alength crlf)))]
            ;; One framed write means one socket/reactor handoff. The outer
            ;; coalescing Sink bounds this allocation to the response buffer
            ;; size for the generic sequential-body path.
            (System/arraycopy header 0 wire 0 hlen)
            (System/arraycopy bs off wire hlen len)
            (System/arraycopy crlf 0 wire (+ hlen (long len)) (alength crlf))
            (with-lock lock
              (sink-write! sink wire 0 (alength wire))))))
      (sink-close! [_]
        (with-lock lock
          (when-not @closed
            (vreset! closed true)
            (sink-write! sink empty-chunk 0 (alength empty-chunk))
            (sink-close! sink)))))))

(defn limited-sink
  "Wrap a Sink so it emits at most `limit` bytes; if the body turns out to be
  shorter than the declared Content-Length the socket is closed on completion,
  since the response can no longer be framed correctly."
  [sink limit socket]
  (let [left (volatile! (long (or limit 0)))]
    (reify Sink
      (sink-write! [_ bs off len]
        (let [n (min (long len) (long @left))]
          (when (pos? n)
            (vswap! left - n)
            (sink-write! sink bs off n))))
      (sink-close! [_]
        (sink-close! sink)
        (when (pos? (long @left))
          (tcp/close socket))))))

;; --- request bodies --------------------------------------------------------

(defprotocol RequestBody
  "The request `:body`. Ring specifies an InputStream, which jolt cannot provide
  (`proxy` is `reify`, so InputStream cannot be subclassed and the io coercions
  reject a reified stream), so handlers get this protocol instead."
  (body-recv [body]
    "Block for the next chunk of the request body as a byte-array; nil at the
    end of the body.")
  (body-bytes [body]
    "Block until the whole request body is read; return it as one byte-array.")
  (body-string [body charset]
    "Block until the whole request body is read; decode it as a String."))

(defn- concat-chunks ^bytes [chunks]
  (let [total (reduce + 0 (map alength chunks))
        out   (byte-array total)]
    (loop [cs chunks off 0]
      (if-let [^bytes c (first cs)]
        (do (System/arraycopy c 0 out off (alength c))
            (recur (rest cs) (+ off (alength c))))
        out))))

(defrecord ChanRequestBody [ch]
  RequestBody
  (body-recv [_] (a/<!! ch))
  (body-bytes [this]
    (loop [acc []]
      (if-let [c (body-recv this)]
        (recur (conj acc c))
        (concat-chunks acc))))
  (body-string [this charset]
    (String. (body-bytes this) ^String (or charset "UTF-8"))))

(defn request-body
  "Create a RequestBody plus the channel the parser feeds it.

  Backpressure falls out of the channel: the parser pushes with a blocking
  `>!!`, and while it is parked the socket stays WORKING, so the reactor stops
  reading and TCP flow-controls the sender. This is the same mechanism
  teensyp.stream documents, and it is why no explicit pause/resume is needed."
  [buffer-size]
  (let [ch (a/chan (max 1 (long buffer-size)))]
    {:ch ch :body (->ChanRequestBody ch)}))

(defn empty-request-body
  "A RequestBody that is immediately at end-of-stream."
  []
  (let [ch (a/chan 1)]
    (a/close! ch)
    (->ChanRequestBody ch)))

;; --- streamable bodies -----------------------------------------------------

(defprotocol StreamableBody
  "The `ring.core.protocols/StreamableResponseBody` analogue: synchronously
  write `body` to a Sink and return only after the final write.

  Sink lifetime belongs to the HTTP adapter. Implementations may close it early
  to signal completion, but need not do so; close is idempotent and the adapter
  closes exactly once observably after this method returns. A body that wants to
  outlive this call needs a future outcome-aware streaming SPI rather than
  retaining this blocking Sink."
  (write-body-to-sink [body response sink]))

(defn- copy-stream-to-sink [in sink]
  (let [tmp (byte-array 8192)]
    (loop []
      (let [n (.read ^java.io.FileInputStream in tmp 0 8192)]
        (when (pos? n)
          (sink-write! sink tmp 0 n)
          (recur))))))

(extend (Class/forName "[B")
  StreamableBody
  {:write-body-to-sink
   (fn [^bytes body _response sink]
     (sink-write! sink body 0 (alength body)))})

(extend-protocol StreamableBody
  String
  (write-body-to-sink [body response sink]
    (let [charset (or (content-charset (:headers response)) "UTF-8")
          bs      (.getBytes ^String body ^String charset)]
      (sink-write! sink bs 0 (alength bs))))

  nil
  (write-body-to-sink [_body _response _sink])

  Object
  (write-body-to-sink [body response sink]
    ;; NOTE: `java.io.File` is handled here by an explicit `instance?` test
    ;; rather than as its own `extend-protocol` arm. On jolt, protocol dispatch
    ;; does not resolve java.io.File — both `extend-protocol java.io.File` and
    ;; `extend java.io.File` silently fall through to Object — so a File arm
    ;; would be dead code. (String, nil and the byte-array class do dispatch.)
    (cond
      (instance? java.io.File body)
      (with-open [in (java.io.FileInputStream. ^java.io.File body)]
        (copy-stream-to-sink in sink))

      ;; The Ring fallback: a seqable body is written element by element.
      (seqable? body)
      (let [charset (or (content-charset (:headers response)) "UTF-8")]
        (doseq [x body]
          (when (some? x)
            (let [bs (if (bytes? x) x (.getBytes (str x) ^String charset))]
              (sink-write! sink bs 0 (alength ^bytes bs))))))

      :else
      (throw (ex-info (str "Cannot write body of type " (class body))
                      {:err ::unwritable-body :type (class body)})))))
