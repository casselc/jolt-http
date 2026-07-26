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

The full suite runs the same 55 scenarios on Windows x86-64 as on Linux, so no
runtime or socket group is skipped on that target.

Hosted CI on this branch (`casselc/jolt-http`, workflow `tests`) has been
observed green across all six lanes — Linux x86-64, Linux aarch64, macOS arm64,
macOS x86-64, Windows x86-64 and the non-gating Windows ARM64 preview.
Windows ARM64 is a preview lane only and claims no socket-runtime support.

Every POSIX lane and the Windows x86-64 suite run with `JOLT_HEGEL_REQUIRED=1`.
That flag is not what makes a missing libhegel fail — `hegel.ffi` loads the
native library eagerly, so an absent library aborts at namespace load with
`:hegel.ffi/library-load-failed` and a non-zero exit, which was verified
directly. What the flag adds is refusal to report success for a run that loaded
libhegel and then executed no generative cases, whether because a group ran
empty or because a generative scenario was dropped entirely. Both directions
were verified by forcing them; see `hegel-support/assert-generative-coverage!`.

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

### The per-case bound was not the binding constraint

Sizing that bound was necessary but **not sufficient**, and treating it as the
whole fix was wrong. The macOS x86-64 lane — the slowest hosted runner, and one
this branch had not measured when the 45 s bound was chosen — then failed
intermittently with an opaque `engine/setup error`.

Two things had to be fixed to see why:

1. `hegel.report/run!` publishes a thrown setup, health-check or engine error
   under `:exception`, but this suite's reporter destructured `:error` — a key
   never present on that event. Every engine error had therefore always printed
   a bare `nil` and discarded the exception.

2. With that corrected, the cause is exact:

   ```
   FailedHealthCheck: TooSlow — input generation is slow: only 8 valid inputs
   after 38.082074097s (threshold 30s).
   ```

libhegel applies a **30 s aggregate budget to the whole property**. A 45 s
per-case bound can never be reached, because the engine aborts the property
first. Observed durations on macOS x86-64 make the race plain:

| duration | outcome |
| --- | --- |
| 15.2 s, 16.3 s, 26.4 s, 26.5 s, 27.4 s, 31.8 s | pass |
| 32.4 s | TooSlow |

Same jolt-tcp re-arm latency, just on slower hardware. `:too-slow` is therefore
suppressed for this one property, which is what the check itself recommends for
an expected cost. It is sound here specifically: the generators are a bounded
integer and a keyword, so input *generation* is trivial; the elapsed time is the
property body pushing up to 120 KB through a deliberately undersized buffer over
real loopback TCP, which is the coverage. Every other property keeps the check,
so a genuine generation slowdown elsewhere still fails.

Verified by forcing the failure rather than waiting for it: with a 2.5 s
artificial delay per case the property reliably tripped TooSlow at ~38 s; with
the suppression it runs 68.4 s and passes, every case still checked.

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

## Finding 3 — two POSIX-only temp paths

Both are test-layer portability defects, and the second is a reminder that a
local Windows box is not the same target as a hosted Windows runner.

**The progress file.** The suite aborted before its first scenario on native
Windows:

```
Exception in open-output-file: failed for
D:\src\jolt-proposal-net-runtime-w3/C:\Users\...\Temp/jolt-http-test-progress.log...
: invalid argument
```

jolt's `java.io.File` does not recognise a drive-qualified Windows path as
absolute and joins with `/`, so `.getAbsolutePath` prepended the process working
directory — which the native gate deliberately sets to the runtime checkout.

**The file-writer property.** A hard-coded `"/tmp/jolt-http-prop-*.bin"` passed
on the local Windows host, where a `\tmp` happens to exist on the current drive,
and failed only on the hosted runner:

```
failed for /tmp/jolt-http-prop-0-1.bin: no such file or directory
```

so `body/file-writer` reported a missing file rather than testing the writer.

Both now go through one helper, `hegel-support/temp-path`, rather than
open-coding the rule twice: use the platform temp directory, join it ourselves
when it is already absolute, keep the `java.io.File` path only for a genuinely
relative fallback, and honour a `JOLT_HTTP_TEST_TMPDIR` override. The override
was confirmed to be on the path by pointing it at a nonexistent directory and
observing the suite fail there.

## CI toolchain caching

Not a correctness finding, but it dominated iteration time and is recorded so
the trap is not reintroduced.

`actions/cache` writes its cache in a post-job step, and that step is **skipped
when the job fails**. The Windows x86-64 lane was red while the defects above
were being fixed, so it could never populate its own cache: it rebuilt Chez from
source (6m46s) on every attempt in order to run a ~90 s suite. The Windows ARM64
preview had no cache at all (~7 min of `build.bat` per run), and with no
`concurrency` group, stacked pushes produced concurrent runs that each missed
the cache the others were about to write — macOS x86-64 rebuilt Chez for 9m28s
in one run despite the preceding run having just cached it.

Every toolchain cache is now `actions/cache/restore` plus an explicit
`actions/cache/save` placed **after the build is asserted good and before any
test step**, so a red or cancelled run can no longer cost the next one a
rebuild, and what is saved has always passed its version assertion. The ARM64
lane caches its `tarm64nt` tree, the macOS x86-64 libhegel build is cached on
the pinned `hegel-rust` commit and toolchain, and a
`tests-${{ github.ref }}` concurrency group with `cancel-in-progress`
supersedes in-flight runs.

Observed: Windows ARM64 7m19s → 1m21s, Windows x86-64 9m12s → 2m32s, macOS
x86-64 13m03s → ~4m; full-matrix wall clock ~13 min → ~3.5 min.

## Windows ARM64 — preview observed, boundary unchanged

The preview lane has now **passed on native Windows ARM64 hardware**, which it
had not when this document was first written.

Observed evidence:

| item | value |
| --- | --- |
| run / job | `30216650356` / `89831928101` |
| runner | `windows-11-vs2026-arm` |
| Chez | native `tarm64nt` 10.4.1, restored from `chez-Windows-ARM64-v10.4.1-tarm64nt-v1` |
| entry point | `-M:windows-arm64-preview` (`jolt.http.windows-arm64-preview`) |
| result | `Ran 2 tests. 11 assertions passed, 0 failures, 0 errors.` |
| fail-closed assertion | `PASS portable HTTP logic with network dependency fail-closed` |

The `Get-FileHash` caveat is also obsolete here and has been removed rather than
restated: W6A hit a jolt-hegel installer blocker on this target, but this lane
declares no jolt-hegel dependency and never invokes the installer — the CI step
does not pass `-InstallHegel` — so that blocker is not on its path at all.

**The boundary is unchanged, and passing does not widen it.** What the lane
proves is exactly two things: that the descriptor-independent HTTP layers really
execute under native source-mode Jolt on ARM64, and that
`jolt.net.target/descriptor` still fails closed with `:unsupported-target`. In
particular this lane is and remains:

- **non-gating** — `continue-on-error: true`;
- **no socket runtime** — jolt.net still lacks a reviewed ARM64 descriptor, so
  loading the HTTP/TCP server graph would correctly fail before any socket call.
  No listener is started and no HTTP-over-Winsock support is claimed;
- **no jolt-hegel dependency** — the alias declares no `:extra-deps`, so an
  installer failure can never silence the fail-closed assertion;
- **no packaged joltc, no devboot, no AOT cache claim** — it is source-mode Jolt
  over a natively built `tarm64nt` Chez, with `JOLT_AOT_CACHE=0`.

The same namespace was also exercised on Linux, where it runs the portable
selection non-vacuously and then correctly refuses the non-ARM64 target.

## Proof boundary

This work is dependency, CI and test-portability work. The existing HTTP
framing, completion, EOF, ownership and cancellation proofs in `docs/proofs/` do
**not** need re-derivation, because none of the semantics they model changed:
`src/` is byte-identical to the base commit. Every change is in `deps.edn`,
`.github/workflows/tests.yml`, `tools/`, `test/`, `README.md`, and this
document. The two behavioural findings above were both localized *outside*
jolt-http's production layer — one to jolt-tcp's reactor, one to TCP reset
semantics — and neither was worked around by altering HTTP production code.
