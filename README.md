# jolt-http

A [Capra][]-style HTTP/1.1 server for **[jolt][]** (Clojure on Chez Scheme) — no
JVM, no Java NIO. It layers an incremental HTTP/1.1 parser and a Ring-shaped
handler contract over [jolt-tcp][]'s `jolt.net`-backed readiness reactor.

This is to Capra what jolt-tcp is to teensyp: a native reimplementation of the
adapter over jolt-tcp's owned sockets. It replaces [ring-chez-adapter][], which read
each request into a String, served one request per connection, and always closed.

Supports HTTP/1.1 only: keep-alive, pipelining, chunked request and response
bodies, streaming request bodies, file responses, and async handlers.

[capra]: https://github.com/weavejester/capra
[jolt]: https://github.com/jolt-lang/jolt
[jolt-tcp]: https://github.com/casselc/jolt-tcp
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

`write-body-to-sink` is synchronous: it must finish producing the body before
returning and must not retain the sink. The adapter closes the sink exactly once
after the call returns; a custom implementation may close it early because
close is idempotent. `:async?` controls Ring handler completion, not body
production. A future asynchronous producer needs an explicit completion and
cancellation SPI rather than retaining this blocking sink.

Each sink write blocks on jolt-tcp's outcome-bearing completion. A native write
failure therefore throws back through `write-body-to-sink`/`respond` and retires
the connection; it cannot strand the producing handler on a success-only
callback. An async handler may catch that exception in the task which called
`respond`.

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
| `:executor`             | Borrowed executor for handler calls                  | fixed pool  |
| `:pool-size`            | Size of the default handler pool                     | 32          |
| `:port`                 | The port number to listen on                         | 80          |
| `:read-buffer-size`     | Read buffer size; bounds one request/header line     | 8K          |
| `:max-header-bytes`     | Maximum aggregate header-section bytes incl. CRLF    | 64K         |
| `:max-header-count`     | Maximum number of request header fields              | 100         |
| `:recv-buffer-size`     | The receive buffer size (i.e. the SO_RCVBUF option)  |             |
| `:remote-addr`          | Optional override for the actual peer address        | peer address|
| `:response-buffer-size` | The size of the buffer used for the response         | 32K         |
| `:reuse-address?`       | The SO_REUSEADDR socket option                       | false       |
| `:server-name`          | Optional override for the actual local address       | local address|
| `:shutdown-executor?`   | Adopt and stop a supplied executor                    | false       |
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
  hex-prefixed, empty, or signed-64-bit-overflowing `Content-Length`.
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
- A header section over `:max-header-bytes` or `:max-header-count` (431). The
  exact configured boundary is accepted; the next byte or field is rejected.
- EOF in a partial request line, header section, fixed-length body, chunk, or
  trailer produces one 400 and closes. EOF while idle closes silently.

On the response side:

- A `HEAD` response carries the header block a `GET` would, and no body.
- `1xx` and `204` never carry `Content-Length` or `Transfer-Encoding`, even if
  the handler supplies them; `304` carries them but no body.
- A response header whose name is not a token, or whose value contains CR, LF
  or NUL, is refused and turned into a 500 rather than emitted. This prevents
  HTTP response splitting (CWE-113) when a handler puts unescaped input into a
  header.
- A non-three-digit integer status, unrepresentable `Content-Length`, or
  transfer encoding other than exactly `chunked` is likewise replaced with a
  safe 500 before any response byte is written. Repeated identical content
  lengths collapse to one canonical field; valid chunked transfer encoding
  wins over and removes content length.
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
  Framing invariance under every write split, aggregate header boundaries,
  Content-Length and chunked agreement, response metadata fail-closed behavior,
  every truncated EOF prefix, HEAD equalling GET, pipelining, response
  splitting, and a mutation fuzz asserting every answer is well formed and
  terminal. A stateful model generates whole request sequences on one
  connection.
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
regression. The bounded [response-validation, terminal-EOF, and
sink-finalization proofs](docs/proofs/http-fail-closed.md) record the
fail-closed models, known-bug controls, non-vacuity witnesses, and their
implementation-test boundaries.

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

  An executor created by jolt-http is transferred to jolt-tcp and shut down as
  part of deterministic server cleanup. A supplied `:executor` is borrowed by
  default; pass `:shutdown-executor? true` to transfer its ownership explicitly.

- **Connection metadata is truthful.** `:server-port` comes from the bound local
  endpoint, including the kernel-selected port after `:port 0`;
  `:server-name` and `:remote-addr` default to the actual numeric local and peer
  addresses. Their options remain explicit overrides for proxy deployments.

## Testing

```sh
JOLT_PWD="$PWD" /path/to/casselc-jolt/bin/jolt -A:test -m hegel.install
JOLT_PWD="$PWD" /path/to/casselc-jolt/bin/jolt -M:test
```

Linux x86_64, Linux aarch64 (`ubuntu-24.04-arm`), macOS arm64 and macOS x86_64
(`macos-15-intel`) all have observed native runtime evidence for the complete
real-loopback suite with source-built Chez 10.4.1 — these are no longer
candidate rows. Because libhegel 0.30.1 publishes no Darwin/x86_64 asset, the
Intel job builds its exact tagged source and supplies the resulting library
explicitly.

Every POSIX lane runs with `JOLT_HEGEL_REQUIRED=1`, matching both Windows lanes.
Installing libhegel is not the same as requiring it: a missing library already
aborts the run at namespace load, but the flag additionally refuses to report
success for a run that loaded libhegel and then executed no generative cases at
all.

Windows x86_64 **and Windows aarch64** both have observed native runtime
evidence for the complete real-loopback suite. The pinned jolt-tcp revision
ships reviewed Winsock readiness backends and a public client for both
architectures, so the former portable-only caveat no longer applies and **no
runtime or socket group is skipped on either target**.

Two lanes run there. The first is a dependency-free HTTP runtime gate
(`-M:windows-runtime-test`) that declares no `:extra-deps`, so real Windows
socket coverage cannot disappear because jolt-hegel failed to resolve or
install; it requires a port-zero listen, real request/response, keep-alive,
pipelining, a request body large enough to force reader backpressure, a
half-close that is still answered, and a deterministic stop. The second runs the
complete `-M:test` suite with `JOLT_HEGEL_REQUIRED=1`. Both are driven from
native PowerShell through `tools/test-windows-source.ps1`, which invokes Chez
directly via the runtime's `host\chez\cli.ss` and never routes execution through
bash. See [docs/runtime/windows-http-runtime.md](docs/runtime/windows-http-runtime.md)
for the observed evidence and the pins it was taken against.

Windows aarch64 runs the **same two lanes, and gates on both**, on
`windows-11-vs2026-arm`. It builds official Chez 10.4.1 from source, asserts
`runner.arch == ARM64` and Chez `(machine-type) == tarm64nt`, then runs the same
dependency-free real-loopback gate and the same `JOLT_HEGEL_REQUIRED=1` suite as
x86-64 — the same 55 scenario groups, the same 8 tests / 68 assertions, and the
same 316 checks. No runtime or socket group is skipped on either architecture.

The earlier non-gating preview, which opened no socket and asserted that the
transport failed closed with `:unsupported-target`, is gone: the pinned jolt-tcp
revision reaches a jolt.net carrying reviewed Winsock readiness for aarch64 as
well as x86-64. The architecture is declared by the runner through
`JOLT_EXPECTED_ARCH` and asserted as an exact `[:windows :aarch64 64]` target,
never inferred from the running process, so an emulated x86-64 Jolt fails before
any HTTP test can pass.

Both Windows architectures are **source-runtime** evidence only: native Chez,
source-mode Jolt, `JOLT_AOT_CACHE=0`. Neither uses a packaged joltc, a devboot,
or an AOT cache, and neither is evidence for those.

Runs a framework-less acceptance suite plus three generative layers
([jolt-hegel][]) over real loopback TCP, driving raw
sockets so that malformed requests, split requests and pipelining can be
exercised exactly. Scenarios are ported from Capra's test suite. Acceptance
scenarios run under a 60-second watchdog; the pure, in-process protocol, and
loopback property groups have 180-, 300-, and 960-second bounds respectively.
The loopback client uses a blocking recv with no socket timeout, so a lost
response would otherwise hang the run instead of failing it.

Hegel properties derive a stable seed from each property's name by default, so
the same revision explores the same cases in every CI run. To replay or explore
one explicit non-negative signed 64-bit seed across a selected property group:

```sh
JOLT_HTTP_HEGEL_SEED=6635181287260819147 \
  JOLT_PWD="$PWD" /path/to/casselc-jolt/bin/jolt \
  -M:test "protocol properties"
```

Run a subset by naming scenarios:

```sh
JOLT_PWD="$PWD" /path/to/casselc-jolt/bin/jolt \
  -M:test "pipelining" "keep-alive"
```

The current reviewed core baseline is
`46e1f74fc14f29283586900ef4b98c45375c0500`, held in a single `JOLT_CORE_SHA`
workflow variable so no platform can validate against a different core.
`deps.edn` pins jolt-tcp at `e27d5c7152d5746b96382587a084c4f2001f3cb6`, which
transitively pins jolt-net at `c3747385235df812e0d739a3e9f71c4dfb07b474`;
jolt-net is never declared directly at the HTTP layer.

Progress is also written to the absolute
`jolt-http-test-progress.log` path under the platform temp directory
(overridable with `JOLT_HTTP_TEST_TMPDIR`; see `hegel-support/temp-path`, which
exists because a drive-qualified Windows path is not absolute to jolt's
`java.io.File`). Protocol-property
entries include the exact test-var name. Jolt block-buffers stdout when it is
redirected, so on a hang that file is the durable record of how far the run got.
Because Jolt futures cannot currently cancel a running task, a watchdog timeout
fails the gate and suppresses all later scenarios rather than letting the timed
out task overlap them.

## License

EPL-2.0 OR GPL-2.0-or-later, matching Capra and teensyp.
