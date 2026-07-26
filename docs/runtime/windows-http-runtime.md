# Native Windows HTTP runtime evidence (W6B)

This records what was actually observed while promoting jolt-http from
portable-only Windows coverage to real loopback HTTP runtime coverage over the
reviewed W6A jolt-tcp public API, and the two platform findings that came out of
it. It is evidence, not a support matrix: every claim below names the pins it
was taken against.

## Pins

| Component | Pin |
| --- | --- |
| jolt-http base | `9a87db5607bfe1eb0cca6865a292832afb49f8a7` (`codex/jolt-upstream-fork`) |
| jolt-tcp (W6A, reviewed) | `6a311ea8242c867f906ce164bd39d7f33a499a3f` |
| jolt-net (transitive, via jolt-tcp) | `a4a4deb6b757d5e86aeb941cf646927e21420df6` |
| Jolt core (every CI and runtime checkout) | `85f645aa1178e4b631198dcbaf46bdad1283750b` |
| jolt-hegel (test-time only) | `e03127174bcaea4ffa1c0cef11bde0efa009e9dc` |
| libhegel | 0.30.1 |
| Chez Scheme | 10.4.1 |

Native toolchain used for the Windows observations: `D:\chez-10.4.1\bin\scheme.exe`
with the Jolt runtime at `D:\src\jolt-proposal-net-runtime-w3` (itself at Jolt
`85f645aa`), driven from native PowerShell by `tools/test-windows-source.ps1`.

## Layering

Production `jolt-http` reaches the network only through jolt-tcp:
`jolt.http.server` requires `jolt.http.protocol` and `teensyp.server`, and
nothing under `src/` imports `jolt.net` or `jolt.ffi`. That was preserved —
**`src/` is byte-identical to the base commit** (`git diff --stat -- src` is
empty). The Windows gate drives the client side through `teensyp.client`,
jolt-tcp's public API, and touches `jolt.net` only to assert that a readiness
poller really opens on the target.

## Observed results

| Lane | Result |
| --- | --- |
| Windows x86-64, `-M:windows-runtime-test` (dependency-free) | 8 tests, 68 assertions, 0 failures, 0 errors — exit 0 |
| Windows x86-64, `-M:test` with `JOLT_HEGEL_REQUIRED=1` | 316 checks, 0 failures — exit 0 |
| Linux x86-64, `-M:test` with `JOLT_HEGEL_REQUIRED=1` | 316 checks, 0 failures — exit 0 |
| Linux x86-64, `-M:test` with the flaky witness seed | 316 checks, 0 failures; 6/6 clean replays of the loopback group |

The dependency-free gate reports the same 68 assertions on Linux and on native
Windows, so the two platforms are running the same contracts.

## Finding 1 — the flaky backpressure witness is a jolt-tcp re-arm latency

The witness carried into this task was:

```
JOLT_HTTP_HEGEL_SEED=9157075391771664454
property: server/request-backpressure
generated case: {:size 93388, :framing :content-length}
observation: two 8-second response timeouts followed by a passing replay
```

**W6A does not remove it.** On the new graph the seed still reproduced: 2 of 5
replays failed, at sizes 93388 and 120000.

It is not an HTTP defect. Three measurements place it below this repository.

**1. Progress is bounded; only latency varies.** Fifteen consecutive 93388-byte
exchanges against the property's exact fixture (1 KB read buffer, stream queue
of 2), under a 120 s watchdog, all delivered the body whole and correct. Response
latencies, in ms:

```
183  2188  2216  3222  4219  4245  4246  4248
6228 6234  6251  7245  7301  8257  8296
```

Zero wedges. The values are a small base cost plus a whole number of ~1000 ms
steps — which is exactly jolt-tcp's `net/await-ready` poll timeout
(`teensyp/server.clj`, the reactor loop and its shutdown drain both wait 1000 ms).

**2. It scales with reactor read cycles, not with queue depth.** Same body size,
varying the fixture:

| read-buffer | stream-queue | read-ms min | read-ms max | over 8 s |
| --- | --- | --- | --- | --- |
| 1024 | 2 | 2205 | 9283 | 4/10 |
| 1024 | 8 | 183 | 7283 | 0/10 |
| 8192 | 2 | 106 | 1137 | 0/10 |
| 8192 | 8 | 107 | 154 | 0/10 |

Widening the read buffer (fewer reactor read cycles) removes the stalls;
deepening the stream queue does not. The frequency is roughly one stall per
10–20 read cycles.

**3. It reproduces with no HTTP layer at all, and it is not core.async.** A pure
`core.async` producer/consumer over a size-2 channel — the exact shape
`jolt.http.protocol` uses for request-body streaming — completes 92 blocking
handoffs in 1–2 ms, with no stall. But a plain `teensyp.server` echo/consume
server with a 1 KB read buffer and no HTTP involved reproduces the stall
directly (max 2060 ms against a min of 35 ms, byte counts always exact).

The owning layer is therefore **jolt-tcp's reactor re-arm path**: when a handler
arity completes in the window after the reactor has drained its pending set but
before it re-enters `net/await-ready`, the connection is only re-serviced on the
next tick of that 1000 ms wait. jolt-http's fixture merely amplifies it, because
a 1 KB read buffer over a 93 KB body is ~92 exposures per case.

**It is not observed on native Windows x86-64 at all** — the same property
completes 20 generated cases in ~1.4 s there.

### Disposition

Per the task's publication guardrails this was **not** fixed in jolt-tcp or
jolt-net; the boundary is reported here instead. Reproducers are described above
and are cheap to re-derive.

In this repository the property's observer bound was raised from the shared 8 s
default to a documented 45 s (`backpressure-timeout-ms` in
`test/jolt/http/server_property_test.clj`), sized at roughly 5x the worst
latency observed across many runs. This satisfies the "do not merely increase
the timeout" condition: native evidence shows the operation makes bounded
progress and always delivers every byte, so the old 8 s bound was sampling the
tail of a lower-layer latency distribution rather than measuring liveness —
which is precisely why libhegel classified it *flaky* rather than producing a
reproducible counterexample. The bound remains a liveness bound: a case that
exhausts it has genuinely stopped, and that is still a failure. Widening the
fixture's buffers to dodge the latency was rejected, because that would delete
the backpressure coverage the property exists for.

The enclosing scenario watchdog for the loopback group was raised 600 s → 960 s
so the outer bound stays above the sum of the per-case bounds its slowest
property can spend; otherwise a diagnosable per-case failure would surface as an
opaque scenario timeout.

## Finding 2 — an over-limit rejection can be RST'd on Windows

Native Windows surfaced a second, unrelated flake in
`server/oversize` (seed `305835134111915440`, `{:pad-len 2048, :over? true}`).

When the server rejects an over-limit request it answers 414/431 and closes
while the rest of the oversized request is still in flight. TCP answers the
unread inbound data with a RST, and a RST discards whatever is still sitting
unread in the client's receive queue — including, sometimes, the error response
the server did send. This is a transport fact, not an HTTP conformance fact, and
it is timing-dependent: native Windows hits it intermittently, POSIX loopback
almost never does.

`jolt.http.server-test`'s `recv-until-eof` already encoded exactly this
reasoning for the acceptance scenario ("the response bytes received before that
terminal signal remain valid and must still be checked"). The *property* harness
did not, so the raised `:connection-reset` escaped `fail!` entirely and libhegel
saw a case that threw once and passed on replay — reported as flaky with no
counterexample and an empty out-of-band event log.

Two test-layer changes, both in `test/`:

- `send-through!` / `recv-chunk!` treat `:connection-reset` (jolt.net maps
  ECONNRESET, EPIPE and Windows' WSAECONNRESET 10054 onto that one kind) as a
  terminal signal rather than an error.
- `read-into!` now returns `:done` / `:peer-closed` / `:timeout` instead of a
  boolean. Those last two were previously conflated, which made a connection the
  server terminated in 79 ms indistinguishable from a server that never answered
  within 8 s. `check-drain!` fails on `:peer-closed` **by default**; only the
  oversize property opts into it, and only for `over?` cases. An under-limit
  request is answered on a connection the server keeps open, so it has no reset
  race and must always produce its 200.

No oracle was weakened: a response that does arrive must still be well-formed
and carry exactly the expected status.

## Finding 3 — the progress file was not Windows-path-safe

The suite aborted before its first scenario on native Windows:

```
Exception in open-output-file: failed for
D:\src\jolt-proposal-net-runtime-w3/C:\Users\...\Temp/jolt-http-test-progress.log...
: invalid argument
```

jolt's `java.io.File` does not recognise a drive-qualified Windows path as
absolute and joins with `/`, so `.getAbsolutePath` prepended the process working
directory — which the native gate deliberately sets to the runtime checkout.
`progress-file` now joins the directory itself when it is already absolute
(POSIX or drive-qualified), keeps the `java.io.File` path only for a genuinely
relative fallback, and accepts a `JOLT_HTTP_TEST_TMPDIR` override.

## Windows ARM64 remains a non-gating preview

No socket-runtime support is claimed on Windows ARM64, and nothing here weakens
that. jolt.net still lacks its reviewed ARM64 descriptor, so loading the HTTP
server graph would correctly fail before any socket call, and W6A recorded a
jolt-hegel installer/`Get-FileHash` blocker on that target.

The lane is `continue-on-error`, builds native `tarm64nt` Chez 10.4.1, and runs
one checked-in main (`jolt.http.windows-arm64-preview`, alias
`-M:windows-arm64-preview`) that covers both preview claims: the
descriptor-independent HTTP layers really run on native ARM64, **and**
`jolt.net.target/descriptor` still fails closed with `:unsupported-target`. It
declares no jolt-hegel dependency, so the installer blocker cannot silence the
fail-closed assertion. No packaged joltc, devboot, AOT cache, HTTP listener, or
Winsock runtime claim is involved.

That namespace was exercised on Linux to confirm it loads and that its portable
selection is non-vacuous (2 tests, 11 assertions) before correctly refusing the
non-ARM64 target. The ARM64 lane itself remains unobserved on this revision.

## Proof boundary

This work is dependency, CI and test-portability work. The existing HTTP
framing, completion, EOF, ownership and cancellation proofs in `docs/proofs/` do
**not** need re-derivation, because none of the semantics they model changed:
`src/` is byte-identical to the base commit. Every change is in `deps.edn`,
`.github/workflows/tests.yml`, `tools/`, `test/`, `README.md`, and this
document. The two behavioural findings above were both localized *outside*
jolt-http's production layer — one to jolt-tcp's reactor, one to TCP reset
semantics — and neither was worked around by altering HTTP production code.
