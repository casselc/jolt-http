# Findings for upstream Capra

Behaviour in **[weavejester/capra](https://github.com/weavejester/capra)** that
jolt-http had to change or deliberately not port, and that looks like a defect
or a gap in the JVM original rather than a jolt-specific concern.

This is a report *about* upstream, distinct from
[`UPSTREAM-IMPROVEMENTS.md`](UPSTREAM-IMPROVEMENTS.md), which records what
**Jolt** (the language and runtime) would need to make jolt-http smaller.
Nothing here is about Chez, FFI, or AOT.

Capra's transport findings live in the companion report,
[`jolt-tcp/docs/UPSTREAM-TEENSYP-FINDINGS.md`](../../jolt-tcp/docs/UPSTREAM-TEENSYP-FINDINGS.md).
Two of those reach Capra directly and are cross-referenced below.

## Baseline and method

| | |
| --- | --- |
| Upstream reviewed | `weavejester/capra` @ `f7b01e5f3179e50739ea108a6c1178ceec07eaa0` (2026-07-20, "Factor HTTP handling out into capra.http") |
| Transport | `weavejester/teensyp` @ `879da3519480b33cc4c0db3680337d99519ab534` |
| Compared against | `jolt-http` @ `3046a24`, this branch |
| Method | Source review of all 497 lines of `src/capra/http.clj` plus `server.clj`, driven by the rule set jolt-http's conformance suite encodes |

**Six findings have been reproduced against a live JVM Capra server** — see
the *Confirmed* column below. The rest are derived from reading upstream
source; for those, jolt-http is a reimplementation, so a failing case there is
evidence that a *class* of bug exists in the shared parser design, not that the
JVM code fails identically. Each carries the source lines it is read from and a
request to send, and should be run before filing. Line references are
`file:line` against the SHA above.

The confirmed set was checked by building upstream at the SHAs above (JDK 21,
Clojure 1.12.5, real `ring-core-protocols` 1.15.5), running Capra on loopback
with a handler echoing `:uri`, and driving raw sockets at it:

| Finding | Payload | Observed |
| --- | --- | --- |
| 1 | `Content-Length: 6` + `Transfer-Encoding: chunked`, then a second request | two 200s; **the smuggled `GET /admin` reached the handler** |
| 2 | `Content-Length: -5`, then a second request | two 200s; **the smuggled `GET /admin` reached the handler** |
| 3 | `0\r\nX: y\r\n\r\n` terminator | 200 then 400 — the trailer was parsed as a request line |
| 6 | leading bare `\n` | connection dropped, zero bytes returned; `IndexOutOfBoundsException` at `teensyp/buffer.clj:68` |
| 13 | two `Host` fields | 200, not the required 400 |
| 14 | `5;n=v` chunk extension | connection dropped; `NumberFormatException` at `http.clj:415` |

Finding 17's stdout claim is incidentally confirmed too: those stack traces
arrived on stdout, not stderr.

A second pass added three more, driven against a handler that places
attacker-controlled bytes into a response header:

| Finding | Payload | Observed on `f7b01e5` |
| --- | --- | --- |
| 4 | `Location` containing CRLF + a forged second response | **two responses on the wire**; the injected `X-Injected` header and the forged `PWNED` body were both delivered to the client |
| 4 | non-integer `:status` | zero responses — the connection is dropped by an NPE in `write-ascii` |
| 7 | `HEAD` on a 200 route | the full body was written after the header block |
| 7 | `{:status 204 :body "hello"}` | `Content-Length: 5` and the body were both emitted |
| 12 | `Content-Length : 5` (space before colon) | **the smuggled `GET /admin` reached the handler** |

Finding 4 is therefore a working HTTP response-splitting attack against
upstream as it stands, not a latent risk.

Capra's README calls the adapter experimental and not recommended for
production, and this list should be read in that light — it is a conformance
review of work in progress, not a report on a shipped server. The rule set is
the one jolt-http adopted from [cispa/http-conformance][] and the parsing
discrepancy classes catalogued by [HTTP Garden][]; the framing findings are the
same classes those projects found in widely deployed servers.

[cispa/http-conformance]: https://github.com/cispa/http-conformance
[HTTP Garden]: https://arxiv.org/abs/2405.17737

## Summary

| # | Severity | Confirmed | Class | Finding |
| --- | --- | --- | --- | --- |
| [1](#1) | High | **executed** | smuggling | `Content-Length` **and** `Transfer-Encoding` are both accepted, and Content-Length wins |
| [2](#2) | High | **executed** | smuggling | A negative `Content-Length` is accepted; the body is then parsed as a pipelined request |
| [3](#3) | High | **executed** | smuggling | The chunked trailer section is not consumed and is parsed as the next request |
| [4](#4) | High | **executed** | injection | Response header names and values are written unvalidated (response splitting, CWE-113) |
| [5](#5) | High | reasoned | availability | More than 32 synchronous pipelined requests overflow the control queue and kill the connection |
| [6](#6) | Medium-High | **executed** | availability | A leading bare LF crashes the connection (inherited from `teensyp.buffer/read-line`) |
| [7](#7) | Medium-High | **executed** | framing | `HEAD` responses carry a body; `204`/`304` are not special-cased |
| [8](#8) | Medium-High | reasoned | DoS | No aggregate header-count or header-byte limit |
| [9](#9) | Medium-High | reasoned | correctness | A truncated request body is indistinguishable from a complete one |
| [10](#10) | Medium-High | reasoned | liveness | An async handler's response sink is never closed, so the connection hangs |
| [11](#11) | Medium | reasoned | corruption | The `ThreadLocal` response buffer is unsafe on any non-thread-per-task executor |
| [12](#12) | Medium | **executed** | smuggling | `Foo : bar` and obs-fold lines are accepted as distinct headers |
| [13](#13) | Medium | **executed** | conformance | Multiple `Host` headers are accepted |
| [14](#14) | Medium | **executed** | conformance | Chunk extensions are rejected; chunk sizes accept `+`/`-` |
| [15](#15) | Low-Medium | reasoned | conformance | Malformed or duplicate `Content-Length` closes abruptly instead of answering 400 |
| [16](#16) | Low-Medium | reasoned | conformance | `Expect: 100-continue` is not implemented |
| [17](#17) | Low | partial | misc | Error logger prints to stdout; >2 GiB responses throw; a stray `Connection` header |

---

<a id="1"></a>
## 1. `Content-Length` and `Transfer-Encoding` are both accepted, Content-Length wins

**Severity: high — this is the CL.TE request-smuggling primitive.**

`run-ring-handler` validates that any transfer encoding is `chunked`
(`http.clj:361-362`, `400-409`) and that a `Host` header exists, but never
checks whether `Content-Length` and `Transfer-Encoding` are both present.
`run-streaming-handler` then records **both** (`http.clj:374-384`):

```clojure
{::step :body ... ::chunked? (chunked-transfer? (:headers req))
                  ::length   (content-length   (:headers req))}
```

and `read-body-stream` dispatches on length first (`http.clj:450-454`):

```clojure
(cond
  (::length state)   (read-known-length-body-stream state socket buffer)
  (::chunked? state) (read-chunked-body-stream state socket buffer)
  ...)
```

So for a request carrying both, Capra frames the body by `Content-Length` and
ignores the chunked encoding. RFC 9112 §6.3 requires such a message to be
rejected (or, for a non-rejecting recipient, `Transfer-Encoding` to win and
`Content-Length` to be discarded). Picking Content-Length is the option no
specification allows, and it is the exact disagreement a front end needs: a
proxy that honours `Transfer-Encoding` forwards one request where Capra sees
one request plus a prefix of the next.

**Request to send.**

```
POST / HTTP/1.1
Host: x
Content-Length: 6
Transfer-Encoding: chunked

0

GET /admin HTTP/1.1
Host: x

```

A conforming origin sees one request. Capra should be expected to consume 6
bytes as the body and then parse `GET /admin` as a pipelined request.

**Fix.** Reject with 400 when both are present, before dispatching.

*jolt-http:* rejected with 400, listed first in the README's conformance
section, with fixed and generated coverage.

<a id="2"></a>
## 2. A negative `Content-Length` is accepted

**Severity: high — smuggling primitive.**

`content-length` is `(some-> content-length Long/parseLong)`
(`http.clj:142-143`). `Long/parseLong` accepts a leading `-` or `+`, so
`Content-Length: -5` yields `-5`, which is non-`nil` and therefore truthy in
`read-body-stream`'s `cond`. `read-known-length-body-stream` then does
(`http.clj:437-448`):

```clojure
(if (pos? length)
  ...
  (next-request st))     ; -5 is not positive -> immediately move on
```

The connection advances to `::step :buffer` with **zero body bytes consumed**.
Once the response completes, `buffer-reads` (`http.clj:456-457`) re-enters at
`:start-line` and parses whatever followed the headers as the next request.

RFC 9112 §6.2 defines `Content-Length` as `1*DIGIT`; `-5`, `+5`, `0x5`, `5 `
and the empty string are all invalid and must be rejected. Capra accepts the
first two and throws on the rest (see finding 15).

**Request to send.** As in finding 1, with `Content-Length: -5` and no
`Transfer-Encoding`.

**Fix.** Validate against `1*DIGIT` before parsing, and bound the result to a
signed 64-bit value; reject anything else with 400.

*jolt-http:* non-numeric, signed, hex-prefixed, empty and 64-bit-overflowing
content lengths are all 400, with a generative property over decimal strings
(`test/jolt/http/body_property_test.clj:158-186`).

<a id="3"></a>
## 3. The chunked trailer section is not consumed

**Severity: high — smuggling primitive.**

`read-chunk!` (`http.clj:411-418`) reads the chunk-size line, then consumes
`length + 2` bytes for the data and its CRLF. For the terminal chunk, `length`
is 0, so it consumes `0\r\n` plus exactly **two** more bytes and declares the
body finished (`http.clj:424-429`). That is correct only when the terminator is
bare `0\r\n\r\n`.

RFC 9112 §7.1.2 allows a trailer section between the last chunk and the final
CRLF. Given `0\r\nX-Trailer: v\r\n\r\n`, Capra consumes `0\r\n` and then `X-`,
leaving `Trailer: v\r\n\r\n` in the buffer — which `buffer-reads` hands to the
start-line parser as the next request.

There is no trailer handling anywhere in `capra.http`.

**Request to send.**

```
POST / HTTP/1.1
Host: x
Transfer-Encoding: chunked

0
X: y

```

**Fix.** After the zero-length chunk, consume header lines until an empty one,
and discard or expose them; do not assume a fixed two-byte terminator.

*jolt-http:* the trailer section is consumed so it cannot be misread as the
start of the next request; chunk extensions are likewise ignored rather than
mis-framed.

<a id="4"></a>
## 4. Response headers are written unvalidated

**Severity: high — HTTP response splitting, CWE-113.**

```clojure
;; http.clj:297-300
(defn- write-header [^ByteBuffer buffer k v]
  (doto buffer
    (.put (ascii-bytes k)) (.put (byte COLON)) (.put (byte SPACE))
    (.put (ascii-bytes v)) (write-crlf)))
```

No check that `k` is a token, and none that `v` is free of CR, LF or NUL. A
handler that puts unvalidated input into a header — the canonical case being a
`Location` built from a query parameter — lets a client inject `\r\n` and
forge the rest of the response, including a complete second response on a
keep-alive connection.

`ascii-bytes` is `String.getBytes(US_ASCII)` (`http.clj:26-27`), which maps
non-ASCII to `?` but passes control characters through unchanged.

The status line is unvalidated too: `write-status-line` (`http.clj:290-295`)
interpolates `(str status)` directly, so a non-integer or out-of-range
`:status` produces a malformed status line, and `reason/status->reason` is a
plain map lookup returning `nil` for an unknown code — which `write-ascii`
then NPEs on.

**Fix.** Validate before the first response byte is written, and replace the
response with a 500 if it fails. That ordering matters: once bytes are on the
wire nothing can be retracted.

*jolt-http:* a response header whose name is not a token, or whose value
contains CR/LF/NUL, is refused and turned into a 500; likewise a non-three-digit
status, an unrepresentable `Content-Length`, or a transfer encoding other than
exactly `chunked`. The fail-closed model, its known-bug control (the old
header-only evaluator, SAT with an invalid status admitted), and its
non-vacuity witness are in
[`proofs/http-fail-closed.md`](proofs/http-fail-closed.md).

<a id="5"></a>
## 5. Deep synchronous pipelining overflows the control queue

**Severity: high (availability); reproducible in a few lines.**

Every completed response calls `tcp/resume-reads` (`http.clj:350-354`):

```clojure
(write-body-to-socket body response headers buffer socket async?
                      #(do (when (or close? (connection-close? headers))
                             (tcp/close socket))
                           (vreset! done true)
                           (tcp/resume-reads socket)))
```

For a **synchronous** handler with a response small enough to write in one go,
that whole chain runs inline inside the read arity: `sync-handler` calls
`respond` directly (`server.clj:13-18`), `run-writer` calls `tcp/write`
(`http.clj:167-172`), and teensyp's `write` invokes the callback on the calling
thread when the socket takes everything (`teensyp/server.clj:110-114`). `done`
is then true, so `buffer-reads` (`http.clj:456-457`) returns a fresh request
state and `tcp-handler`'s loop (`http.clj:481-492`) parses the *next* buffered
request without ever returning.

So N pipelined requests in one read buffer enqueue **N control events inside a
single read-arity invocation**. teensyp only drains controls when the
connection is not `WORKING` (`teensyp/server.clj:362-363`), which it is for the
whole invocation, so nothing drains. `:control-queue-size` defaults to 32
(`teensyp/server.clj:195`), and `queue-control` throws `::control-queue-full`
once it is reached (`teensyp/server.clj:166-167`). That exception unwinds into
teensyp's handler catch and **closes the connection**.

The client gets 32 responses and a dropped connection. Nothing in Capra bounds
how many pipelined requests arrive in one 8 KiB read — a minimal `GET` is about
40 bytes, so ~200 fit.

Upstream's `pipelined-requests-test` (`test/capra/server_test.clj:623-658`)
uses depth 2 and an **async** handler whose first response completes on a
`future`, which breaks the inline chain — so the synchronous deep case is not
covered.

**Request to send.** 64 concatenated `GET / HTTP/1.1\r\nHost: x\r\n\r\n`
against a synchronous handler returning a short body, written in one `send`.
Expect 32 responses, then EOF.

**Fix.** Skip the resume when the response completed inline and the read buffer
is still owned by the current invocation; the wake is only needed when the
completion happens off-thread.

*jolt-http:* the inline-completion branch contributes zero resume events. The
bounded model, the pre-fix control (SAT at `requests=2, capacity=1`), and the
non-vacuity witness proving the off-thread wake is *not* suppressed are in
[`proofs/inline-resume-capacity.md`](proofs/inline-resume-capacity.md); the
runtime witness sends 64 pipelined requests through a control queue of 32.

<a id="6"></a>
## 6. A leading bare LF crashes the connection

**Severity: medium-high. Root cause is in teensyp; the impact is Capra's.**

`teensyp.buffer/read-line` probes the byte *before* the LF without checking it
is at or after the buffer position (`teensyp/buffer.clj:68`). Capra's read view
starts at position 0, so a request whose first byte is a bare LF makes the probe
`(.get buffer -1)` → `IndexOutOfBoundsException` → teensyp closes the
connection.

```
printf '\nGET / HTTP/1.1\r\nHost: x\r\n\r\n' | nc localhost 8080
```

RFC 9112 §2.2 asks a recipient to ignore at least one empty line received prior
to the request line, so this is a case a server is expected to tolerate. A
leading **CRLF** does not crash but produces a 400 rather than being ignored,
since `parse-start-line` (`http.clj:61-71`) rejects the empty line.

Full analysis and the two-line fix are in
[teensyp finding 3](../../jolt-tcp/docs/UPSTREAM-TEENSYP-FINDINGS.md#3).

*jolt-http:* a leading empty line before a request line is ignored, and the
underlying `read-line` guard is fixed in jolt-tcp.

<a id="7"></a>
## 7. `HEAD` responses carry a body; `204`/`304` are not special-cased

**Severity: medium-high (keep-alive framing desync).**

There is no mention of `HEAD` anywhere in `capra.http`. `write-body-to-socket`
is dispatched purely on the body's type (`http.clj:241-284`), so a `HEAD`
request to a route returning a 200 with a body gets the full body written after
the header block.

On a keep-alive connection the client — which knows a `HEAD` response has no
body — reads the header block, stops, and interprets the body bytes as the
status line of the next response. Every subsequent response on that connection
is misaligned. RFC 9110 §9.3.2 is explicit that a `HEAD` response must carry the
header fields a `GET` would and no body.

The same applies to status-based framing rules, none of which are implemented:
`1xx` and `204` must never carry `Content-Length` or `Transfer-Encoding`, and
`304` carries them but no body (RFC 9112 §6.1-6.3). A handler returning
`{:status 204 :body "x"}` produces a desync.

**Fix.** Suppress the body for `HEAD` while emitting the framing headers a `GET`
would; strip framing fields for `1xx`/`204`; keep them but drop the body for
`304`.

*jolt-http:* all four cases are implemented and covered, including a generated
property asserting a `HEAD` response equals the corresponding `GET` response's
head.

<a id="8"></a>
## 8. No aggregate header-count or header-byte limit

**Severity: medium-high (memory exhaustion).**

`read-header` (`http.clj:98-104`) bounds only a **single** line, and only
indirectly:

```clojure
(when-not (< (.limit ^ByteBuffer buffer) max-buffer-size)
  {::step :error, ::error :request-header-field-too-large})
```

That fires when one line fails to fit the read buffer. Nothing bounds how many
header fields a request may carry, or the total bytes of the header section.
Consumed bytes are compacted out of the read buffer by teensyp, while the parsed
fields accumulate in the request's transient map (`http.clj:84-87`), which grows
without limit.

A single connection sending an unbounded stream of small, well-formed headers
and never terminating the section will grow that map until the process runs out
of memory. Each field also allocates two `String`s plus map overhead, so the
amplification factor over the wire bytes is substantial.

Upstream's `too-large-header-test` (`test/capra/server_test.clj:374`) covers the
single-oversized-field case, which is the bound that exists.

**Fix.** Track cumulative header bytes (including CRLF) and field count across
the section, and answer 431 when either exceeds a configured maximum.

*jolt-http:* `:max-header-bytes` (64K) and `:max-header-count` (100) → 431, with
a generative property driving the exact boundary — the configured value is
accepted, the next byte or field is not
(`test/jolt/http/protocol_property_test.clj:158-214`).

<a id="9"></a>
## 9. A truncated request body looks like a complete one

**Severity: medium-high (silent data corruption).**

When the transport reports EOF, teensyp calls the close arity, which Capra
routes to `close-response` (`http.clj:459-460`, `493-497`):

```clojure
([{::keys [step] :as state} exception]
 (when exception (error-logger exception))
 (case step
   :body (close-response state exception)
   nil))
```

`close-response` hands the exception to `teensyp.stream/input-stream-handler`'s
close arity — which **ignores it** (`teensyp/stream.cljc:165-168`):

```clojure
([^StreamState state _exception]
 (with-lock (.lock state)
   (vreset! (.closed state) true)
   (.signal ^Condition (.canread state))))
```

The blocked reader then returns `-1` (`teensyp/stream.cljc:142`), which is a
clean end-of-stream. So a handler doing `(slurp (:body request))` on an upload
truncated by a reset peer receives a short body and **no indication anything
went wrong**. A handler that persists it stores a silently truncated object.

The connection is gone either way, so the client will not get a response — but
the handler has already acted on incomplete input.

The same code path means EOF part-way through a request line or header section
produces no response at all rather than a 400 (the `case` falls through to
`nil`). That is less serious, since the peer is gone, but it does mean a client
that half-closes after a partial request gets silence.

**Fix.** Make the request body's read raise on transport EOF before the
declared length or the terminal chunk is reached, rather than reporting
end-of-stream.

*jolt-http:* EOF is terminal in every parser state; an incomplete request
produces one 400 and closes, EOF while idle closes silently, and a
streaming handler that already claimed the response slot does not get a second
response. The transition relation, its EOF-insensitive control (12
counterexamples), and the witnesses are in
[`proofs/http-fail-closed.md`](proofs/http-fail-closed.md).

<a id="10"></a>
## 10. An async handler's response sink is never closed

**Severity: medium-high (connection hangs).**

```clojure
;; http.clj:278-280
(try (ring/write-body-to-stream body response out)
     (finally
       (when-not async? (.close out))))
```

For a streaming body (the `Object` arm — a seq, an `InputStream`, anything not
`String`/byte-array/`File`/`nil`) the adapter closes the sink only when the
handler is **synchronous**. `ring.core.protocols/write-body-to-stream` is a
synchronous protocol: it has finished producing by the time it returns,
regardless of how the handler was invoked. The `async?` flag describes how the
Ring handler completes, not how the body is produced.

With `:async? true`, the sink is left open. Closing it is what runs `on-close`
→ the completion callback (`http.clj:273-274`) → `resume-reads`. So for a
chunked response the terminal `0\r\n\r\n` is never written
(`http.clj:123-128`), the callback never fires, `done` stays false, reads are
never resumed, and **the connection hangs until the client times out**.

**Fix.** Close the sink unconditionally in the `finally`, and make the wrappers'
`close` idempotent so a body that closes early is still correct.
`chunked-output-stream` already guards (`http.clj:123-128`);
`limited-output-stream` does not (`http.clj:137-140`).

*jolt-http:* the adapter closes the sink in a `finally` without consulting
handler mode, and both wrappers are idempotent, so the observable finalization
count is exactly one across all four combinations of handler mode and
custom-body close behaviour. The former async-conditioned finalizer is retained
as the SAT bug control in
[`proofs/http-fail-closed.md`](proofs/http-fail-closed.md).

<a id="11"></a>
## 11. The `ThreadLocal` response buffer is unsafe on a pooled executor

**Severity: medium, conditional — safe on virtual threads, unsafe on the
documented fallback.**

```clojure
;; http.clj:322-326, 341-349
(def ^:private response-buffer (ThreadLocal.))
...
(let [buffer (get-cached response-buffer #(ByteBuffer/allocate buf-size))]
  (.clear ^ByteBuffer buffer)
  (write-response-head buffer response headers close?)
  (write-body-to-socket body response headers buffer socket async? ...))
```

The buffer is passed **by reference** to `tcp/write`, and teensyp queues that
same `ByteBuffer` object when the socket cannot take it all
(`teensyp/server.clj:171-181`). `run-writer`'s continuation
(`http.clj:167-172`) then keeps re-using it across executor tasks until the body
is done. So the buffer stays live, owned by one response, well after
`ring-responder` returns.

Meanwhile the thread that allocated it is free. If it picks up **another
connection's** read task, `ring-responder` calls `get-cached` → same
`ThreadLocal` → same buffer → `.clear` → writes the new response head over
bytes still queued for the first connection. The first client receives the
second client's response head.

Capra's default executor is `newVirtualThreadPerTaskExecutor`
(`server.clj:26-32`), where each task gets a fresh thread and the `ThreadLocal`
is effectively per-response — so this cannot bite. But the same function falls
back to `(Executors/newFixedThreadPool 256)` when virtual threads are
unavailable, and `:executor` is a documented option
(`server.clj:66-67`). On either, the precondition holds.

The trigger is a partial write, i.e. a response large enough or a client slow
enough to apply backpressure — the case where cross-talk matters most.

**Fix.** Make the response buffer per connection rather than per thread. Because
teensyp calls a connection's handler arities serially, per-connection needs no
locking. Failing that, the fallback executor must be thread-per-task and
`:executor` must be documented as requiring the same.

*jolt-http:* the response buffer is per connection — originally because jolt
has no usable `ThreadLocal` constructor, but it removes this hazard as a side
effect, which is why it is worth reporting rather than filing as a jolt
divergence.

<a id="12"></a>
## 12. `Foo : bar` and obs-fold lines are accepted as distinct headers

**Severity: medium (parsing discrepancy).**

`parse-header` splits on the first colon and takes everything before it as the
name (`http.clj:89-96`):

```clojure
(let [name  (str/lower-case (subs line 0 colon-index))
      value (str/trim (subs line (inc colon-index)))]
```

No validation that the name is a token. Two consequences:

- **Whitespace before the colon.** `Content-Length : 5` yields the header key
  `"content-length "` — with a trailing space. Capra does not recognise it as
  `Content-Length`, but a front end that trims does. RFC 9112 §5.1 requires
  rejection with 400 precisely because that disagreement is a smuggling
  primitive.
- **Obsolete line folding.** A continuation line starting with whitespace is
  not rejected as such: `" foo: bar"` parses as a header named `" foo"`. Only
  a folded line with no colon at all becomes a 400. RFC 9112 §5.2 deprecates
  obs-fold and requires a 400 from a server that does not support it.

Neither is validated for control characters in the value either — `str/trim`
strips leading and trailing whitespace but passes NUL, bare CR and DEL through
into the request map.

The same is true of the request method: `parse-start-line` (`http.clj:61-71`)
takes everything before the first space as the method and keywordises it with
no token check, so `(:request-method request)` can be an arbitrary keyword built
from client-controlled bytes.

**Fix.** Validate field names and the method against the RFC 9110 token
grammar, reject any line beginning with SP or HTAB, and reject control
characters in field values.

*jolt-http:* all four are 400, listed in the README's conformance section.

<a id="13"></a>
## 13. Multiple `Host` headers are accepted

**Severity: medium (conformance).**

`run-ring-handler` checks only for presence (`http.clj:404-405`):

```clojure
(not (contains? (:headers request) "host"))
```

Repeated fields are joined with commas by `assoc-request-header!`
(`http.clj:84-87`), so `Host: a` + `Host: b` becomes `"a,b"` — which satisfies
`contains?`. RFC 9112 §3.2 requires a 400 for any request with more than one
`Host` field. Where the front end and the origin pick different Host values,
this is a routing-discrepancy primitive.

**Fix.** Count occurrences during parsing and reject anything other than
exactly one.

*jolt-http:* more than one `Host`, or none, is a 400.

<a id="14"></a>
## 14. Chunk extensions are rejected; chunk sizes accept `+` and `-`

**Severity: medium (conformance and robustness).**

```clojure
;; http.clj:411-418
(when-some [head (buf/read-line chunked-buffer StandardCharsets/US_ASCII)]
  (let [start  (.position chunked-buffer)
        length (Long/parseLong head 16)]
```

`Long/parseLong` with radix 16 is the wrong acceptor for `chunk-size`:

- **Chunk extensions throw.** RFC 9112 §7.1.1 defines `chunk-size [
  chunk-ext ]`, so `5;name=value\r\n` is legal and must be ignored. Capra gets
  `NumberFormatException`, which unwinds into teensyp's handler catch and drops
  the connection — no 400, no response.
- **Signs are accepted.** `parseLong` allows a leading `-` or `+`. A `-5` chunk
  size passes, and the subsequent `(.position buffer (+ start length 2))`
  (`http.clj:417`) rewinds the buffer, then `(.limit chunked-buffer (+ start
  length))` sets a limit below the position → `IllegalArgumentException`.
  Whether that is reachable as more than a connection kill depends on the
  arithmetic, but the acceptor should not admit it in the first place.
- Leading/trailing whitespace, `0x` prefixes and the empty string all throw the
  same way.

**Fix.** Parse `chunk-size` as `1*HEXDIG`, stop at `;`, and skip the extension
text to the CRLF. Reject anything else with 400.

*jolt-http:* a chunk size that is not `1*HEXDIG` is a 400 — no `+`, `-`, `0x`,
`_` or surrounding whitespace, and not empty — and chunk extensions are ignored.

<a id="15"></a>
## 15. Malformed or duplicate `Content-Length` closes abruptly

**Severity: low-medium (conformance).**

`content-length` (`http.clj:142-143`) is called without a guard, so any value
`Long/parseLong` rejects throws `NumberFormatException` from inside the read
arity. teensyp catches it and closes the connection, so the client gets **no
response** where RFC 9112 §6.2 calls for a 400.

Duplicate fields land here too, whether or not they agree: two `Content-Length:
5` lines are joined to `"5,5"` (`http.clj:84-87`) and fail to parse. RFC 9112
allows identical duplicates to collapse to one value and requires a 400 only
for differing ones — so Capra rejects a legal request, and rejects it by
hanging up rather than answering.

**Fix.** Validate before parsing (finding 2 covers the grammar), collapse
identical duplicates, and answer 400 for the rest.

*jolt-http:* repeated `Content-Length` with differing values is a 400;
identical ones collapse to one canonical field.

<a id="16"></a>
## 16. `Expect: 100-continue` is not implemented

**Severity: low-medium (interoperability).**

Nothing in `capra.http` mentions `Expect`. A client that sends
`Expect: 100-continue` and waits for the interim response before sending its
body will stall until its own timeout expires — typically one second in `curl`,
longer elsewhere — and then send anyway. Every large upload from such a client
pays that latency.

RFC 9110 §10.1.1 also requires a 417 for any expectation the server does not
understand; Capra ignores unknown expectations entirely.

**Fix.** Once the header section is known to be acceptable, write
`HTTP/1.1 100 Continue\r\n\r\n` before reading the body; answer 417 for any
other expectation.

*jolt-http:* implemented, with the interim response deliberately sequenced
*after* header acceptability is established so a request that will be rejected
does not first get a 100.

<a id="17"></a>
## 17. Smaller observations

- **The default error logger prints to stdout.** A misplaced paren
  (`server.clj:23-24`):

  ```clojure
  (defn- default-error-logger [exception]
    (locking *err* (binding [*out* *err*]) (prn exception)))
  ```

  The `binding` form has an empty body and `prn` sits outside it, so errors go
  to `*out*`. Should be `(binding [*out* *err*] (prn exception))`.

- **Responses over 2 GiB throw.** `limited-output-stream` builds an
  `AtomicInteger` from the parsed `Content-Length` (`http.clj:130-131`), which
  is a `long`. Any value above `Integer/MAX_VALUE` fails the narrowing cast
  with `IllegalArgumentException`. `AtomicLong` is the drop-in.

- **`limited-output-stream` NPEs on a non-chunked transfer encoding.** It is
  reached when `chunked-response?` is false (`http.clj:275-277`), which
  includes "transfer encoding present but not `chunked`, no content length" —
  and then `(AtomicInteger. nil)` throws.

- **A stray `Connection` header.** `chunked-header` (`http.clj:36-38`) emits
  `Connection: Transfer-Encoding` alongside the chunked encoding. It is written
  after `write-response-head`, which may already have emitted
  `Connection: close` (`http.clj:313`), so a response can carry two
  contradictory `Connection` fields.

- **`run-server` builds an executor it may discard.** `new-default-options`
  calls `new-default-executor` unconditionally (`server.clj:34-42`) before
  merging user options, so a caller-supplied `:executor` leaves an orphaned
  `ExecutorService`. Harmless today (neither implementation starts threads
  eagerly), but it should be lazy. Note also that teensyp shuts down whatever
  executor it is handed — see
  [teensyp finding 4a](../../jolt-tcp/docs/UPSTREAM-TEENSYP-FINDINGS.md#4).

- **The `Date` header is recomputed per response.** `rfc-1123-date-time`
  (`http.clj:286-288`) constructs a `ZonedDateTime` and formats it on every
  response. It has one-second resolution, so caching it for the current second
  is free. Performance only.

## Not upstream findings

These jolt-http divergences exist because the host lacks a JVM facility, not
because Capra is wrong. The README documents each:

- `RequestBody`/`StreamableBody` protocols instead of `InputStream` and
  `ring.core.protocols` (jolt's `proxy` is `reify`, so `InputStream` cannot be
  subclassed; and there is no Ring on jolt).
- `teensyp.buffer/Buffer` instead of `java.nio.ByteBuffer`; charset **name
  strings** instead of `Charset` objects.
- The `Date` header computed locally (no `java.time`).
- No `:direct-read-buffer?` (no direct buffers).
- A bounded default pool instead of virtual threads.
- `java.io.File` dispatched by an explicit `instance?` test (protocol dispatch
  on host shim classes falls through to `Object` on jolt).

jolt-http also keeps Capra's **deliberate** deviation of answering an HTTP/1.0
request with 505 rather than 400, on the same reasoning: both adapters support
HTTP/1.1 only, so the version is the more accurate complaint. It is the single
[h1spec](https://github.com/dropseed/h1spec) failure in jolt-http's 32/33
`--strict` result, and upstream would see the same.

## Suggested filing order

1. **Findings 1, 2, 3** — the three smuggling primitives. Small, independent
   fixes, and they belong together as one hardening pass.
2. **Finding 4** — response splitting; also small, and it is the one a
   downstream application cannot defend against itself.
3. **Finding 5** — a concrete, reproducible connection kill with a known
   one-branch fix.
4. **Findings 7, 8** — framing correctness and a DoS bound; both self-contained.
5. **Findings 9, 10** — both change observable handler behaviour, so worth
   agreeing on before patching.
6. **Finding 6** — belongs with the teensyp report but should be cross-filed,
   since the symptom appears in Capra.
7. **Finding 11** — worth raising even if the default configuration is safe,
   because the fallback path is documented as supported.
8. The rest as conformance cleanup.

Findings 1, 2, 3, 5, 13 and 14 are each a handful of lines and could plausibly
arrive as one patch series against `capra.http`.
