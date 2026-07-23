(ns jolt.http.error
  "Functions for handling client or server errors.")

(defn- plaintext-response [status body]
  {:status  status
   :headers {"Connection" "close"
             "Content-Type" "text/plain; charset=UTF-8"}
   :body    body})

(def error-handlers
  "A map of plaintext error handlers for responding to bad requests"
  {:unsupported-transfer-encoding
   (fn [{{:strs [transfer-encoding]} :headers}]
     (plaintext-response
      501 (str "Unsupported request transfer encoding: \"" transfer-encoding
               "\".\nOnly \"chunked\" transfer encoding supported.")))
   :uri-too-long
   (constantly (plaintext-response 414 "URI too long."))
   :request-header-field-too-large
   (constantly (plaintext-response 431 "Request header field too large."))
   :missing-host-header
   (constantly (plaintext-response 400 "Missing \"Host\" header in request."))
   :http-version-not-supported
   (fn [{:keys [bad-protocol]}]
     (plaintext-response
      505 (str "Unsupported HTTP version: \"" bad-protocol
               "\".\nOnly \"HTTP/1.1\" is supported.")))
   :invalid-request-start-line
   (constantly (plaintext-response 400 "Invalid HTTP request start line."))
   :invalid-request-header
   (fn [{:keys [bad-header]}]
     (plaintext-response
      400 (str "Invalid HTTP request header line: \"" bad-header "\".")))
   ;; RFC 9112 5.1 — whitespace between a field name and its colon must be
   ;; rejected, not trimmed: it is a request smuggling vector.
   :whitespace-before-colon
   (fn [{:keys [bad-header]}]
     (plaintext-response
      400 (str "Whitespace before colon in request header: \"" bad-header "\".")))
   ;; RFC 9112 6.3 — Content-Length and Transfer-Encoding together are
   ;; ambiguous framing, the classic request smuggling setup.
   :conflicting-framing
   (constantly
    (plaintext-response
     400 (str "Both Content-Length and Transfer-Encoding present.\n"
              "The request framing is ambiguous.")))
   :invalid-content-length
   (constantly (plaintext-response 400 "Invalid or conflicting Content-Length."))
   ;; RFC 9112 5.2 — obs-fold is not accepted by a non-proxy server.
   :obs-fold-header
   (constantly
    (plaintext-response 400 "Obsolete line folding in request headers."))
   ;; RFC 9112 2.2 — a bare CR is not a line terminator, and implementations
   ;; disagree about how to treat one, so it is rejected.
   :bare-cr
   (constantly (plaintext-response 400 "Bare CR in request."))
   ;; RFC 9112 2.2 *permits* a recipient to treat a lone LF as a line
   ;; terminator, but doing so is a request smuggling vector whenever a front
   ;; end does not: the two then frame different messages out of one byte
   ;; stream. jolt-http is an origin server that will usually sit behind a
   ;; proxy, so it takes the strict reading. RFC 9112 7.1 leaves no choice at
   ;; all for chunk framing, where CRLF is required outright.
   :bare-lf
   (constantly (plaintext-response 400 "Bare LF line terminator in request."))
   :invalid-field-name
   (fn [{:keys [bad-header]}]
     (plaintext-response
      400 (str "Invalid character in request header field name: \""
               bad-header "\".")))
   ;; RFC 9112 3.2 — exactly one Host field is permitted.
   :duplicate-host-header
   (constantly (plaintext-response 400 "Multiple \"Host\" headers in request."))
   :invalid-field-value
   (fn [{:keys [bad-header]}]
     (plaintext-response
      400 (str "Control character in request header value: \""
               bad-header "\".")))
   ;; RFC 9110 9.1 — the method is a token.
   :invalid-request-method
   (constantly (plaintext-response 400 "Invalid HTTP request method."))
   ;; RFC 9112 7.1 — chunk-size is 1*HEXDIG. Lenient numeric parsing here is a
   ;; documented request smuggling vector.
   :invalid-chunk-size
   (constantly (plaintext-response 400 "Invalid chunk size in request body."))
   :invalid-host-header
   (constantly (plaintext-response 400 "Invalid \"Host\" header value."))
   ;; RFC 9112 6.3 — Transfer-Encoding whose final coding is not chunked leaves
   ;; the body length undeterminable, which is a 400 rather than a 501.
   :unframeable-transfer-encoding
   (fn [{{:strs [transfer-encoding]} :headers}]
     (plaintext-response
      400 (str "Transfer-Encoding \"" transfer-encoding
               "\" does not end with \"chunked\";\n"
               "the length of the request body cannot be determined.")))
   ;; RFC 9110 10.1.1
   :expectation-failed
   (fn [{{:strs [expect]} :headers}]
     (plaintext-response
      417 (str "Unsupported expectation: \"" expect "\".")))})
