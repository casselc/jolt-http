(ns ^:no-doc jolt.http.protocol
  "The HTTP/1.1 state machine, driven by jolt-tcp's read buffer.

  A port of Capra's `capra.http`. The shape is unchanged — a per-connection
  state map stepped through

      :start-line -> :headers -> :handler -> :body -> :buffer

  on each call of the read arity, looping until a step needs more bytes. What
  differs from Capra is jolt-specific and confined to a few places:

  - `java.nio.ByteBuffer` becomes `teensyp.buffer`.
  - The response buffer is allocated **per connection**, not per thread: jolt
    has no usable `ThreadLocal` constructor. jolt-tcp calls a connection's
    handler arities serially, so a per-connection buffer needs no locking.
  - Request bodies are a channel-backed `RequestBody` rather than an
    InputStream, so Capra's `input-stream-handler` machinery is replaced by
    pushing chunks onto a channel."
  (:require [clojure.string :as str]
            [jolt.http.body :as body]
            [jolt.http.date :as date]
            [jolt.http.error :as err]
            [jolt.http.reason :as reason]
            [clojure.core.async :as a]
            [teensyp.buffer :as buf]
            [teensyp.server :as tcp]))

(def ^:private server-header (body/ascii-bytes "Server: jolt-http\r\n"))
(def ^:private length-header (body/ascii-bytes "Content-Length: "))
(def ^:private date-header   (body/ascii-bytes "Date: "))
(def ^:private close-header  (body/ascii-bytes "Connection: close\r\n"))
(def ^:private http-1-1      (body/ascii-bytes "HTTP/1.1 "))
(def ^:private chunked-header
  (body/ascii-bytes (str "Transfer-Encoding: chunked\r\n"
                         "Connection: Transfer-Encoding\r\n")))

;; --- request state ---------------------------------------------------------

(defn- init-request
  "Start parsing a new request on a connection. `conn` carries the per-connection
  response buffer and the precomputed constant part of the request map."
  [conn]
  (transient (assoc (:base-request conn)
                    ::step :start-line
                    ::conn conn
                    ::header-bytes 0
                    ::header-count 0)))

(defn- ->ring-request
  "Persist the parser state into the map handed to the Ring handler, dropping
  the parser's own bookkeeping keys."
  [state]
  (-> (persistent! state)
      (dissoc ::step ::conn ::header-bytes ::header-count)))

;; Each clause binds an index and continues only if it is a usable one. Note the
;; nil check: Capra used `.indexOf`, which reports "absent" as -1, but these use
;; `clojure.string/index-of`, which reports it as nil — and `(pos? nil)` throws.
;; Index 0 is rejected as well, matching Capra: a start line or header beginning
;; with its own delimiter is malformed.
(defmacro ^:private when-pos [[sym expr & clauses] & body]
  `(let [~sym ~expr]
     (if (and ~sym (pos? ~sym))
       ~(if (seq clauses)
          `(when-pos ~(vec clauses) ~@body)
          `(do ~@body)))))

(defmacro ^:private if-pos {:clj-kondo/lint-as 'clojure.core/let}
  [clauses then else]
  `(or (when-pos ~clauses ~then) ~else))

;; --- grammar predicates (RFC 9110 5.6.2, RFC 9112 2.2) ---------------------

(defn- ows?
  "Optional whitespace, per RFC 9110: space or horizontal tab."
  [c]
  (or (= c \space) (= c \tab)))

(def ^:private tchar-extra (set "!#$%&'*+-.^_`|~"))

(defn- tchar?
  "A token character, per RFC 9110 5.6.2. Field names and methods are tokens."
  [c]
  (or (and (>= (int c) 0x30) (<= (int c) 0x39))     ; 0-9
      (and (>= (int c) 0x41) (<= (int c) 0x5A))     ; A-Z
      (and (>= (int c) 0x61) (<= (int c) 0x7A))     ; a-z
      (contains? tchar-extra c)))

(defn- token? [s]
  (and (pos? (count s)) (every? tchar? s)))

(defn- ctl-char?
  "A control character that must not appear in a field value: anything below
  SP other than HTAB, plus DEL."
  [c]
  (let [i (int c)]
    (or (and (< i 0x20) (not= i 0x09))
        (= i 0x7F))))

(defn- bare-cr?
  "A CR anywhere in an already-line-split value. `read-line` strips the CR of a
  terminating CRLF, so anything left is a bare CR — which RFC 9112 2.2 forbids
  and which proxies and origins famously disagree about (a smuggling vector)."
  [s]
  (str/includes? s (str (char 13))))

;; --- start line + headers --------------------------------------------------

(defn- parse-start-line [state line]
  (if-pos [space1 (str/index-of line " ")
           space2 (str/index-of line " " (inc space1))]
          (let [method (subs line 0 space1)
                target (subs line (inc space1) space2)
                qmark  (str/index-of target "?")]
            ;; RFC 9110 9.1 — the method is a token. Servers that accept
            ;; anything up to the first space, or silently take the longest
            ;; valid prefix, disagree with each other about what was requested.
            (if-not (token? method)
              {::step  :error
               ::error :invalid-request-method}
              (assoc! state
                      ::step          :headers
                      :request-method (keyword (str/lower-case method))
                      :uri            (if qmark (subs target 0 qmark) target)
                      :query-string   (when qmark (subs target (inc qmark)))
                      :protocol       (subs line (inc space2))
                      :headers        (transient {}))))
          {::step  :error
           ::error :invalid-request-start-line}))

;; teensyp.buffer/read-line-strict returns this when the line it found was
;; terminated by a lone LF rather than CRLF. Named once here so the parser never
;; depends on keyword-alias resolution.
(def ^:private bare-lf :teensyp.buffer/bare-lf)

(defn- read-start-line [state socket buffer max-buffer-size]
  (if-some [line (buf/read-line-strict buffer "US-ASCII")]
    (cond
      ;; RFC 9112 2.2 allows a recipient to accept a lone LF here. jolt-http
      ;; does not: an origin behind a front end that requires CRLF would frame a
      ;; different message than the front end did, which is a smuggling vector.
      ;; This is the same stance the parser already takes on a bare CR.
      (= line bare-lf)
      {::step :error, ::error :bare-lf}

      ;; RFC 9112 2.2 — a server should ignore empty line(s) received before the
      ;; request-line. Some clients emit a stray CRLF after a previous request
      ;; body; rejecting it breaks interop for no benefit. Staying on
      ;; :start-line makes the driver loop read the next line.
      (= line "")
      state

      (bare-cr? line)
      {::step :error, ::error :bare-cr, ::request {:bad-header line}}

      :else
      (let [{:keys [protocol] :as state} (parse-start-line state line)]
        (if (or (nil? protocol) (= protocol "HTTP/1.1"))
          state
          {::step    :error
           ::error   :http-version-not-supported
           ::request {:bad-protocol protocol}})))
    (cond
      ;; The oversize check comes first: a request line that has filled the read
      ;; buffer is a 414 whether or not the peer is still there.
      (not (< (buf/limit buffer) (long max-buffer-size)))
      {::step :error, ::error :uri-too-long}

      ;; No request line, nothing buffered, and the peer's terminal notification
      ;; is current: the bytes to finish a request can never arrive, so release
      ;; the connection instead of holding it open forever.
      ;;
      ;; This must test peer-eof-notified? and not peer-closed?. The latter can
      ;; be true while an older invocation is still working against a view taken
      ;; before EOF, and closing on it preempts decisions this invocation was
      ;; about to make correctly — including the oversize one above.
      (tcp/peer-eof-notified? socket)
      (if-not (buf/has-remaining? buffer)
        (do (tcp/close socket) {::step :done})
        {::step :error, ::error :incomplete-request})

      :else nil)))

(defn- assoc-request-header! [headers name value]
  (if-some [existing-val (headers name)]
    (assoc! headers name (str existing-val "," value))
    (assoc! headers name value)))

(defn- bad-header [err line]
  {::step :error, ::error err, ::request {:bad-header line}})

(defn- parse-header [{:keys [headers] :as state} line]
  (cond
    ;; RFC 9112 5.2 — obs-fold. A leading space/tab means this line is a
    ;; continuation of the previous field. A server that is not a proxy must
    ;; reject it rather than unfold, since folding is another way for two
    ;; implementations to disagree about where a field ends.
    (ows? (first line))
    (bad-header :obs-fold-header line)

    (bare-cr? line)
    (bad-header :bare-cr line)

    :else
    (if-pos [colon-index (str/index-of line ":")]
            (let [raw-name (subs line 0 colon-index)
                  value    (str/trim (subs line (inc colon-index)))]
              (cond
                ;; RFC 9112 5.1: no whitespace between field name and colon, and
                ;; a server MUST reject such a message with 400. Accepting
                ;; "Foo : bar" is a smuggling vector, because an upstream proxy
                ;; may read the field name differently than we do.
                (ows? (nth raw-name (dec (count raw-name))))
                (bad-header :whitespace-before-colon line)

                ;; A field name must be a bare token: no spaces, controls, or
                ;; other separators.
                (not (token? raw-name))
                (bad-header :invalid-field-name line)

                ;; RFC 9110 5.5 — a field value is made of visible characters,
                ;; SP and HTAB. A NUL or other control character in a value is
                ;; another point where implementations disagree: some truncate
                ;; the value there, some strip it, some pass it through.
                (some ctl-char? value)
                (bad-header :invalid-field-value line)

                :else
                (assoc! state :headers
                        (assoc-request-header! headers (str/lower-case raw-name) value))))
            (bad-header :invalid-request-header line))))

(defn- read-header
  [{:keys [headers] ::keys [header-bytes header-count] :as state}
   socket buffer max-buffer-size max-header-bytes max-header-count]
  (if-some [line (buf/read-line-strict buffer "US-ASCII")]
    ;; The strict line reader's bare-LF sentinel is a keyword, not text. Reject
    ;; it before measuring the line so malformed framing cannot reach `count`.
    (if (= line bare-lf)
      {::step :error, ::error :bare-lf}
      (let [bytes' (+ (long header-bytes) (count line) 2)
            count' (+ (long header-count) (if (= line "") 0 1))]
        (cond
          (> bytes' (long max-header-bytes))
          {::step :error, ::error :request-headers-too-large}

          (> count' (long max-header-count))
          {::step :error, ::error :too-many-request-headers}

          (= line "")
          (assoc! state ::step :handler
                  ::header-bytes bytes'
                  :headers (persistent! headers))

          :else
          (parse-header
           (assoc! state ::header-bytes bytes' ::header-count count')
           line))))
    (cond
      (not (< (buf/limit buffer) (long max-buffer-size)))
      {::step :error, ::error :request-header-field-too-large}

      (tcp/peer-eof-notified? socket)
      {::step :error, ::error :incomplete-request}

      :else nil)))

;; --- response writing ------------------------------------------------------

(defn- write-known-length-to-socket
  [socket headers buffer writerf len callback]
  (cond
    (headers "content-length")
    (let [content-len (long (body/content-length headers))
          len         (long len)]
      (body/write-crlf buffer)
      (cond
        (= content-len len)
        (body/run-writer writerf socket buffer callback)
        (< content-len len)
        (body/run-writer (body/limit-writer writerf content-len)
                         socket buffer callback)
        :else
        ;; Declared longer than the body: the response can never be framed
        ;; correctly, so send what we have and drop the connection.
        (do (body/run-writer writerf socket buffer callback)
            (tcp/close socket))))
    (body/chunked-transfer? headers)
    (do (body/write-crlf buffer)
        (body/run-writer (body/chunk-writer writerf len) socket buffer callback))
    :else
    (do (body/put! buffer length-header)
        (body/write-ascii buffer (str len))
        (body/write-crlf buffer)
        (body/write-crlf buffer)
        (body/run-writer writerf socket buffer callback))))

(defprotocol ^:private SocketBody
  (write-body-to-socket [body response headers buffer socket async? callback]))

(defn- write-via-sink
  "The generic path: length is unknown, so the head is flushed first and the
  body is streamed through a blocking Sink."
  [b response headers buffer socket _async? callback]
  (when (and (nil? (headers "transfer-encoding"))
             (nil? (headers "content-length")))
    (body/put! buffer chunked-header))
  (body/write-crlf buffer)
  ;; Copy, for the same reason as run-writer: the shared response buffer must
  ;; not still be queued when the next response reuses it.
  (body/queue-write! socket buffer)
  (let [sink (body/socket-sink socket (fn [_sock] (callback)))
        sink (if (body/chunked-response? headers)
               (body/chunked-sink sink)
               (body/limited-sink sink (body/content-length headers) socket))
        ;; Preserve the generic Sink contract while preventing a seqable body
        ;; from paying one reactor completion round trip per element.
        sink (body/coalescing-sink sink (buf/capacity buffer))]
    (try (body/write-body-to-sink b response sink)
         (finally
           ;; StreamableBody is synchronous at this boundary. Handler async-ness
           ;; controls when `respond` is called, not whether a completed body
           ;; leaves its framing open. The Sink chain is idempotent, so a custom
           ;; body may close early without producing a second terminator.
           (body/sink-close! sink)))))

(extend (Class/forName "[B")
  SocketBody
  {:write-body-to-socket
   (fn [^bytes b _response headers buffer socket _async? callback]
     (let [len     (alength b)
           writerf (body/bytes-writer b 0 len)]
       (write-known-length-to-socket socket headers buffer
                                     writerf len callback)))})

(extend-protocol SocketBody
  String
  (write-body-to-socket [b response headers buffer socket async? callback]
    (let [charset (or (body/content-charset headers) "UTF-8")
          bs      (.getBytes ^String b ^String charset)]
      (write-body-to-socket bs response headers buffer socket async? callback)))

  nil
  (write-body-to-socket [_b _response headers buffer socket _async? callback]
    (write-known-length-to-socket socket headers buffer
                                  (constantly true) 0 callback))

  Object
  (write-body-to-socket [b response headers buffer socket async? callback]
    (write-via-sink b response headers buffer socket async? callback)))

(defn- write-file-to-socket
  "Files get the known-length fast path: the size is known up front, so the
  response is Content-Length framed and streamed straight off the file with no
  blocking sink and no chunking."
  [f headers buffer socket callback]
  (let [in  (java.io.FileInputStream. ^java.io.File f)
        len (.length ^java.io.File f)]
    (write-known-length-to-socket socket headers buffer
                                  (body/file-writer in) len callback)))

(defn- write-response-body
  "Dispatch a response body to the socket.

  `java.io.File` is tested explicitly rather than given a `SocketBody` arm: on
  jolt, protocol dispatch does not resolve java.io.File (both `extend-protocol`
  and `extend` fall through to Object), so a File arm would never fire and every
  file would take the slow chunked sink path."
  [b response headers buffer socket async? callback]
  (if (instance? java.io.File b)
    (write-file-to-socket b headers buffer socket callback)
    (write-body-to-socket b response headers buffer socket async? callback)))

(defn- write-status-line [buffer {:keys [status]}]
  (let [status (or status 200)]
    (body/put! buffer http-1-1)
    (body/write-ascii buffer (str status))
    (body/put-byte! buffer body/SPACE)
    (body/write-ascii buffer (or (reason/status->reason status) "Unknown"))
    (body/write-crlf buffer)))

(defn- write-header [buffer k v]
  (body/write-ascii buffer k)
  (body/put-byte! buffer body/COLON)
  (body/put-byte! buffer body/SPACE)
  (body/write-ascii buffer v)
  (body/write-crlf buffer))

(defn- write-date-header [buffer]
  (body/put! buffer date-header)
  (body/write-ascii buffer (date/now))
  (body/write-crlf buffer))

(defn- write-response-head [buffer {:keys [headers] :as response} lc-headers close?]
  (write-status-line buffer response)
  (when (and close? (not (lc-headers "connection")))
    (body/put! buffer close-header))
  (when-not (lc-headers "date")   (write-date-header buffer))
  (when-not (lc-headers "server") (body/put! buffer server-header))
  (doseq [kv headers]
    (let [value (val kv)]
      (if (vector? value)
        (doseq [v value] (write-header buffer (key kv) v))
        (write-header buffer (key kv) value)))))

(defn- assoc-response-header! [headers name value]
  (assoc! headers
          (str/lower-case name)
          (if (string? value) value (str/join "," value))))

(defn- normalize-headers [headers]
  (persistent! (reduce-kv assoc-response-header! (transient {}) headers)))

(def ^:private re-close-connection #"(?i)(^| *,)close( *,|$)")

(defn- connection-close? [{:strs [connection]}]
  (boolean (when connection (re-find re-close-connection connection))))

;; --- HEAD ------------------------------------------------------------------

(defn- known-body-length
  "The byte length a body would occupy, or nil if it can only be discovered by
  writing it (a seq or a custom StreamableBody)."
  [b response headers]
  (cond
    (nil? b)                      0
    (bytes? b)                    (alength ^bytes b)
    (instance? java.io.File b)    (.length ^java.io.File b)
    (string? b)                   (alength (.getBytes ^String b
                                                      ^String (or (body/content-charset headers)
                                                                  "UTF-8")))
    :else                         nil))

(defn- write-head-response
  "Respond to HEAD: the same header block a GET would produce, and no body.

  RFC 9110 9.3.2 — a HEAD response must not carry content, but should carry the
  header fields the equivalent GET would. Sending a body here is not merely
  redundant: the client is not expecting one, so on a keep-alive connection
  those bytes get read as the start of the next response."
  [b response headers buffer socket _async? callback]
  (let [len (known-body-length b response headers)]
    (cond
      ;; An explicit Content-Length or Transfer-Encoding from the handler wins.
      (or (headers "content-length") (headers "transfer-encoding"))
      nil
      len
      (do (body/put! buffer length-header)
          (body/write-ascii buffer (str len))
          (body/write-crlf buffer))
      :else
      ;; A GET would have been chunked, so say so; there is still no body.
      (body/put! buffer chunked-header)))
  (body/write-crlf buffer)
  (body/queue-write! socket buffer)
  (callback))

;; --- response validation ---------------------------------------------------

(defn- unsafe-field-value?
  "A response field value containing CR, LF or NUL. Emitting one splits the
  response: everything after the CRLF is read by the client as further header
  fields, or as a whole second response. This is HTTP response splitting, and
  the injected text is typically attacker-controlled data that a handler placed
  into a header without escaping it."
  [v]
  (some (fn [c] (or (= c (char 13)) (= c (char 10)) (= c (char 0))))
        (str v)))

(defn- invalid-response-headers
  "The offending [name value] pairs of a response, or nil when it is safe."
  [headers]
  (seq
   (for [[k v] headers
         :let  [values (if (vector? v) v [v])]
         :when (or (not (token? (str k)))
                   (some unsafe-field-value? values))]
     [k v])))

(defn- response-header-values
  "Every wire value supplied for the case-insensitive response field `name`."
  [headers name]
  (mapcat (fn [[k v]]
            (when (= name (str/lower-case (str k)))
              (if (vector? v) v [v])))
          headers))

(defn- remove-response-header [headers name]
  (reduce-kv (fn [m k v]
               (if (= name (str/lower-case (str k)))
                 m
                 (assoc m k v)))
             {} headers))

(defn- comma-parts [values]
  (mapcat #(map str/trim (str/split (str %) #",")) values))

(defn- content-length-state [headers]
  (let [values (response-header-values headers "content-length")]
    (if (empty? values)
      {:kind :none}
      (let [parts (distinct (comma-parts values))]
        (if (not= 1 (count parts))
          {:kind :invalid, :values (vec values)}
          (if-some [n (body/parse-content-length (first parts))]
            {:kind :valid, :value (str n)}
            {:kind :invalid, :values (vec values)}))))))

(defn- transfer-encoding-state [headers]
  (let [values (response-header-values headers "transfer-encoding")]
    (if (empty? values)
      {:kind :none}
      (let [parts (comma-parts values)]
        (if (and (= 1 (count parts))
                 (= "chunked" (str/lower-case (first parts))))
          {:kind :chunked}
          {:kind :invalid, :values (vec values)})))))

(defn- valid-status? [status]
  (and (integer? status) (<= 100 status 999)))

(def ^:private internal-error-response
  {:status  500
   :headers {"Content-Type" "text/plain; charset=UTF-8"}
   :body    "Internal Server Error"})

(def ^:private no-content-length-statuses
  "RFC 9112 6.2 — a Content-Length must never be sent on these."
  #{204})

(defn- informational? [status] (and (>= status 100) (< status 200)))

(defn- suppress-framing?
  "1xx and 204 carry neither Content-Length nor Transfer-Encoding."
  [status]
  (or (informational? status) (contains? no-content-length-statuses status)))

(defn- bodiless-status?
  "Statuses that never carry content. 304 keeps the framing headers a 200 would
  have had (RFC 9110 15.4.5), it just sends no body."
  [status]
  (or (suppress-framing? status) (= status 304)))

(defn- write-no-content-response
  "Terminate the header block and send nothing else: no body, and no framing
  headers to imply one."
  [buffer socket callback]
  (body/write-crlf buffer)
  (body/queue-write! socket buffer)
  (callback))

;; --- Ring handler plumbing -------------------------------------------------

(defn- ^{:jolt.aspects/id :http/server-sanitized-response
         :jolt.aspects/role :http/server-response}
  sanitize-response
  "Validate and canonicalize a response before the first byte is written.

  Invalid status/header syntax and unrepresentable framing fail closed to a 500.
  A valid `Transfer-Encoding: chunked` wins over Content-Length, whose field is
  removed before serialization as RFC 9112 requires. Repeated identical decimal
  Content-Length values are collapsed to one canonical field.

  Returns [safe-response problems]."
  [response]
  (let [response (or response {})
        ;; Preserve the historical default only when the key is absent. An
        ;; explicitly supplied nil/false status is metadata, and must fail the
        ;; same three-digit-integer check as any other invalid value.
        status   (if (contains? response :status) (:status response) 200)
        headers  (into {} (or (:headers response) {}))
        unsafe   (invalid-response-headers headers)
        cl       (content-length-state headers)
        te       (transfer-encoding-state headers)
        problems (cond-> []
                   (not (valid-status? status))
                   (conj {:kind :invalid-status, :value status})
                   unsafe
                   (conj {:kind :unsafe-header, :headers (vec unsafe)})
                   (= :invalid (:kind cl))
                   (conj {:kind :invalid-content-length, :values (:values cl)})
                   (= :invalid (:kind te))
                   (conj {:kind :invalid-transfer-encoding, :values (:values te)}))]
    (if (seq problems)
      [internal-error-response problems]
      (let [headers (-> headers
                        (remove-response-header "content-length")
                        (remove-response-header "transfer-encoding"))
            ;; Transfer-Encoding determines response framing and therefore
            ;; suppresses Content-Length when both were supplied.
            headers (cond
                      (= :chunked (:kind te))
                      (assoc headers "Transfer-Encoding" "chunked")

                      (= :valid (:kind cl))
                      (assoc headers "Content-Length" (:value cl))

                      :else headers)]
        [(assoc response :status status :headers headers) nil]))))

(defn- strip-framing-headers
  "Drop any Content-Length/Transfer-Encoding a handler supplied, for statuses
  that must not carry them."
  [headers]
  (reduce-kv (fn [m k v]
               (if (contains? #{"content-length" "transfer-encoding"}
                              (str/lower-case (str k)))
                 m
                 (assoc m k v)))
             {} (into {} headers)))

(defn- ring-responder
  ([request socket handled done buffer read-buffer]
   (ring-responder request socket handled done buffer read-buffer nil
                   (volatile! true)))
  ([request socket handled done buffer read-buffer error-logger]
   (ring-responder request socket handled done buffer read-buffer error-logger
                   (volatile! true)))
  ([request socket handled done buffer read-buffer error-logger handler-returned?]
   (fn respond [response async?]
     (when (compare-and-set! handled false true)
       (let [[response problems] (sanitize-response response)
             _          (when (and problems error-logger)
                          (error-logger
                           (ex-info "Invalid HTTP response; refusing to serialize it"
                                    {:err      ::invalid-response
                                     :problems (vec problems)})))
             status     (:status response)
             suppress?  (suppress-framing? status)
             response   (cond-> response
                          suppress? (update :headers strip-framing-headers))
             headers    (:headers response)
             body       (:body response)
             lc-headers (normalize-headers headers)
             close?     (connection-close? (:headers request))
             head?      (= :head (:request-method request))
             finish     #(do (when (or close?
                                       (connection-close? lc-headers)
                                       ;; The peer half-closed *and* nothing is
                                       ;; left unparsed, so this connection has
                                       ;; nothing more to answer: release it.
                                       ;;
                                       ;; The second half of that test is not
                                       ;; optional. A client may pipeline N
                                       ;; requests and then half-close — an
                                       ;; ordinary pattern, and the one h1spec
                                       ;; drives. Closing on peer-closed? alone
                                       ;; answered the first and silently
                                       ;; dropped the rest, because "no further
                                       ;; request can arrive" is true only of
                                       ;; requests not yet *sent*, not of ones
                                       ;; already sitting in the read buffer.
                                       ;;
                                       ;; Reading the buffer here is safe
                                       ;; precisely because the peer has
                                       ;; half-closed: no more bytes can arrive,
                                       ;; so its contents cannot change under
                                       ;; us.
                                       ;; `read-buffer` is nil on the streaming
                                       ;; path, where this closure runs on a
                                       ;; worker thread and cannot establish
                                       ;; what is still unparsed. It must not
                                       ;; close there — doing so drops requests
                                       ;; pipelined behind a body — so it sets
                                       ;; done and calls resume-reads below, and
                                       ;; the serialized parser decides in
                                       ;; buffer-reads / read-start-line.
                                       ;;
                                       ;; That only works because resume-reads
                                       ;; now re-submits the handler when EOF
                                       ;; has been notified as well as when
                                       ;; bytes are buffered; without that the
                                       ;; connection was never re-entered and
                                       ;; the half-close hung.
                                       (and read-buffer
                                            (not (buf/has-remaining? read-buffer))
                                            (tcp/peer-eof-notified? socket)))
                               (tcp/close socket))
                             (vreset! done true)
                             ;; A small synchronous response completes inside
                             ;; the parser's current read invocation. That
                             ;; invocation immediately loops through
                             ;; buffer-reads itself, so queueing resume-reads is
                             ;; redundant — and a pipeline longer than the
                             ;; bounded control queue used to overflow it before
                             ;; the reactor could run. Resume only when the Ring
                             ;; handler has returned (completion is off-thread),
                             ;; or on the streaming-request path where this
                             ;; handler always runs outside the parser worker.
                             (when (or (nil? read-buffer) @handler-returned?)
                               (tcp/resume-reads socket)))]
         (buf/clear buffer)
         (write-response-head buffer response lc-headers close?)
         (cond
           ;; 1xx / 204 — no body and no framing headers at all.
           suppress?
           (write-no-content-response buffer socket finish)
           ;; HEAD and 304 — the header block a GET would produce, no body.
           (or head? (bodiless-status? status))
           (write-head-response body response lc-headers buffer socket async? finish)
           :else
           (write-response-body body response lc-headers buffer socket async? finish)))))))

(defn- ring-raiser [request respond {:keys [error-handler error-logger]}]
  (fn [exception]
    (error-logger exception)
    (error-handler request #(respond % true) exception)))

(defn- transfer-encoding-error
  "Classify a Transfer-Encoding, per RFC 9112 6.1/6.3.

  If the final coding is not `chunked` the body length cannot be determined, and
  the server MUST answer 400 — this is not merely an unsupported feature, it is
  an unframeable message. A well-formed chunked message that also applies codings
  we do not implement (`gzip, chunked`) is a 501 instead."
  [{{encoding "transfer-encoding"} :headers}]
  (when encoding
    (let [codings (map str/trim (str/split (str/lower-case encoding) #","))
          final   (last codings)]
      (cond
        (= codings ["chunked"])  nil
        (not= final "chunked")   :unframeable-transfer-encoding
        :else                    :unsupported-transfer-encoding))))

(defn- empty-request-body? [{:keys [headers]}]
  (and (not (contains? headers "content-length"))
       (not (contains? headers "transfer-encoding"))))

(defn- ^{:jolt.aspects/id :http/server-ring-handler
         :jolt.aspects/role :http/server
         :jolt.aspects/arity 8}
  invoke-handler
  "Run the Ring handler. `handled` is the response slot: whoever claims it first
  writes the response, so a body-framing error detected by the parser can lock
  the handler out rather than interleaving two responses on one connection."
  ([ring-handler request socket done buffer read-buffer opts]
   (invoke-handler ring-handler request socket done buffer read-buffer opts (atom false)))
  ([ring-handler request socket done buffer read-buffer opts handled]
   (let [handler-returned? (volatile! false)
         respond (ring-responder request socket handled done buffer read-buffer
                                 (:error-logger opts) handler-returned?)
         raise   (ring-raiser request respond opts)]
     (try
       (ring-handler request respond raise)
       (finally (vreset! handler-returned? true))))))

(defn- run-simple-handler [ring-handler state socket read-buffer opts]
  (let [conn    (::conn state)
        buffer  (:response-buffer conn)
        done    (volatile! false)
        request (assoc (->ring-request state) :body (body/empty-request-body))]
    (invoke-handler ring-handler request socket done buffer read-buffer opts)
    {::step :buffer, ::done done, ::conn conn}))

(defn- run-streaming-handler [ring-handler state socket read-buffer opts]
  (let [conn      (::conn state)
        buffer    (:response-buffer conn)
        done      (volatile! false)
        handled   (atom false)
        {:keys [ch body]} (body/request-body (:stream-queue-size opts))
        request   (assoc (->ring-request state) :body body)
        headers   (:headers request)]
    ;; The handler runs on its own pool thread so the reactor's read arity can
    ;; keep feeding the body channel while the handler consumes it.
    (.execute ^java.util.concurrent.Executor (:executor opts)
              ;; No read-buffer for the streaming path: `finish` runs on this
              ;; pool thread, while the reactor may still be filling the read
              ;; buffer for a request that arrived in a later segment. The
              ;; close decision is left to buffer-reads, which runs on the
              ;; reactor and sees a settled buffer.
              (fn [] (invoke-handler ring-handler request socket done buffer nil opts
                                     handled)))
    (transient {::step     :body
                ::conn     conn
                ::chan     ch
                ::done     done
                ::handled  handled
                ::chunked? (body/chunked-transfer? headers)
                ::length   (body/content-length headers)})))

;; A permissive but non-empty host charset: letters, digits and the punctuation
;; that can legitimately appear in host[:port] or an IP-literal. Notably it
;; excludes whitespace and controls.
(def ^:private re-valid-host #"[A-Za-z0-9\-._~%!$&'()*+,;=:\[\]@]+")

(defn- invalid-host?
  "RFC 9112 3.2 — a Host field value that is not a valid host[:port]."
  [{:keys [headers]}]
  (when-some [host (headers "host")]
    (not (re-matches re-valid-host host))))

(def ^:private continue-response
  (body/ascii-bytes "HTTP/1.1 100 Continue\r\n\r\n"))

(defn- expects-continue? [{:keys [headers]}]
  (when-some [e (headers "expect")]
    (= "100-continue" (str/lower-case (str/trim e)))))

(defn- unsupported-expectation?
  "RFC 9110 10.1.1 — the only expectation defined is 100-continue; anything else
  must be answered with 417."
  [{:keys [headers]}]
  (when-some [e (headers "expect")]
    (not= "100-continue" (str/lower-case (str/trim e)))))

(defn- send-continue!
  "RFC 9110 10.1.1 — a client that sent `Expect: 100-continue` is waiting for
  permission before it sends the body. Without this it stalls until its own
  timeout expires (curl uses this for bodies over 1KB), so the interim response
  is sent as soon as the headers are known to be acceptable."
  [socket]
  (tcp/write socket (buf/wrap continue-response)))

(defn- conflicting-framing?
  "RFC 9112 6.3: a request carrying both Content-Length and Transfer-Encoding is
  ambiguously framed and a server MUST reject it. This is the canonical request
  smuggling setup — a front-end that honours one header and a back-end that
  honours the other disagree about where the request ends."
  [{:keys [headers]}]
  (and (contains? headers "content-length")
       (contains? headers "transfer-encoding")))

(defn- invalid-content-length?
  "A Content-Length that is not a single representable non-negative integer. Repeated
  Content-Length fields arrive here joined with commas (\"5,6\"); identical
  values collapse to one, differing values are a smuggling vector and must be
  rejected rather than silently mis-parsed. Values larger than the signed
  64-bit remaining-byte counter are rejected before the handler is invoked."
  [{:keys [headers]}]
  (when-some [v (headers "content-length")]
    (let [parts (distinct (map str/trim (str/split v #",")))]
      (or (not= 1 (count parts))
          (nil? (body/parse-content-length (first parts)))))))

(defn- normalize-content-length
  "Collapse a repeated-but-identical Content-Length (\"5,5\") to a single value,
  so downstream length parsing sees a plain integer."
  [request]
  (if-some [v (get-in request [:headers "content-length"])]
    (assoc-in request [:headers "content-length"]
              (str (body/parse-content-length
                    (str/trim (first (str/split v #","))))))
    request))

(defn- run-ring-handler [ring-handler state socket read-buffer opts]
  (let [request (persistent! (assoc! state ::step ::handled))]
    (cond
      (transfer-encoding-error request)
      {::step :error, ::error (transfer-encoding-error request), ::request request}
      (conflicting-framing? request)
      {::step :error, ::error :conflicting-framing, ::request request}
      (invalid-content-length? request)
      {::step :error, ::error :invalid-content-length, ::request request}
      (not (contains? (:headers request) "host"))
      {::step :error, ::error :missing-host-header, ::request request}
      ;; RFC 9112 3.2 — more than one Host field must be rejected. Repeated
      ;; fields arrive here comma-joined, and a legitimate host never contains
      ;; a comma, so this detects the duplicate.
      (str/includes? (get-in request [:headers "host"]) ",")
      {::step :error, ::error :duplicate-host-header, ::request request}
      ;; RFC 9112 3.2 — and a Host whose value is not a valid host[:port] is
      ;; likewise a 400, not something to route on.
      (invalid-host? request)
      {::step :error, ::error :invalid-host-header, ::request request}
      ;; RFC 9110 10.1.1 — an expectation we do not support is a 417.
      (unsupported-expectation? request)
      {::step :error, ::error :expectation-failed, ::request request}
      :else
      ;; Rebuild the transient: the checks above needed a persistent view.
      (let [request (normalize-content-length request)
            state   (transient request)]
        (when (expects-continue? request)
          (send-continue! socket))
        (if (empty-request-body? request)
          (run-simple-handler ring-handler state socket read-buffer opts)
          (run-streaming-handler ring-handler state socket read-buffer opts))))))

;; --- request body reading --------------------------------------------------

(defn- push-chunk!
  "Hand a chunk of request body to the handler. The blocking put is what applies
  backpressure: while parked, the socket stays WORKING and the reactor stops
  reading from it."
  [ch bs]
  (a/>!! ch bs))

(defn- end-of-body [{::keys [done chan conn]}]
  (a/close! chan)
  {::step :buffer, ::done done, ::conn conn})

(defn- hex-digit? [c]
  (or (and (>= (int c) 0x30) (<= (int c) 0x39))
      (and (>= (int c) 0x41) (<= (int c) 0x46))
      (and (>= (int c) 0x61) (<= (int c) 0x66))))

(defn- parse-chunk-size
  "chunk-size is 1*HEXDIG, optionally followed by chunk extensions after a ';'
  (RFC 9112 7.1.1). Returns the size, or nil if it is not well formed.

  The validation is deliberately strict rather than delegated to a numeric
  parser. Permissive integer parsing here is a well-documented smuggling
  vector: `parseLong` accepts a leading `+`, and `strtoll`-style parsing accepts
  `0x` and `-`, so a front-end and a back-end that disagree about the size of a
  chunk can be made to frame two different requests out of one byte stream."
  [head]
  (let [semi   (str/index-of head ";")
        digits (if semi (subs head 0 semi) head)]
    (when (and (pos? (count digits))
               (every? hex-digit? digits))
      (try (Long/parseLong digits 16)
           (catch :default _ nil)))))

(defn- skip-trailer-section
  "Consume the trailer section that follows the terminating zero-length chunk:
  zero or more field lines, then an empty line (RFC 9112 7.1.2).

  Leaving these bytes in the buffer would be a framing bug, not just an
  omission — on a keep-alive connection the next parse would read the trailer
  lines as the start of the following request."
  [view buffer]
  (loop []
    (if-some [line (buf/read-line-strict view "US-ASCII")]
      (cond
        ;; RFC 9112 7.1 requires CRLF throughout the chunked grammar; unlike the
        ;; start-line and field lines there is no latitude for a lone LF here.
        (= line bare-lf) ::invalid
        (= line "")      (do (buf/set-position! buffer (buf/position view)) ::end)
        :else            (recur))
      ;; Trailer section is incomplete; wait for more bytes.
      nil)))

(defn- body-error
  "Fail a request whose body framing is malformed or terminally incomplete.

  Atomically claims the response slot *before* unblocking a waiting handler. If
  the claim succeeds, the framing error owns the one 400 response; closing the
  channel first would let the handler wake and win that race. If the handler
  already claimed the slot intentionally, close behind its response without
  appending a second one."
  [{::keys [chan handled]} socket error]
  (let [claimed? (or (nil? handled) (compare-and-set! handled false true))]
    (when chan (a/close! chan))
    (if claimed?
      {::step :error, ::error error}
      ;; The handler already replied; the framing is broken either way, so the
      ;; connection cannot be reused.
      (do (tcp/close socket) {::step :done}))))

(defn- invalid-chunk [state socket]
  (body-error state socket :invalid-chunk-size))

(defn- incomplete-body [state socket]
  (body-error state socket :incomplete-request))

(defn- read-chunk-head
  "Parse the chunk-size line at the buffer's position **without consuming it**.
  Returns [size position-after-the-line], ::invalid, or nil when the line has
  not fully arrived.

  Leaving the position alone is what makes re-entry safe. The terminating `0`
  line is followed by a trailer section that may not have arrived yet, and the
  parser keeps no \"inside a trailer\" state — so if the position were advanced
  past the `0` and the trailer turned out to be incomplete, the next call would
  read the trailer's own terminating CRLF as a fresh chunk-size line, find it
  empty, and reject a perfectly good request. Committing only once the whole
  frame is understood is how the pre-incremental parser avoided that."
  [buffer]
  (let [view (buf/duplicate buffer)]
    (when-some [head (buf/read-line-strict view "US-ASCII")]
      (cond
        ;; A chunk-size line terminated by a bare LF. RFC 9112 7.1 requires CRLF
        ;; here, and unlike the start-line and field lines there is no latitude
        ;; to accept a lone LF. Rejected for the same reason as a mis-sized
        ;; chunk: it is a framing discrepancy a front end may not share.
        (= head bare-lf) ::invalid
        ;; A malformed chunk-size is distinct from an incomplete one: waiting
        ;; for more bytes would stall the connection forever.
        (nil? (parse-chunk-size head)) ::invalid
        :else [(parse-chunk-size head) (buf/position view)]))))

;; A chunked body is decoded incrementally, one buffer-full at a time, exactly
;; as the Content-Length path is.
;;
;; It used to require the *whole* chunk — size line, data and trailing CRLF — to
;; be sitting in the read buffer at once, and returned "incomplete, wait for
;; more" otherwise. A chunk larger than :read-buffer-size can never satisfy
;; that, because the buffer cannot hold more than its capacity, so the parser
;; waited forever and the connection hung. With the 8K default that is any
;; client uploading in 16K chunks, which is ordinary curl behaviour.
;;
;; So the parser carries its position within the current chunk instead:
;;
;;   ::chunk-left   bytes still to come in the chunk being read
;;   ::chunk-crlf?  the chunk's data is complete; the CRLF that must follow it
;;                  has not arrived yet
;;
;; Neither key is set between chunks, which is when a chunk-size line is due.
(defn- read-chunked-body
  [{::keys [chan chunk-left chunk-crlf?] :as state} socket buffer]
  (cond
    ;; chunk-data MUST be followed by CRLF (RFC 9112 7.1). Skipping two bytes
    ;; without checking silently accepts a mis-sized chunk, which is exactly how
    ;; a desync is smuggled through.
    chunk-crlf?
    (if (>= (buf/remaining buffer) 2)
      (let [pos (buf/position buffer)]
        (if-not (and (= 13 (bit-and (buf/get-byte buffer pos) 0xff))
                     (= 10 (bit-and (buf/get-byte buffer (inc pos)) 0xff)))
          (invalid-chunk state socket)
          (do (buf/set-position! buffer (+ pos 2))
              (assoc! state ::chunk-crlf? false))))
      (when (tcp/peer-eof-notified? socket)
        (incomplete-body state socket)))

    ;; Mid-chunk: hand the handler whatever has arrived, however little.
    (and chunk-left (pos? (long chunk-left)))
    (if (buf/has-remaining? buffer)
      (let [n  (min (long chunk-left) (buf/remaining buffer))
            bs (buf/get-bytes! buffer n)
            left (- (long chunk-left) n)]
        (push-chunk! chan bs)
        (assoc! state ::chunk-left left ::chunk-crlf? (zero? left)))
      (when (tcp/peer-eof-notified? socket)
        (incomplete-body state socket)))

    :else
    (if-some [r (read-chunk-head buffer)]
      (if (= r ::invalid)
        (invalid-chunk state socket)
        (let [[length after] r]
          (if (zero? (long length))
            ;; The terminating chunk. Its trailer section must be consumed too,
            ;; or the next parse would read the trailer lines as the start of
            ;; the following request. skip-trailer-section commits the buffer
            ;; position only once it has seen the whole section, so an
            ;; incomplete trailer leaves the `0` line unconsumed and this whole
            ;; step is simply retried when more bytes arrive.
            (let [view (buf/duplicate buffer)]
              (buf/set-position! view after)
              (let [t (skip-trailer-section view buffer)]
                (cond
                  (= t ::end)     (end-of-body state)
                  (= t ::invalid) (invalid-chunk state socket)
                  (tcp/peer-eof-notified? socket)
                  (incomplete-body state socket)
                  :else nil)))
            (do (buf/set-position! buffer after)
                (assoc! state ::chunk-left length ::chunk-crlf? false)))))
      (when (tcp/peer-eof-notified? socket)
        (incomplete-body state socket)))))

(defn- read-known-length-body [{::keys [chan length] :as state} socket buffer]
  (let [length (long length)]
    (if (pos? length)
      (if (buf/has-remaining? buffer)
        (let [n  (min length (buf/remaining buffer))
              bs (buf/get-bytes! buffer n)]
          (push-chunk! chan bs)
          ;; assoc! — `state` is transient here, and plain `assoc` on a
          ;; transient throws a ClassCastException on jolt.
          (assoc! state ::length (- length n)))
        (when (tcp/peer-eof-notified? socket)
          (incomplete-body state socket)))
      (end-of-body state))))

(defn- read-body [{::keys [length chunked?] :as state} socket buffer]
  (cond
    length   (read-known-length-body state socket buffer)
    chunked? (read-chunked-body state socket buffer)
    :else    (end-of-body state)))

;; --- steps -----------------------------------------------------------------

(defn- buffer-reads
  "Between requests: only start parsing the next one once the previous response
  has been fully written (keep-alive / pipelining)."
  [{::keys [done conn]} socket buffer]
  (when @done
    (if (and (tcp/peer-eof-notified? socket) (not (buf/has-remaining? buffer)))
      ;; Nothing more can arrive and nothing is left unparsed; the response is
      ;; out, so release the socket rather than holding it open waiting for a
      ;; request that cannot come. Requests already buffered still get parsed:
      ;; closing on peer-closed? alone dropped every pipelined request after the
      ;; first.
      (do (tcp/close socket) {::step :done})
      (init-request conn))))

(defn- write-error-response [{::keys [error request handled]} socket conn opts]
  (let [done    (volatile! false)
        ;; Reuse the request's response slot when there is one. A framing error
        ;; found part-way through a body belongs to a request whose handler may
        ;; already have replied; writing a second response would both corrupt
        ;; the connection and reuse the response buffer underneath the first.
        handled (or handled (atom false))
        buffer  (:response-buffer conn)
        respond (ring-responder (or request {}) socket handled done buffer nil
                                (:error-logger opts))
        handler (err/error-handlers error)]
    (respond (handler (or request {})) false)
    (tcp/close socket)
    ;; Move to a terminal step rather than returning nil. Returning nil would
    ;; leave the state on :error, and any bytes still sitting unconsumed in the
    ;; read buffer (everything after the offending line) make the reactor call
    ;; the read arity again — which would re-enter :error, write the response a
    ;; second time, and tear the socket down before the first one had drained.
    {::step :done}))

(defn- peer-disconnect?
  "True when `exception` represents the peer ending a connection while a
  response is in flight. This is normal cancellation for streaming responses,
  not an application/server failure worth reporting through `:error-logger`."
  [exception]
  (loop [exception exception]
    (when exception
      (let [data (ex-data exception)]
        (or (= :connection-reset (:jolt.net/kind data))
            (= :teensyp.server/socket-closed (:err data))
            (recur (ex-cause exception)))))))

(defn tcp-handler
  "Create a jolt-tcp (teensyp) handler from a Ring handler."
  [handler {:keys [error-logger response-buffer-size port server-name remote-addr
                   max-header-bytes max-header-count]
            max-buf-size :read-buffer-size
            :as opts}]
  (fn
    ([socket]
     (let [{local  :local-address
            remote :remote-address} (tcp/socket-info socket)]
       (init-request
        {:response-buffer (buf/buffer response-buffer-size)
         :base-request    {:scheme      :http
                           ;; getsockname is authoritative for an ephemeral
                           ;; bind. The configured value remains the fallback
                           ;; for in-process Socket fakes and older adapters.
                           :server-port (or (:port local) port)
                           :server-name (or server-name
                                            (:host local)
                                            "127.0.0.1")
                           :remote-addr (or remote-addr
                                            (:host remote)
                                            "127.0.0.1")}})))
    ([state socket buffer]
     ;; Captured once, before stepping: an error step yields a plain map that
     ;; carries no ::conn, and reading it back off a spent transient would fail.
     ;; The connection's response buffer is needed to write the error response.
     (let [conn (::conn state)]
       (loop [state state]
         (if-some [new-state
                   (case (::step state)
                     :start-line (read-start-line state socket buffer max-buf-size)
                     :headers    (read-header state socket buffer max-buf-size
                                              max-header-bytes max-header-count)
                     :handler    (run-ring-handler handler state socket buffer opts)
                     :body       (read-body state socket buffer)
                     :buffer     (buffer-reads state socket buffer)
                     :error      (write-error-response state socket conn opts)
                     ;; :done — the connection has been answered and closed;
                     ;; ignore anything still arriving on it.
                     nil)]
           (recur new-state)
           state))))
    ([state exception]
     (when (and exception (not (peer-disconnect? exception)))
       (error-logger exception))
     ;; A connection closing mid-body must unblock a handler waiting on the body.
     ;; The read arity uses transients. If it throws after persistent! has consumed
     ;; the current state but before returning its replacement, jolt-tcp passes
     ;; that last state here; reading it normally would throw "transient used
     ;; after persistent!" and mask the original exception. A spent state cannot
     ;; contain a live :body step that was successfully installed, so there is no
     ;; channel to release in that case.
     (when-some [ch (try (::chan state) (catch :default _ nil))]
       (a/close! ch))
     nil)))
