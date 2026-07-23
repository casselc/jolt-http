(ns jolt.http.http-model
  "An independent HTTP/1.1 response reader, and generators for well-formed
  requests and their deliveries.

  The reader is the oracle for every generative property here, and it is
  deliberately written from RFC 9112 rather than derived from
  `jolt.http.protocol` — a property whose expected value is computed by the code
  under test proves nothing.

  It replaces the regex helpers in jolt.http.server-test (`status-of`,
  `body-of`, `complete-response?`), which cannot express the questions that
  matter most: how many responses are in this byte stream, in what order, and is
  there anything left over? A duplicated or split response is invisible to a
  regex that searches for one status line, and duplication/splitting is exactly
  what a framing bug produces.

  Bytes are handled as vectors of unsigned octets (0..255) throughout, because
  jolt's byte-arrays read back signed and comparing the two representations
  directly reports spurious mismatches above 0x7f."
  (:require [clojure.string :as str]
            [hegel.generator :as g]))

;; --- octet helpers ---------------------------------------------------------

(defn ->octets
  "Byte-array or String -> vector of unsigned octets."
  [x]
  (cond
    (string? x) (->octets (.getBytes ^String x "UTF-8"))
    (bytes? x)  (let [^bytes a x]
                  (mapv (fn [i] (bit-and (long (aget a i)) 0xff)) (range (alength a))))
    :else       (vec x)))

(defn ->ba
  "Vector of unsigned octets -> byte-array. Only at an I/O boundary."
  ^bytes [octets]
  (let [n (count octets)
        a (byte-array n)]
    (dotimes [i n] (aset a i (unchecked-byte (long (nth octets i)))))
    a))

(defn octets->str [octets]
  (String. (->ba octets) "UTF-8"))

(defn ascii [s] (->octets (.getBytes ^String s "US-ASCII")))

(def ^:private CR 13)
(def ^:private LF 10)

(defn- index-of-crlf
  "Index of the first CRLF at or after `from`, or nil."
  [octets from]
  (let [n (count octets)]
    (loop [i (long from)]
      (cond
        (>= (inc i) n)                                        nil
        (and (= CR (nth octets i)) (= LF (nth octets (inc i)))) i
        :else                                                 (recur (inc i))))))

(defn- read-line-at
  "[line-string next-index] for the CRLF-terminated line starting at `from`, or
  nil if no complete line is present."
  [octets from]
  (when-some [i (index-of-crlf octets from)]
    [(octets->str (subvec (vec octets) from i)) (+ i 2)]))

;; --- the reader ------------------------------------------------------------

(defn- parse-status-line [line]
  (when-some [m (re-matches #"HTTP/1\.1 (\d{3})(?: (.*))?" line)]
    {:status (parse-long (m 1)) :reason (or (m 2) "")}))

(defn- parse-header-line [line]
  (when-some [i (str/index-of line ":")]
    [(str/lower-case (subs line 0 i)) (str/trim (subs line (inc i)))]))

(defn- collect-headers
  "Read field lines up to the empty line. Returns [headers next-index], with
  repeated fields collected into a vector so duplicates stay visible — the
  server emitting a header twice is a thing properties need to assert about."
  [octets from]
  (loop [i from headers {}]
    (if-some [[line next-i] (read-line-at octets i)]
      (if (= line "")
        [headers next-i]
        (if-some [[k v] (parse-header-line line)]
          (recur next-i (update headers k (fnil conj []) v))
          [::malformed-header i]))
      [::incomplete i])))

(defn- header1
  "The single value of a field, or nil. Multiple values -> ::multiple."
  [headers k]
  (when-some [vs (get headers k)]
    (if (= 1 (count vs)) (first vs) ::multiple)))

(defn- dechunk
  "Decode a chunked body starting at `from`. Returns
  [body-octets trailer-headers next-index] or ::incomplete / ::invalid."
  [octets from]
  (loop [i from acc []]
    (if-some [[line next-i] (read-line-at octets i)]
      (let [size-str (let [semi (str/index-of line ";")]
                       (if semi (subs line 0 semi) line))]
        (if-not (re-matches #"[0-9A-Fa-f]+" size-str)
          ::invalid
          (let [size (Long/parseLong size-str 16)]
            (if (zero? size)
              ;; terminating chunk, then the trailer section
              (let [[trailers after] (collect-headers octets next-i)]
                (if (keyword? trailers) ::incomplete [acc trailers after]))
              (let [end (+ next-i size)]
                (if (> (+ end 2) (count octets))
                  ::incomplete
                  (if-not (and (= CR (nth octets end)) (= LF (nth octets (inc end))))
                    ::invalid
                    (recur (+ end 2) (into acc (subvec (vec octets) next-i end))))))))))
      ::incomplete)))

(defn read-response
  "Parse one HTTP/1.1 response from the front of `octets`.

  Returns a map with :status :reason :headers :body :end (the index one past
  this response), or {:error ...} for a stream that is not a well-formed
  response. Framing follows RFC 9112 6: no body at all for a HEAD request or a
  bodiless status, else Transfer-Encoding: chunked, else Content-Length, else
  read-to-end.

  `head?` must be passed for a response to a HEAD request: the framing headers
  describe the body a GET would have returned, and reading them as this
  response's length would consume the *next* response's bytes — which is
  precisely the desync a HEAD bug causes."
  ([octets] (read-response octets false))
  ([octets head?]
   (let [octets (vec octets)]
     (if-some [[line after-status] (read-line-at octets 0)]
       (if-some [{:keys [status reason]} (parse-status-line line)]
         (let [[headers after-headers] (collect-headers octets after-status)]
           (cond
             (= headers ::incomplete)       {:error :incomplete-headers}
             (= headers ::malformed-header) {:error :malformed-header}
             :else
             (let [te   (header1 headers "transfer-encoding")
                   cl   (header1 headers "content-length")
                   base {:status status :reason reason :headers headers}]
               (cond
                 ;; 1xx/204/304 and HEAD carry no content whatever the headers say
                 (or head? (< status 200) (= status 204) (= status 304))
                 (assoc base :body [] :end after-headers)

                 (and (string? te) (= "chunked" (str/lower-case te)))
                 (let [r (dechunk octets after-headers)]
                   (cond
                     (= r ::incomplete) {:error :incomplete-chunked}
                     (= r ::invalid)    {:error :invalid-chunked}
                     :else (let [[body trailers end] r]
                             (assoc base :body body :trailers trailers :end end))))

                 (and (string? cl) (re-matches #"\d+" cl))
                 (let [n   (parse-long cl)
                       end (+ after-headers n)]
                   (if (> end (count octets))
                     {:error :incomplete-body :want n
                      :have (- (count octets) after-headers)}
                     (assoc base :body (subvec octets after-headers end) :end end)))

                 (= cl ::multiple)  {:error :multiple-content-length}
                 (= te ::multiple)  {:error :multiple-transfer-encoding}

                 ;; no framing headers: the body runs to end of stream
                 :else
                 (assoc base :body (subvec octets after-headers) :end (count octets)
                        :to-eof? true)))))
         {:error :bad-status-line :line line})
       {:error :no-status-line}))))

(defn read-responses
  "Parse a whole stream into a vector of responses. `heads` is a seq of booleans
  saying which positions answered a HEAD request. Stops at the first error and
  reports it, along with whatever was left unparsed — leftover bytes are the
  signal of a framing bug, so they are returned rather than ignored."
  ([octets] (read-responses octets nil))
  ([octets heads]
   (loop [i 0 acc [] heads (seq heads)]
     (if (>= i (count octets))
       {:responses acc :trailing []}
       (let [r (read-response (subvec (vec octets) i) (boolean (first heads)))]
         (if (:error r)
           {:responses acc :error (:error r) :trailing (subvec (vec octets) i)}
           (recur (+ i (:end r)) (conj acc r) (next heads))))))))

(defn status-lines
  "How many `HTTP/1.1 ` status lines the stream contains, counted independently
  of framing. More of these than responses expected means a split or duplicated
  response — the thing an at-most-one-response invariant is asserting about."
  [octets]
  (count (re-seq #"HTTP/1\.1 \d{3}" (octets->str octets))))

(defn body-str [response] (octets->str (:body response)))

(defn header
  "A response header's single value, or nil."
  [response k]
  (first (get (:headers response) (str/lower-case k))))


;; --- HTTP chunk framing ----------------------------------------------------
;; NOT the same thing as splitting a message across TCP writes — that is
;; g/chunkings, from jolt-hegel. This splits a body into HTTP/1.1 *chunks*, each
;; of which gets its own size line on the wire.

(defn split-by-sizes [payload sizes]
  (loop [remaining (vec payload)
         sizes (seq sizes)
         chunks []]
    (if (empty? remaining)
      chunks
      (let [requested  (or (first sizes) (count remaining))
            chunk-size (min (max 1 requested) (count remaining))]
        (recur (subvec remaining chunk-size)
               (next sizes)
               (conj chunks (subvec remaining 0 chunk-size)))))))

;; --- request generator -----------------------------------------------------

(def token-char-gen
  (g/string {:min-size 1 :max-size 12
             :alphabet "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&'*+-.^_`|~"}))

;; RFC 9110 5.5 field-value: VCHAR / SP / HTAB, and it may not start or end with
;; whitespace once trimmed, so values are generated without leading/trailing OWS
;; and the OWS handling is exercised separately by the padding in `render`.
(def field-value-gen
  (g/string {:max-size 24
             :alphabet (str "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
                            "!#$%&'*+-.^_`|~()<>@,;:\\\"/[]?={} ")}))

(def path-gen
  (g/fmap #(str "/" %)
          (g/string {:max-size 20
                     :alphabet "abcdefghijklmnopqrstuvwxyz0123456789-._~/%"})))

(def query-gen
  (g/string {:max-size 20
             :alphabet "abcdefghijklmnopqrstuvwxyz0123456789-._~=&%+"}))

(def method-gen
  (g/sampled-from ["GET" "POST" "PUT" "DELETE" "OPTIONS" "PATCH"]))

(defn- trim-ows [s] (str/trim s))

(defn draw-headers!
  "A generated list of [name value] field lines, with the reserved fields that
  the framing depends on excluded so a generated header can never change how the
  message is framed."
  []
  (g/let [n     (g/integer 0 5)
          names (g/vector {:size n} token-char-gen)
          vals  (g/vector {:size n} field-value-gen)]
    (->> (map vector names vals)
         (remove (fn [[k _]]
                   (contains? #{"host" "content-length" "transfer-encoding"
                                "connection" "expect" "trailer"}
                              (str/lower-case k))))
         vec)))

(defn expected-request-headers
  "The `:headers` map the server must produce for these field lines: names
  lower-cased, values OWS-trimmed, repeats comma-joined in arrival order
  (protocol/assoc-request-header!)."
  [field-lines]
  (reduce (fn [m [k v]]
            (let [k (str/lower-case k)
                  v (trim-ows v)]
              (if-some [prev (get m k)]
                (assoc m k (str prev "," v))
                (assoc m k v))))
          {} field-lines))

(defn render-request
  "Render a request to octets. `body-framing` is :none, :content-length or
  [:chunked chunk-sizes]."
  [{:keys [method target field-lines host body body-framing extra-lines]
    :or   {host "localhost"}}]
  (let [body (vec (or body []))
        head (str method " " target " HTTP/1.1\r\n"
                  "Host: " host "\r\n"
                  (apply str (map (fn [[k v]] (str k ": " v "\r\n")) field-lines))
                  (apply str extra-lines)
                  (case (if (vector? body-framing) (first body-framing) body-framing)
                    :content-length (str "Content-Length: " (count body) "\r\n")
                    :chunked        "Transfer-Encoding: chunked\r\n"
                    "")
                  "\r\n")]
    (into (ascii head)
          (case (if (vector? body-framing) (first body-framing) body-framing)
            :content-length body
            :chunked (let [sizes  (second body-framing)
                           chunks (if (seq body) (split-by-sizes body sizes) [])]
                       (into (vec (mapcat (fn [c]
                                            (into (ascii (str (format "%X" (count c)) "\r\n"))
                                                  (into (vec c) (ascii "\r\n"))))
                                          chunks))
                             (ascii "0\r\n\r\n")))
            []))))

(defn draw-request!
  "A well-formed request plus the model of what the server must parse out of it.
  Must be called inside an active property."
  []
  (g/let [method      method-gen
          path        path-gen
          has-query?  (g/boolean)
          query       query-gen
          field-lines (draw-headers!)
          body-kind   (g/sampled-from [:none :content-length :chunked])
          body        (if (= :none body-kind)
                        (g/just [])
                        (g/vector {:max-size 512} (g/octet)))
          chunk-sizes (if (and (= :chunked body-kind) (seq body))
                        (g/vector {:max-size 6} (g/integer 1 (max 1 (count body))))
                        (g/just []))]
    (let [target  (if has-query? (str path "?" query) path)
          framing (case body-kind
                    :chunked [:chunked chunk-sizes]
                    body-kind)
          spec    {:method method :target target :field-lines field-lines
                   :body body :body-framing framing}]
      {:bytes (render-request spec)
       :spec  spec
       :model {:request-method (keyword (str/lower-case method))
               :uri            path
               :query-string   (when has-query? query)
               :protocol       "HTTP/1.1"
               :headers        (expected-request-headers field-lines)
               :body           body}})))
