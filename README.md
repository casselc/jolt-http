# jolt-http

A [Capra][]-style HTTP/1.1 server for **[jolt][]** (Clojure on Chez Scheme) — no
JVM, no Java NIO. It layers an incremental HTTP/1.1 parser and a Ring-shaped
handler contract over [jolt-tcp][]'s `poll(2)` reactor.

This is to Capra what jolt-tcp is to teensyp: a native reimplementation of the
adapter for jolt's FFI sockets. It replaces [ring-chez-adapter][], which read
each request into a String, served one request per connection, and always closed.

Supports HTTP/1.1 only: keep-alive, pipelining, chunked request and response
bodies, streaming request bodies, file responses, and async handlers.

[capra]: https://github.com/weavejester/capra
[jolt]: https://github.com/jolt-lang/jolt
[jolt-tcp]: https://github.com/jolt-lang/jolt-tcp
[ring-chez-adapter]: https://github.com/jolt-lang/ring-chez-adapter

## Usage

```clojure
(require '[jolt.http.server :as http])

(defn handler [_request]
  {:status  200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body    "Hello World"})

(def server (http/run-server handler :port 3000))
;; ... later ...
(http/stop-server server)
```

Options can be supplied as variadic arguments or as a map. The server also works
with `with-open`.

> **The process will not exit on its own.** jolt-http loads core.async (for
> request body streaming), which starts non-daemon threads. End a `-main` with
> `(System/exit 0)`.

## Handler contract

Ring-shaped. Synchronous by default; pass `:async? true` for 3-arity
`(fn [request respond raise] ...)` handlers.

The request map carries `:request-method`, `:uri`, `:query-string`, `:headers`
(lower-cased, repeated headers joined with commas), `:scheme`, `:protocol`,
`:server-port`, `:server-name`, `:remote-addr` and `:body`.

### Request bodies

Ring specifies `:body` as an `InputStream`. jolt cannot provide one — its `proxy`
is just `reify`, so `InputStream` cannot be subclassed, and jolt's io coercions
reject a reified stream. `:body` is therefore a `jolt.http.body/RequestBody`:

```clojure
(require '[jolt.http.body :as body])

(defn handler [request]
  {:status 200
   :headers {}
   :body (str "you sent: " (body/body-string (:body request) "UTF-8"))})
```

- `(body-recv body)` — block for the next chunk as a byte-array, `nil` at end.
- `(body-bytes body)` — block for the whole body as one byte-array.
- `(body-string body charset)` — block for the whole body, decoded.

Bodies stream: chunks are handed over as they arrive, and a consumer that falls
behind applies backpressure through TCP flow control rather than buffering
without limit.

### Response bodies

`String`, byte-array, `java.io.File`, a seq of strings, or `nil`. Strings, byte
arrays and files are sent with a `Content-Length`; seqs and other types are
chunked.

Extend `jolt.http.body/StreamableBody` (the `ring.core.protocols` analogue) to
support your own type:

```clojure
(extend-protocol body/StreamableBody
  MyType
  (write-body-to-sink [this _response sink]
    (let [bs (.getBytes (render this) "UTF-8")]
      (body/sink-write! sink bs 0 (alength bs)))))
```

Note that protocol dispatch on `java.io.File` does not work on jolt (both
`extend-protocol` and `extend` fall through to `Object`), so files are dispatched
by an explicit `instance?` test internally.

## Options

| Key                     | Description                                          | Default     |
|-------------------------|------------------------------------------------------|-------------|
| `:async?`               | Whether to use 3-arity asynchronous Ring handlers    | false       |
| `:control-queue-size`   | The max number of queued control events              | 32          |
| `:error-handler`        | An async Ring handler called on uncaught exceptions  | 500 response|
| `:error-logger`         | A function that takes an exception and logs it       | prints       |
| `:executor`             | An executor for handler calls                        | fixed pool  |
| `:pool-size`            | Size of the default handler pool                     | 32          |
| `:port`                 | The port number to listen on                         | 80          |
| `:read-buffer-size`     | Read buffer size; bounds the request line + headers  | 8K          |
| `:recv-buffer-size`     | The receive buffer size (i.e. the SO_RCVBUF option)  |             |
| `:remote-addr`          | Reported as `:remote-addr` (jolt exposes no peer)    | 127.0.0.1   |
| `:response-buffer-size` | The size of the buffer used for the response         | 32K         |
| `:reuse-address?`       | The SO_REUSEADDR socket option                       | false       |
| `:server-name`          | Reported as `:server-name` on requests               | 127.0.0.1   |
| `:stream-queue-size`    | Request body chunks buffered before backpressure     | 8           |
| `:write-buffer-size`    | The write buffer size in bytes                       | 128K        |
| `:write-queue-size`     | The maximum number of writes that can be queued      | 64          |

## Conformance and hardening

The adapter validates message syntax strictly rather than guessing, because
nearly every "lenient" HTTP parse is a request-smuggling primitive: it lets a
front-end and a back-end disagree about where one request ends and the next
begins. The following are rejected with a 400 (or 501 where noted):

- `Content-Length` together with `Transfer-Encoding` (RFC 9112 6.3).
- Repeated `Content-Length` with differing values; a non-numeric, signed,
  hex-prefixed or empty `Content-Length`.
- A `chunk-size` that is not `1*HEXDIG` — no `+`, `-`, `0x`, `_` or surrounding
  whitespace, and not empty.
- Any `Transfer-Encoding` other than exactly `chunked` (501), including
  `chunked, chunked` and `,chunked`.
- Whitespace between a field name and its colon (RFC 9112 5.1).
- Obsolete line folding, i.e. a header line starting with whitespace (5.2).
- A bare CR anywhere in the request line or a header line (2.2).
- A line terminated by a bare LF rather than CRLF, anywhere in the message.
  RFC 9112 2.2 *permits* a recipient to accept a lone LF in the request line and
  header fields, and 7.1 permits nothing of the sort in chunked framing. Both
  are rejected here: jolt-http is an origin server that will usually sit behind
  a front end, and a front end that requires CRLF while the origin does not
  frames a different message out of the same bytes — the same reasoning that
  makes a bare CR a 400.
- A field name that is not a token, and a control character in a field value.
- A request method that is not a token (RFC 9110 9.1).
- More than one `Host` field, or none (RFC 9112 3.2).
- Any HTTP version other than `HTTP/1.1` (505).

On the response side:

- A `HEAD` response carries the header block a `GET` would, and no body.
- `1xx` and `204` never carry `Content-Length` or `Transfer-Encoding`, even if
  the handler supplies them; `304` carries them but no body.
- A response header whose name is not a token, or whose value contains CR, LF
  or NUL, is refused and turned into a 500 rather than emitted. This prevents
  HTTP response splitting (CWE-113) when a handler puts unescaped input into a
  header.
- A leading empty line before a request line is ignored (RFC 9112 2.2).
- Chunk extensions are ignored and the trailer section is consumed, so neither
  can be misread as the start of the next request.
- `Expect: 100-continue` gets an interim `100 Continue` once the headers are
  known to be acceptable; any other expectation is a 417 (RFC 9110 10.1.1).
- A client that half-closes its write side after sending — send request,
  `shutdown(SHUT_WR)`, read reply — still gets its response, and the connection
  is then released rather than held open for a request that cannot arrive.

The one deliberate deviation is that an HTTP/1.0 request is answered with 505
rather than 400, including when it carries `Transfer-Encoding: chunked`. Like
Capra, this adapter supports HTTP/1.1 only, so the version is the more accurate
complaint. [h1spec][] reports this as its single failure; everything else in its
suite passes (32/33 in `--strict` mode).

The test suite covers these directly. The rule set was drawn from
[cispa/http-conformance][] (MIT), the suite from *Who's Breaking the Rules?*
(ACM AsiaCCS 2024), and from the parsing-discrepancy classes catalogued by the
[HTTP Garden][] project, which found 100+ such bugs in widely deployed servers.
HTTP Garden is GPLv3 and nothing is copied from it — the tests here cover the
bug *classes* it documents, with payloads written from the RFC grammar.

Conformance is also checked with [h1spec][], which runs against a live server:

```
joltc run dev/h1spec-server.clj &      # or any server you like
h1spec --strict 127.0.0.1:8080
```

[cispa/http-conformance]: https://github.com/cispa/http-conformance
[HTTP Garden]: https://arxiv.org/abs/2405.17737
[h1spec]: https://github.com/dropseed/h1spec
[jolt-hegel]: https://github.com/chucklehead-dev/jolt-hegel

### Generative conformance testing

The rules above are pinned by fixed payloads *and* explored generatively, so
coverage comes from classes of input rather than from a list of remembered
examples. Three layers, all folded into `joltc -M:test`:

- **Pure properties** (`jolt.http.body-property-test`) — the RFC-1123 date
  arithmetic against an independently written civil-date algorithm, and the
  writer/sink layer driven at generated buffer capacities down to one byte.
- **Protocol properties** (`jolt.http.protocol-property-test`) — the state
  machine driven in-process through a `teensyp.server/Socket` fake, so hundreds
  of generated message streams run in the time a handful of loopback cases take.
  Framing invariance under every write split, Content-Length and chunked
  agreeing, HEAD equalling GET, pipelining, response splitting, and a mutation
  fuzz asserting every answer is well formed and terminal. A stateful model
  generates whole request sequences on one connection.
- **Loopback properties** (`jolt.http.server-property-test`) — what the fake
  cannot reach: the reactor's send loop, write-credit accounting, backpressure
  on a real sender, and a genuine half-close.

Expected values come from an HTTP/1.1 response reader in
`jolt.http.http-model`, written from RFC 9112 rather than derived from the
parser — a property whose oracle is the code under test proves nothing.

All eight loopback properties run in the default gate. The two rapid-churn
properties formerly exposed an fd-reuse race in jolt-tcp; they remain available
through `run-known-flaky-properties!` as a compatibility helper for focused
stress.

The bounded [inline-response control-capacity
proof](docs/proofs/inline-resume-capacity.md) records the source facts, Z3
counterexample query and semantic controls behind the synchronous pipelining
regression.

## Differences from Capra

- **Request bodies** are a `RequestBody` protocol value, not an `InputStream`
  (see above). Response bodies use the `StreamableBody` protocol in place of
  `ring.core.protocols`.
- **No Ring dependency** — there is no Ring on jolt, so the protocols are
  defined here.
- **Buffers** are `teensyp.buffer/Buffer` rather than `java.nio.ByteBuffer`, and
  charsets are name strings (`"UTF-8"`), not `Charset` objects.
- **The response buffer is per connection**, not per thread: jolt has no usable
  `ThreadLocal` constructor. jolt-tcp calls a connection's handler arities
  serially, so it needs no locking.
- **The `Date` header** is computed here (jolt's core has no `java.time`) and
  cached for the current second.
- **No `:direct-read-buffer?`** — jolt has no direct buffers.
- **The default executor is an explicitly bounded pool.** Capra uses a
  virtual-thread-per-task executor, but jolt has no M:N virtual threads. In
  v0.4.15 the cached, virtual-thread, and work-stealing constructor shims all
  map to a fixed 32-worker implementation, so none provides Capra's scaling
  semantics. A separate high-concurrency native-I/O workload has ended in
  Chez's `nonrecoverable invalid memory reference`, but that runtime safety case
  still needs a minimal reproducer. This server therefore keeps configurable
  bounded policy and queues excess requests; raise `:pool-size` deliberately
  for blocking workloads.

  This does not deadlock even though a handler may block waiting for one of its
  own writes: jolt-tcp runs write-completion callbacks on a separate executor,
  so the callback that releases a blocked handler always has a thread. (Sharing
  one pool deadlocks at exactly `pool-size` concurrent streaming responses —
  that is why jolt-tcp grew a `:callback-executor`.)

## Testing

```
joltc -M:test
```

Runs a framework-less acceptance suite plus three generative layers
([jolt-hegel][]) over real loopback TCP, driving raw
sockets so that malformed requests, split requests and pipelining can be
exercised exactly. Scenarios are ported from Capra's test suite. Each scenario
runs under a 60-second watchdog, because the loopback client uses a blocking
recv with no socket timeout and a lost response would otherwise hang the run
instead of failing it.

Run a subset by naming scenarios:

```
joltc -M:test "pipelining" "keep-alive"
```

Progress is also written to `/tmp/jolt-http-test-progress.log`; jolt block-buffers
stdout when it is redirected, so on a hang that file is the only record of how
far the run got.

## License

EPL-2.0 OR GPL-2.0-or-later, matching Capra and teensyp.
