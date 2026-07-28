# Native Windows HTTP runtime evidence (W6B, W8)

This records what was actually observed while promoting jolt-http from
portable-only Windows coverage to real loopback HTTP runtime coverage over the
reviewed jolt-tcp public API — first on x86-64 (W6B), then on ARM64 (W8) — and
the platform findings that came out of it. It is evidence, not a support matrix:
every claim below names the pins it was taken against.

All Windows evidence here is **source-runtime** evidence: native Chez, source-mode
Jolt, `JOLT_AOT_CACHE=0`. No lane on this platform uses a packaged `joltc`, a
devboot, or an AOT cache, and none of the results below should be read as
evidence for those.

## Pins (final public v0.5.7 stack)

| Component | Pin |
| --- | --- |
| jolt-http evidence tip | `a920052d04d00f21ca19bf5023bddc57b6467a26` (`codex/windows-arm64-v0.5.7-integration`) |
| jolt-http runtime-stack pin | `25af61c541a6cd4eb765faacea7bbd89eb154113` |
| jolt-http base | `2f877462363e7979b67353d52694fba5e0b9c3fb` (`claude/http-backpressure-bound-restore`) |
| jolt-tcp | `911cf783d56e988adb2b8f716b6636fae5454e52` |
| jolt-net (transitive, via jolt-tcp) | `c3747385235df812e0d739a3e9f71c4dfb07b474` |
| Jolt core (every CI and runtime checkout) | `46e1f74fc14f29283586900ef4b98c45375c0500` |
| jolt-hegel (test-time only) | `c406e6a85e9902dd89a42a3abce3d6161e5cd406` (`chucklehead-dev/jolt-hegel`) |
| libhegel | 0.30.1 |
| Chez Scheme | 10.4.1 |

The Jolt core revision is held in a single `JOLT_CORE_SHA` workflow variable
rather than repeated per job, so a platform cannot silently validate against a
different core.

jolt-net is reached **only** transitively through jolt-tcp; jolt-http declares no
direct dependency on it. `c3747385` is the revision jolt-tcp `911cf78` actually
pins, and therefore the one every result below was produced against. It is not
the tip of jolt-net's reviewed branch.

The W6B-era observations were originally taken on a local toolchain. Everything
recorded here now comes from hosted CI, named by run and job ID at each claim.

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
| Windows **aarch64**, `-M:windows-runtime-test` (dependency-free) | 8 tests, 68 assertions, 0 failures, 0 errors — exit 0 |
| Windows **aarch64**, `-M:test` with `JOLT_HEGEL_REQUIRED=1` | 316 checks, 0 failures — exit 0 |
| Windows x86-64, `-M:windows-runtime-test` (dependency-free) | 8 tests, 68 assertions, 0 failures, 0 errors — exit 0 |
| Windows x86-64, `-M:test` with `JOLT_HEGEL_REQUIRED=1` | 316 checks, 0 failures — exit 0 |
| Linux x86-64, `-M:test` with `JOLT_HEGEL_REQUIRED=1` | 316 checks, 0 failures — exit 0 |
| Linux x86-64, `-M:test` with the flaky witness seed | 316 checks, 0 failures; 6/6 clean replays of the loopback group |

Both Windows architectures report the **same** numbers: 8 tests / 68 assertions
in the dependency-free gate, and 316 checks in the full suite, across an
identical set of 55 scenario groups. No runtime or socket group is skipped on
either target.

Exit codes are observed, not inferred. `tools/test-windows-source.ps1`
materializes the child process handle before reading `.ExitCode`, refuses to
report success when no exit code is available, and propagates a nonzero one — so
a green step means a real child exit code of 0 was read.

Hosted CI on `casselc/jolt-http` (workflow `tests`, run
[`30369250255`](https://github.com/casselc/jolt-http/actions/runs/30369250255),
revision `a920052`) is green across all six lanes — Linux x86-64, Linux aarch64,
macOS arm64, macOS x86-64, Windows x86-64 and Windows aarch64. **All six gate.**
That revision differs from the runtime-stack pin only in documentation and test
failure diagnostics; `src/` remains byte-identical.

Every POSIX lane and both Windows suites run with `JOLT_HEGEL_REQUIRED=1`.
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

**W6A did not remove it.** On that graph the seed still reproduced: 2 of 5
replays failed, at sizes 93388 and 120000. The measurements below are the
historical diagnosis that led to W6A.1; they are not a description of the final
public pins.

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

The owning layer was therefore **jolt-tcp's reactor re-arm path**: when a handler
arity completes in the window after the reactor has drained its pending set but
before it re-enters `net/await-ready`, the connection is only re-serviced on the
next tick of that 1000 ms wait. jolt-http's fixture merely amplifies it, because
a 1 KB read buffer over a 93 KB body is ~92 exposures per case.

**It is not observed on native Windows at all**, on either architecture — the
same property completes its 20 generated cases in ~1.4 s on x86-64 and in
1804 ms on aarch64 (run `30323400505`).

### Disposition — fixed at the owning layers

W6A.1 closed the notification window rather than masking it:

- jolt-net added a caller-supplied monotonic `poller/wake-cursor`; its final
  public pin `c3747385` contains the reviewed implementation from `64b15e0`;
- jolt-tcp samples that cursor before draining producer-owned pending work and
  passes it into `await-ready`; its final public pin `911cf78` contains the
  consumer change from `8a898b3`; and
- the deterministic forced-race controls and six corrected/buggy/nonvacuity SMT
  models are recorded in jolt-net's `socket-invariants.md` and jolt-tcp's
  `reactor-lifecycle-invariants.md`.

The earlier HTTP branch had temporarily widened the per-case observer from 8 s
to 45 s and suppressed libhegel's 30 s aggregate `TooSlow` check for this one
property. The latter was the binding workaround on slow macOS x86-64 runners:
the property body, not input generation, could take longer than the health
check. Both workarounds were removed at HTTP commit `8768612` after the
lower-layer fix. The observer is again 8 s and `TooSlow` is enabled.

The original witness then completed its 20 cases in 2584/2620/2768 ms, with
every byte conserved. The final six-platform run `30369250255` executes the
same property at those defaults and is green. No HTTP production code changed;
the diagnosis, repair, and proofs remain at the readiness/reactor boundary.

## Finding 2 — an over-limit rejection can be RST'd before its response is observed

Native Windows surfaced a second, unrelated flake in
`server/oversize` (seed `305835134111915440`, `{:pad-len 2048, :over? true}`).

When the server rejects an over-limit request it answers 414/431 and closes
while the rest of the oversized request is still in flight. TCP answers the
unread inbound data with a RST, and a RST discards whatever is still sitting
unread in the client's receive queue — including, sometimes, the error response
the server did send. This is a transport fact, not an HTTP conformance fact, and
it is timing-dependent. Native Windows exposed it first; hosted macOS x86-64
later produced one nondeterministic outcome at the same property boundary.

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

A later documentation-tip run
[`30367230561`](https://github.com/casselc/jolt-http/actions/runs/30367230561)
failed this property once on macOS x86-64 with seed `305835134111915440`.
The fixture's explicit failure log was empty. The reporter also failed to print
jolt-hegel's bounded `:observed-failures`, so that historical run cannot tell us
whether the escaped throwable was a direct reset, a wrapped terminal error, or
cleanup after the peer had already retired. Guessing another accepted error
kind from that evidence would weaken the oracle without establishing the
premise.

Commit `a920052` therefore changes diagnostics only: flaky results now retain
the structured throwable summaries jolt-hegel already captured. The exact seed
then passed 200 consecutive Linux executions, the complete six-lane run
`30369250255`, and two additional targeted macOS x86-64 reruns (attempts 2 and
3 of that run). No timeout, retry, health check, status oracle, or transport
classification changed. The residual timing risk remains recorded, and the
next occurrence will carry the evidence needed for a narrow fix.

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

The response at the time was to replace every `actions/cache` use with
`actions/cache/restore` plus an explicit `actions/cache/save` placed **after the
build was asserted good and before any test step**, so a red or cancelled run
could no longer cost the next one a rebuild, and to add a
`tests-${{ github.ref }}` concurrency group with `cancel-in-progress`.

Observed then: Windows ARM64 7m19s → 1m21s, Windows x86-64 9m12s → 2m32s, macOS
x86-64 13m03s → ~4m; full-matrix wall clock ~13 min → ~3.5 min.

**W12 removed the Chez source builds entirely, so this trap no longer has a Chez
instance to reintroduce.** The concurrency group remains, and the observation
above is retained because the same failure mode still applies to any *future*
post-job cache: the macOS x86-64 libhegel build is the one remaining hand-rolled
`restore`/`save` pair in this workflow, and it is deliberately still shaped that
way for exactly this reason. See the W12 section below.

## Windows ARM64 — promoted to a gating socket runtime (W8)

Windows ARM64 previously ran a non-gating preview: `continue-on-error: true`,
two descriptor-independent tests, no socket, and an assertion that
`jolt.net.target/descriptor` still failed closed with `:unsupported-target`.
**That boundary is gone.** The pinned jolt-tcp revision reaches a jolt.net
carrying reviewed Winsock readiness for aarch64 as well as x86-64, so ARM64 now
runs the same HTTP contracts and the same Hegel-required suite as x86-64, and
gates on both.

Observed evidence:

| item | value |
| --- | --- |
| run / job | [`30369250255`](https://github.com/casselc/jolt-http/actions/runs/30369250255) / `90308446714` |
| revision | `a920052d04d00f21ca19bf5023bddc57b6467a26` |
| runner | `windows-11-vs2026-arm`; `runner.arch = ARM64`, OS architecture `ARM 64-bit Processor` |
| Chez | native 10.4.1, `(machine-type)` asserted `tarm64nt` |
| target assertion | `JOLT_EXPECTED_ARCH=aarch64`, gate asserts `[:windows :aarch64 64]` |
| dependency-free gate | `-M:windows-runtime-test` — 8 tests, 68 assertions passed, 0 failures, 0 errors, exit 0 |
| complete suite | `-M:test` with `JOLT_HEGEL_REQUIRED=1` — 316 checks, 0 failures, exit 0 |
| libhegel | `libhegel-windows-arm64.dll` 0.30.1, downloaded and sha256-verified on the runner |
| wall clock | 8m31s including a from-source `tarm64nt` build |

Every generative group ran non-vacuously on ARM64:

| group | result |
| --- | --- |
| pure properties | `Ran 24 tests. 32 assertions passed, 0 failures, 0 errors.` |
| protocol properties | `Ran 19 tests. 25 assertions passed, 0 failures, 0 errors.` (19 named properties, each `PROPERTY-BEGIN`/`PROPERTY-END`) |
| loopback properties | all 8 real-TCP properties, 30/25/20/30/30/20/25/25 cases |

### What retired the two blockers

Both reasons the preview existed were retired by **lower-layer** evidence
before this lane was promoted, not by relaxing anything here:

- **No reviewed ARM64 descriptor.** jolt-net `c3747385` ships reviewed Winsock
  readiness for x86-64 and aarch64 alike. jolt-tcp `911cf78` pins it and is green
  on real ARM64 loopback in run
  [`30322363564`](https://github.com/casselc/jolt-tcp/actions/runs/30322363564).
- **The `Get-FileHash` installer blocker.** W6A recorded a jolt-hegel installer
  failure on Windows ARM64, and the preview sidestepped it by declaring no
  jolt-hegel dependency. It no longer reproduces: the same jolt-tcp run installs
  libhegel and runs its complete suite on ARM64, and this lane now does the same,
  fetching and verifying `libhegel-windows-arm64.dll` directly.

### What is asserted rather than assumed

An ARM64 runner label alone would not prove a native ARM64 run, so three
independent checks stand between the label and a green result:

- `runner.arch` must equal `ARM64`;
- Chez `(machine-type)` must equal `tarm64nt`, read with `--script` rather than
  `--eval` so a failing probe cannot exit zero;
- the gate itself asserts the exact `[:windows :aarch64 64]` target from
  `JOLT_EXPECTED_ARCH`, failing closed on a missing or unknown value.

The architecture is declared by the runner and never inferred from the running
process, so an emulated x86-64 Jolt fails before the positive readiness-poller
assertion or any HTTP test can pass.

### Remaining boundaries

- **Source-runtime only.** No packaged `joltc`, no devboot, no AOT cache —
  `JOLT_AOT_CACHE=0`. Nothing here is evidence for a packaged or AOT ARM64 build.
- The claim covers `windows-11-vs2026-arm` with official Chez 10.4.1 at the pins
  named above. W8 built that Chez from source on the runner; since W12 the same
  official 10.4.1 arrives as the digest-pinned `tarm64nt` toolchain archive. The
  ARM64 evidence is unchanged in kind — the `(machine-type) == tarm64nt` witness
  is applied to whichever `scheme.exe` the lane is about to run.

## Proof boundary

This work is dependency, CI and test-portability work. The existing HTTP
framing, completion, EOF, ownership and cancellation proofs in `docs/proofs/` do
**not** need re-derivation, because none of the semantics they model changed:
`src/` is byte-identical to the base commit. Every change is in `deps.edn`,
`.github/workflows/tests.yml`, `tools/`, `test/`, `README.md`, and this
document. The two behavioural findings above were both localized *outside*
jolt-http's production layer — one to jolt-tcp's reactor, one to TCP reset
semantics — and neither was worked around by altering HTTP production code.

The ARM64 promotion (W8) is the same kind of work and carries the same
disposition. `src/` is byte-identical to base `2f877462363e7979b67353d52694fba5e0b9c3fb`
(`git diff --stat 2f87746 -- src/` is empty), so no ARM-specific proof model was
invented and none was needed. The existing HTTP, TCP and net invariants apply on
aarch64 for a structural reason rather than by re-derivation:

- jolt-http reaches the network **only** through jolt-tcp's reactor and
  `teensyp.client`. That interface contract names no architecture, and nothing
  under `src/` imports `jolt.net` or `jolt.ffi`.
- The readiness contract those proofs depend on is supplied by a single reviewed
  jolt-net revision (`c3747385`) that implements Winsock readiness for x86-64 and
  aarch64 alike. Both Windows lanes therefore exercise the *same* contract
  object, not two independently derived ones.
- Native conformance is asserted rather than assumed at three levels
  (`runner.arch`, Chez `machine-type`, and the in-test `[:windows :aarch64 64]`
  target check), so the evidence cannot be satisfied by an emulated process.
- The observable behaviour is identical: the same 55 scenario groups, the same
  8 tests / 68 assertions, and the same 316 checks on both architectures.

Had any HTTP semantics differed on ARM64, the obligation would have been to stop
and identify the exact proof affected rather than to widen a platform claim. No
such difference was observed, and no HTTP production change was made.

## W12 — CI moved to the shared immutable Chez toolchain

Every lane built Chez Scheme from source. The POSIX matrix cloned
`cisco/ChezScheme` and ran configure/make/make install; Windows x86-64 did the
same under an MSYS2 MINGW64 toolchain; Windows ARM64 ran `build.bat tarm64nt`
over a full ChezScheme checkout. Each build was guarded by a hand-rolled
`actions/cache` entry keyed by a string **nothing verified**, so a restored
toolchain was trusted purely because its key matched.

All six lanes now install the same immutable, checksum-pinned release.

| item | value |
| --- | --- |
| branch | `claude/http-shared-toolchain` |
| base | `e27ecc1a7e41607fe3fbf86b3d9b7e101004f219` (`codex/windows-arm64-v0.5.7-integration`) |
| tested workflow revision | `582b6a7aa80abba6936c3c3c79a13d39da1b4108` |
| action | `casselc/jolt-toolchains/setup-chez@095108ae32659757808064d004855092567d3ad3` |
| release | `chez-ci-10.4.1.1` |
| capability requested | `source-runtime` |

`source-runtime` is the honest boundary: jolt-http runs Clojure-on-Chez through
the source Jolt launcher with `JOLT_AOT_CACHE=0` and links nothing against the
Chez kernel. The GNU kernel-development inputs the old MSYS2 lane staged
(`scheme.h`, `libkernel.a`, `JOLT_CHEZ_CSV`, a `cc` shim) are **not** requested.

The action verifies the archive digest, its descriptor, its internal manifest,
the extracted inventory, and the requested capability before exporting an
executable. There is deliberately **no source-build fallback**: a digest mismatch
fails the job, because a fallback would convert a supply-chain failure into a
slow green run.

### Per-target archive digests, as observed on the runners

Each lane pins its own SHA-256 next to its target, so a wrong target/digest
pairing cannot pass. Every value below was echoed by the lane itself from
`steps.chez.outputs.archive-sha256`, and matched on both runs:

| target | archive SHA-256 |
| --- | --- |
| `linux-x86_64` | `16476cd98fb5cb2e2c0285e88fcd6d57ade9392ca8d7cf603ca38432b4118526` |
| `linux-aarch64` | `b5b2306d3d6468b5fc7d5836721b09c704c7750f887a6232f1aeeb567d55f5d9` |
| `macos-arm64` | `d5b5a504eed1e0f117b4a3dd23ad0030bb3e5c0da6d6b9d9f990f5f2fa32478f` |
| `macos-x86_64` | `e4577ad71b1f1e1062c361fa612af60d75378e64dc2869d8c8eefd3d7efdbc62` |
| `windows-x86_64` | `360c60496eea2f8aab0e557eb77e9e18b315bb9181938158ae57655aa541b7f8` |
| `windows-arm64` | `9bc28462823a1447de6d849e129758a6317cc9deafb8e87414817e7244f149c8` |

### Cold run — [`30407168310`](https://github.com/casselc/jolt-http/actions/runs/30407168310)

No `jolt-chez-archive-*` cache key existed anywhere in the repository before this
run, so the miss is real rather than arranged.

| lane | job | cache | result | wall clock |
| --- | --- | --- | --- | --- |
| Linux x86_64 | `90435084736` | miss | 316 checks, 0 failures | 1m22s |
| Linux aarch64 | `90435084620` | miss | 316 checks, 0 failures | 1m22s |
| macOS arm64 | `90435084611` | miss | 316 checks, 0 failures | 1m15s |
| macOS x86_64 | `90435084605` | miss (+ libhegel miss) | 316 checks, 0 failures | 5m42s |
| Windows x86_64 | `90435084581` | miss | 8 tests / 68 assertions, then 316 checks, 0 failures | 1m57s |
| Windows aarch64 | `90435084584` | miss | 8 tests / 68 assertions, then 316 checks, 0 failures | 4m22s |

All six logged `Cache not found for input keys: jolt-chez-archive-chez-ci-10.4.1.1-<target>-<digest-prefix>`,
downloaded the release asset, and reported
`installed chez-ci-10.4.1.1/<target> … archive sha256=<expected>`. Run wall clock
23:11:12Z → 23:22:01Z (10m49s), of which ~5 min was runner queueing; longest job
5m42s.

### Warm run — [`30407836912`](https://github.com/casselc/jolt-http/actions/runs/30407836912)

Same revision `582b6a7`, dispatched with no source change.

| lane | job | cache | result | wall clock |
| --- | --- | --- | --- | --- |
| Linux x86_64 | `90437183219` | **hit** | 316 checks, 0 failures | 1m19s |
| Linux aarch64 | `90437183395` | **hit** | 316 checks, 0 failures | 1m17s |
| macOS arm64 | `90437183240` | **hit** | 316 checks, 0 failures | 1m20s |
| macOS x86_64 | `90437183237` | **hit** (+ libhegel hit) | 316 checks, 0 failures | 3m03s |
| Windows x86_64 | `90437183202` | **hit** | 8 tests / 68 assertions, then 316 checks, 0 failures | 2m17s |
| Windows aarch64 | `90437183143` | **hit** | 8 tests / 68 assertions, then 316 checks, 0 failures | 3m26s |

All six logged `Cache restored from key: …` and `cache hit = true`, with **zero
misses and zero archive downloads**, and reported the identical digests. Run wall
clock 23:23:40Z → 23:27:12Z (3m32s); longest job 3m26s.

Every generative group ran non-vacuously on every lane in both runs: pure
properties `Ran 24 tests. 32 assertions passed, 0 failures, 0 errors.`, protocol
properties `Ran 19 tests. 25 assertions passed, 0 failures, 0 errors.`, and the
complete `-M:test` suite `316 checks, 0 failures` under `JOLT_HEGEL_REQUIRED=1`.
The counts are identical cold and warm, and identical to the pre-migration
counts recorded earlier in this document.

### What is asserted, not assumed

- **No source-build fallback ran.** Across all twelve job logs, the strings
  `cisco/ChezScheme`, `build.bat`, `setup-msys2`, `configure --threads` and
  `make -j` occur **zero** times. No `msys2` step, MinGW package install, or
  ChezScheme checkout remains in the workflow.
- **Windows ARM64 keeps its architecture witnesses.** `runner.arch == ARM64`,
  Chez `--version` containing `10.4.1`, and `(machine-type)` read through a
  `--script` probe, all applied to the *shipped* `scheme.exe`. Both runs logged
  `chez machine-type: tarm64nt`. An x86-64 image published under the ARM64 target
  name would fail here rather than run under emulation. The in-test
  `JOLT_EXPECTED_ARCH=aarch64` → `[:windows :aarch64 64]` check is unchanged. The
  descriptor is an additional check, never a substitute for executing the binary
  on real ARM64 hardware.
- **The macOS x86-64 libhegel path is intact.** The pinned `hegel-rust`
  `f81c6cce` source checkout, the Rust 1.86.0 `cargo build --locked -p hegeltest-c
  --release`, and its `restore`/`save` cache keyed
  `libhegel-macOS-X64-0.30.1-f81c6cce-rust1.86.0-v1` were untouched. That is a
  libhegel concern, not a Chez one. The cold run shows
  `Cache not found for input keys: libhegel-…` and built it; the warm run shows
  `Cache restored from key: libhegel-…`.
- **No Node.js 20 deprecation warnings** in any of the twelve logs. Every
  third-party action is pinned by full commit SHA at a node24 major:
  `actions/checkout@fbc6f39` (v5.1.0) and `actions/cache/{restore,save}@caa2961`
  (v5.1.0). The action's own internal cache use is already node24.
- **`permissions: contents: read`** was added at workflow level.
- Nothing was added to manufacture green: no sleeps, no retries, no widened
  timeouts, no skipped groups, no `continue-on-error`. The 45- and 60-minute job
  timeouts and the 900/2400-second script timeouts are unchanged.

### Diff boundary

- `git diff e27ecc1..HEAD -- src test` is **empty**. No production and no test
  semantic change.
- `git diff --check` is clean.
- Jolt core, jolt-net, jolt-tcp and jolt-hegel pins are **unchanged**; `deps.edn`
  is untouched. Repinning to the v0.5.10 core is intentionally a follow-on after
  the core rebase, not part of W12.

### Deviations and remaining boundaries

- The macOS arm64 lane keeps its existing `macos-14` runner. The toolchain
  descriptor names `macos-15` as that target's build runner; the archive was
  observed installing, verifying, and running the full suite on `macos-14` in
  both runs, so the lane was left as it was rather than changed to match the
  descriptor.
- `zstd` is the one genuine dependency addition: the POSIX archives are
  `.tar.zst`. It is installed only when the image does not already ship it. The
  former `build-essential` / `liblz4-dev` / `zlib1g-dev` / `libncurses-dev` /
  `uuid-dev` / `libssl-dev` and `brew lz4 openssl@3` sets existed solely to
  compile Chez and are gone.
- Windows x86-64 lost its job-level `shell: msys2 {0}` default and is native
  PowerShell throughout. The only shell mechanism retained is the POSIX `sh` the
  source Jolt launcher reads through `JOLT_SH` while deriving its git cache key;
  both Windows lanes now resolve Git for Windows' `sh.exe` explicitly and pass it
  as `-ShellExe`, rather than relying on a PATH that no longer carries MSYS2.
- Still source-runtime evidence only, on both Windows architectures: no packaged
  `joltc`, no devboot, no AOT cache.

## Follow-on W9 — downstream Ring adapter validation

The next non-overlapping consumer slice is `ring-chez-adapter`. Its local
`codex/jolt-http-validation` branch at
`eebfefa` is already a thin compatibility wrapper around `jolt-http`; W9 should
validate that boundary rather than move HTTP or socket semantics back into the
adapter.

W9 should:

1. work in a new worktree from exact base `eebfefa`, leaving the existing
   adapter checkout and the dirty `http-client` checkout untouched;
2. pin jolt-http `a920052d04d00f21ca19bf5023bddc57b6467a26` and Jolt core
   `46e1f74fc14f29283586900ef4b98c45375c0500`;
3. add a dependency-free real-loopback gate driven by the public
   `teensyp.client` test surface, with port zero, the actual bound port, a
   Ring request map, 200 and 404 responses, descriptor opacity, deterministic
   stop, and idempotent repeated stop;
4. remove fixed startup sleeps from the acceptance path: `run-server` must
   return only after the listener is usable, so a retry delay would hide a
   lifecycle defect;
5. characterize the existing `jolt-lang/http-client` compatibility gate
   separately. If its published pin blocks the final stack, report the exact
   missing capability and stop rather than modifying the dirty local checkout;
6. run source-mode Linux and native Windows x86-64 checks with real child exit
   codes and fail-closed target assertions, then prepare—but do not claim—a
   six-platform CI matrix unless a writable public fork is explicitly selected;
7. keep adapter production changes absent or minimal. Any discovered framing,
   lifecycle, readiness, or descriptor problem belongs at jolt-http, jolt-tcp,
   or jolt-net respectively and must be reported before another repository is
   changed.

`ring-chez-adapter` currently points at the upstream-owned `jolt-lang` origin.
W9 therefore must not push, open a PR, or create a fork. Its deliverable is a
clean local branch, exact commands and counts, the proposed CI patch, and a
statement of which platform claims were actually observed.
