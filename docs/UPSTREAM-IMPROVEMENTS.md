# Upstream improvements for jolt-http

This document records changes to Jolt and jolt-tcp that would make jolt-http
safer, smaller, or easier to scale. It is a local planning document, not an
upstream issue tracker.

## Verification baseline

Revalidated 2026-07-28 against hosted CI run
[`30369250255`](https://github.com/casselc/jolt-http/actions/runs/30369250255)
(jolt-http `a920052`), green across all six lanes:

- Jolt core `46e1f74fc14f29283586900ef4b98c45375c0500`, source-mode over Chez
  10.4.1 built from source on every lane — no packaged `joltc`, no AOT cache;
- jolt-tcp `911cf783d56e988adb2b8f716b6636fae5454e52`, transitively jolt-net
  `c3747385235df812e0d739a3e9f71c4dfb07b474`; and
- jolt-hegel `c406e6a85e9902dd89a42a3abce3d6161e5cd406` with libhegel 0.30.1.

The earlier baseline (2026-07-23, installed `joltc v0.4.15` and Jolt
`d5aaf503fc7a45c5638d21215eb153b426a7e8dc`) predates the v0.5.7 rebase and no
longer describes any lane.

jolt-http has no direct FFI. It layers an HTTP/1.1 parser and Ring-shaped handler
contract over jolt-tcp's `jolt.net`-backed readiness reactor, so native safety and byte-transfer
changes are inherited. See
[jolt-tcp's detailed upstream document](../../jolt-tcp/docs/UPSTREAM-IMPROVEMENTS.md).

The reviewed Jolt proposal fork is published only to `casselc/jolt`, now
rebased over upstream v0.5.10 on `codex/upstream-rebase-v0.5.10`; nothing has
been pushed to the upstream project's origin and no pull request has been
opened. The current reviewed core baseline is
`b921991e532ce2555d947bf88bc0464bf0c89d27`. Its HTTP prerequisites include
scoped array ranges at reminted commit `8bc595eb`, Windows path handling, the
variadic FFI boundary at reminted commit `ec46ddcb`, and serialized
transactional Git dependency resolution. The per-namespace runtime AOT design
remains excluded from the active loader because it cannot replay downstream
top-level effects.

The current dependency pin is jolt-tcp
`e67fd1eb331e6c9736140f2ce4cfeba9ec0d8787`, transitively using jolt-net
`3b83e53f275f5087f9948b9fef445546fe773eb5`. The pin retains the W6A.1 wake
cursor repair, adds reviewed Winsock readiness for aarch64 alongside x86-64, and
aligns every runtime checkout with the same rebased core — which is
held in a single `JOLT_CORE_SHA` workflow variable rather than repeated per job.

## Implementation update — 2026-07-24

- Production namespaces depend only on jolt-tcp. They import neither
  `jolt.net` nor `jolt.ffi`; tests use `jolt.net` only for deterministic native
  failure injection.
- `deps.edn` pins jolt-tcp at
  `e67fd1eb331e6c9736140f2ce4cfeba9ec0d8787`, which transitively pins the
  validated jolt-net revision `3b83e53f275f5087f9948b9fef445546fe773eb5`. CI
  resolves this immutable graph directly rather than cloning mutable sibling
  branches. (The 2026-07-24 entry below was written against jolt-tcp
  `0c3e085f`; the W6B repin to the reviewed W6A runtime is what promoted
  Windows x86-64 from portable-only to real socket coverage.)
- Response writes use TCP's outcome-bearing completion API, so reset/failure
  paths unblock blocking producers and preserve the first native failure as a
  direct exception or closed-socket cause.
- Request EOF is terminal in every parser state; aggregate header count/bytes
  and signed-long Content-Length bounds are explicit. Response status and
  framing metadata are validated and canonicalized before serialization, and
  synchronous streamable bodies finalize their sink exactly once observably.
- Fixed, Hegel, SMT, and Prolog controls cover those boundaries. Remaining
  recommendations below are broader runtime/SPI work, not descriptions of
  workarounds still present in this adapter.

## Priority summary

| Priority | Upstream area | Independently landable change | Main payoff |
| --- | --- | --- | --- |
| P0 | Jolt build | Closed-world, fresh-process AOT; selective runtime reuse remains research-only | Prevent HTTP artifacts from mixing mutable compiler states |
| P1 | Jolt runtime | Reproduce and harden concurrent foreign calls | Remove an uncharacterized process-corruption risk |
| P1 | Jolt concurrency | Make JVM-named executor shims semantically honest | Stop presenting fixed-32 pools as cached, virtual, or work-stealing |
| P1 | Jolt host shims | Make type tests and protocol dispatch agree | Remove silent `java.io.File` fallthrough |
| P1 | Jolt threads | Daemon threads or daemon core.async infrastructure | Let a server process exit normally |
| P1 | Jolt/jolt-tcp bytes | Bulk array copy and range-aware native transfer | Remove inherited transport copies and body-range loops |
| P1 | jolt-tcp completion | Outcome-bearing write completion | Prevent a reset peer from stranding a blocking body producer |
| P2 | Jolt runtime | Genuine M:N lightweight tasks | Scale blocking handlers without one OS thread each |
| P2 | Jolt stdlib | Shared byte input/output protocols | Replace local request-body and response-sink shapes |
| P2 | Jolt stdlib | Cross-platform `jolt.net` with peer address | Report real clients and inherit audited socket behavior |
| P2 | `jolt.host` | Complete target descriptor and real CPU count | Make executor/platform defaults honest |
| P3 | Jolt bytes | Contiguous byte storage, richer buffers, charset registry | Consolidate the remaining HTTP byte plumbing |

P0 is a demonstrated wrong-code class. P1 covers runtime correctness, semantic
fidelity, and process lifecycle. P2 adds reusable runtime/stdlib capability. P3
should follow the smaller bulk-byte substrate.

## 1. Replace selective runtime AOT reuse with a closed-world build

### Current constraint

Jolt's current cache key covers a namespace's own source length and Chez
`equal-hash`, plus the Jolt version. jolt-hegel demonstrated a real same-length
false hit. A separate v0.4.15 probe showed that even replacing `equal-hash`
with a digest of only the consumer would remain wrong:

1. an unchanged consumer compiled a macro expansion producing `:v1`;
2. only the macro namespace changed to expand to `:v2`; and
3. the next run hit the consumer artifact, rebuilt the macro namespace, and
   still returned stale `:v1`.

HTTP code is macro- and protocol-heavy, and it is compiled on top of a changing
transport dependency. It is a direct beneficiary of correctness here, not just
an inherited Hegel concern.

The deeper review found that a dependency manifest is not a complete boundary.
Live fixtures exposed compile-time reads of ordinary nonmacro Vars without
`require` edges, global type-registry changes, and pre-`ns` caller context.
Other failures crossed readers/compiler callbacks, aliases, retained namespace
cells, direct/nested loading, and selection-time mutation.

The checked-in 44-model Chiasmus/Z3 suite proves bounded gates only under
complete, synchronous, non-spoofable observation of every consumed compiler
input. It does not prove instrumentation completeness. See the cross-project
[`AOT proof record`](../../jolt-upstream/docs/aot-cache-provenance-invariants.md).

### Upstream change

Make production AOT a closed-world build:

- start a fresh compiler process and namespace image;
- resolve and digest the exact project graph;
- require each file's first meaningful form to declare the requested namespace;
- freeze readers, compiler/features, target, Jolt version, and declared native
  inputs;
- compile one snapshot into one immutable executable/image; and
- reject or fall back for dynamic compiler effects outside the graph.

Do not reuse the image against independently mutated live compiler/runtime
state. Whole-image content addressing is reasonable; selective in-process
namespace reuse remains research-only. Use a reusable native streaming SHA-256
primitive for whole-build identity.

### Acceptance criteria

- Same-length source changes produce different build identities and results.
- Macro, compile-time nonmacro Var, reader, alias, registry, compiler, or target
  changes rebuild the snapshot or fail closed before user forms execute.
- Current build source paths appear in metadata and diagnostics.
- Dynamic load/reload outside the declared graph is rejected or takes the
  ordinary non-AOT path.
- Concurrent publication cannot mix namespace generations.
- An identical clean build deterministically reuses or reproduces one immutable
  image.

### jolt-http payoff

Resolved source and executable HTTP behavior cannot silently diverge. Cache
deletion stops being part of routine correctness guidance for the validated
closed-world build. The current runtime cache warning must remain for selective
namespace loading.

## 2. Fix concurrent-FFI safety independently of lightweight scheduling

### Current constraint

A prior high-concurrency streaming workload ended with Chez's
`nonrecoverable invalid memory reference` while threads were doing native socket
work. That observation is important, but it does not yet isolate a foreign-call
runtime bug from a specific surrounding workload.

The previous local explanation also misstated current executor behavior. On
both installed v0.4.15 and the vendored Jolt source:

- `Executors/newCachedThreadPool`;
- `Executors/newVirtualThreadPerTaskExecutor`; and
- `Executors/newWorkStealingPool`

all construct the same fixed 32-worker executor. A live gated 40-task probe
started exactly 32 tasks for both cached and virtual constructors. The current
handler default is also a fixed pool, but it is inaccurate to describe the
available cached shim as unbounded.

### Upstream change

Split the work:

1. **Correctness:** reduce the crash to the smallest repeatable concurrent-FFI
   program and fix the proven runtime defect.
2. **API fidelity:** make executor constructor names match scheduling and
   queueing semantics, introduce honestly named Jolt variants, or fail clearly
   for unsupported semantics.
3. **Feature/performance:** design a genuine M:N lightweight-task executor.

Do not block the safety fix on the scheduler, and do not implement virtual
threads by spawning an unbounded number of OS threads.

### Acceptance criteria

- A checked-in reproducer records exact SHA, OS/architecture, foreign
  signature, blocking/collect-safe mode, concurrency, and outcome.
- Repeated debug/sanitized and optimized runs survive the proven stress case.
- All executor constructors have conformance tests for maximum active tasks,
  queueing, ordering promises, shutdown, and exception behavior.
- An M:N executor, if added, proves multiplexing rather than relying on one
  native thread per parked task.

### jolt-http payoff

The fixed handler pool remains an explicit throughput/backpressure policy rather
than folklore around a crash. A later M:N executor can then improve blocking
and streaming concurrency without conflating scaling with correctness.

## 3. Make host-shim type identity and protocol dispatch agree

### Current constraint

On v0.4.15, extending a protocol to `java.io.File` compiles but a File value
silently invokes the `Object` arm. jolt-http works around this with explicit
File tests in [`jolt.http.body`](../src/jolt/http/body.clj) and
[`jolt.http.protocol`](../src/jolt/http/protocol.clj).

Silent fallback is more dangerous than an unsupported-class error: code appears
portable and chooses a generic behavior at runtime.

### Upstream change

Use one host type mapping for `class`, `instance?`, extension, and protocol
invocation. If a shim class cannot be extended, reject it when the extension is
defined. Add table-driven conformance coverage for every supported `java.*`
shim rather than fixing File as a special case.

### Acceptance criteria

- A `java.io.File` extension invokes its own protocol arm.
- `class`, `instance?`, `extend`, `extend-type`, `extend-protocol`, and
  invocation agree for each shim type.
- Unsupported extensions fail clearly and cannot silently dispatch to Object.
- Interpreted and AOT-compiled behavior match.

### jolt-http payoff

File response bodies and protocol adapters can use ordinary protocol dispatch.
The explicit File branches and the risk of other silent shim fallthroughs
disappear.

### Local proposal status

The initial File fix in `3105198a` did not close the invariant: live
InputStream and PersistentQueue witnesses still dispatched through `Object`.
`287f9022` adds one host-class registry and migrates File, binary
InputStream/OutputStream, and PersistentQueue; the focused coherence gate passes
22/22 checks. Legacy char readers/writers, NIO Path, concurrency shims,
transients, ReaderConditional, MultiFn, and per-value htable classes remain an
explicit migration/audit inventory, so “every supported shim” is not yet a
completed acceptance criterion. The shared binary-stream representation cannot
distinguish FileInputStream from ByteArrayInputStream, so the correction
deliberately narrows those concrete `instance?` aliases to false; characterize
downstream compatibility before proposing that behavior upstream.

## 4. Add daemon-thread/liveness control

### Current constraint

jolt-http loads core.async for request-body streaming. Its infrastructure
threads retain process liveness, so a `-main` must call `System/exit` even after
application work and server shutdown finish.

### Upstream change

Support daemon threads or make core.async's shared dispatch infrastructure
non-liveness-retaining. Executor and future implementations should document
whether their workers retain the process.

This does not remove the need for deterministic jolt-tcp shutdown: application
reactors and native resources must still be stopped explicitly.

### Acceptance criteria

- A program that loads and uses core.async exits normally after its application
  work completes and owned services stop.
- Non-daemon application threads still retain the process.
- Uncaught infrastructure exceptions remain observable.
- Server tests do not require `System/exit` to finish.

### jolt-http payoff

Examples, test runners, and applications can return normally without a manual
process kill hiding leaked application resources.

## 5. Add bulk byte primitives before a full buffer API

### Current constraint

Jolt lacks `System/arraycopy`; its native array transfers use per-byte loops
without offsets. jolt-tcp therefore allocates/copies between native memory and
its connection buffers. jolt-http additionally implements buffer range views
and response/body loops because the shared substrate has no efficient range
operation.

### Upstream change

First land:

```clojure
(arraycopy src src-off dest dest-off len) ; overlap-safe
(ffi/read-array! ptr len dest dest-off)
(ffi/write-array ptr src src-off len)
```

Then measure whether contiguous bytevector-backed arrays and a richer
position/limit buffer are required. Do not make the immediately useful transfer
operations wait for a Java-shaped NIO design.

### Acceptance criteria

- Overlapping moves agree with memmove-style reference behavior.
- Native subranges round-trip into arbitrary array offsets.
- jolt-tcp sends and receives existing buffer ranges without temporary arrays.
- HTTP range/chunk tests cover empty, partial, boundary, and large transfers.
- Before/after allocation and throughput measurements accompany the hot-path
  change.

### jolt-http payoff

Intermediate transport arrays disappear underneath HTTP and response-body
slicing can use one range primitive. The remaining buffer API becomes smaller
and driven by measured needs.

### Local proposal status

The reviewed fork now implements overlap-safe `System/arraycopy`, bulk native
array transfers, whole-array borrowing, and scoped nonzero-offset byte-array
borrowing. Reminted commit `8bc595eb` is the current byte-slice
implementation. The current HTTP/transport baseline,
`b921991e532ce2555d947bf88bc0464bf0c89d27`, also rejects executor submissions
after shutdown, preserves drive-rooted project paths on Windows, and validates
immutable Git dependency checkouts transactionally; every CI and runtime
checkout pins it through the single `JOLT_CORE_SHA` workflow variable.
`jolt.net` and jolt-tcp use those borrowed ranges
for socket I/O, including partial sends whose next position is nonzero.
Allocation and throughput measurement remains open.

## 6. Define shared byte input and output protocols

### Current constraint

Jolt's `proxy` is not a real Java subclass, so it cannot provide ordinary
`InputStream`/`OutputStream` compatibility. jolt-http consequently defines:

- a request-body source with its own delivery/backpressure rules; and
- a response `Sink` for byte-array writes.

jolt-tcp has another custom connection/stream shape. An output-only proposal
would leave the request side duplicated.

### Upstream change

Define small Jolt-native protocols for both directions:

- array/range read and write;
- close and half-close;
- optional flush;
- explicit blocking/backpressure/cancellation behavior; and
- adapters for arrays, files, buffers, sockets, and core.async where useful.

Keep HTTP framing, content length, chunked encoding, and Ring body coercion out
of the generic protocol.

The write side also needs one transport-level completion invariant: every
accepted write completes exactly once with either success or a structured
failure. A success-only callback is insufficient for the current blocking HTTP
sink, because a reset peer can otherwise leave its waiting promise unresolved.
This belongs in jolt-tcp's socket API, then in the shared output SPI, rather than
as HTTP knowledge in `jolt.net`.

The local fork now has that additive surface:
`teensyp.server/write-completion` returns a promise settled with `:written` or
`:failed`, while the legacy callback remains success-only. jolt-http's blocking
socket sink consumes it and throws failures to the response-producing task. A
deterministic connection-reset injection pins non-stranding behavior. One
diagnostic follow-on remains in jolt-tcp: if the response head fails before the
body completion can be admitted, the body sees `::socket-closed` rather than the
underlying reset cause.

### Acceptance criteria

- File, byte-array, socket, and bounded-buffer adapters share conformance tests.
- Partial writes/reads, EOF, half-close, close idempotence, and exceptions have
  explicit behavior.
- A slow consumer cannot cause unbounded hidden buffering.
- jolt-http can implement both request and response paths without pretending to
  subclass Java streams.

### jolt-http payoff

The local request-body and `Sink` machinery shrink toward shared primitives
while HTTP-specific flow control stays visible.

## 7. Build a cross-platform `jolt.net` and surface peer addresses

### Current constraint

jolt-tcp currently provides a loopback-oriented POSIX reactor and no peer
address, so jolt-http reports a configured constant as `:remote-addr`.

Upstream already contains broader pieces: `stdlib/jolt/mvn_http.clj` implements
DNS/address iteration, Windows Winsock startup, timeouts, and TLS; Jolt's nREPL
contains server socket code. A new network layer should factor these with
jolt-tcp rather than merely copying the current adapter.

### Initial contract

- generic endpoints, DNS, IPv4, and IPv6;
- Linux/macOS/Windows client and server support;
- non-blocking operations, wakeable readiness, and half-close;
- local and peer addresses;
- timeout/cancellation building blocks;
- safe broken-pipe behavior; and
- structured errors with native state captured at the failing call.

Deadline-bearing operations first require a real monotonic clock:
`System/nanoTime` currently derives from UTC wall-clock milliseconds and can
jump. Non-blocking connect must expose in-progress separately and verify
`SO_ERROR` after readiness. TLS adapters must preserve `WANT_READ`,
`WANT_WRITE`, clean `close_notify`, and truncated transport EOF as distinct
states.

TLS may remain layered, but the existing Maven HTTP client must have a clear
migration path.

### Acceptance criteria

- HTTP requests expose the actual IPv4 or IPv6 client address.
- Loopback server/client tests pass on all supported platforms.
- DNS fallback, connection timeout, EOF, half-close, and lost-wakeup cases are
  covered.
- Error data names the operation, endpoint, and stable/native code.

### jolt-http payoff

`:remote-addr` becomes truthful, platform support grows through the transport,
and socket edge cases live in one audited implementation.

### Local implementation status

`jolt.net` now owns endpoints, resolution, sockets, readiness registration,
wakeup, structured native errors, and handle generations on Linux, macOS,
Windows x86-64 **and Windows aarch64**. jolt-tcp is implemented over that
public surface and exposes
actual local/peer endpoint maps through `socket-info`; jolt-http derives
`:server-port`, `:server-name`, and `:remote-addr` there without importing
`jolt.net` or `jolt.ffi`.

Windows readiness is no longer follow-up work, on either architecture. The
pinned jolt-tcp revision (`e67fd1e`) ships reviewed Winsock readiness backends
and a public client for x86-64 **and aarch64** over jolt-net `3b83e53`, and
both of jolt-http's Windows lanes now run the complete real-loopback suite
rather than a portable subset — the same 55 scenarios they run on Linux, with no
runtime or socket group skipped. See
[runtime/windows-http-runtime.md](runtime/windows-http-runtime.md) for the
observed evidence.

Windows **ARM64** was previously a non-gating preview that ran only
descriptor-independent HTTP logic and asserted the transport failed closed with
`:unsupported-target`. That is retired: it now gates on the same dependency-free
real-loopback contracts and the same `JOLT_HEGEL_REQUIRED=1` suite as x86-64,
reporting identical counts. The jolt-hegel installer blocker recorded against
this target in W6A no longer reproduces — the lane fetches and sha256-verifies
`libhegel-windows-arm64.dll` on the runner.

One caveat remains, and it is not a Windows readiness gap:

- The portable connector/deadline and broader stream SPIs are still follow-up
  work on every platform.

## 8. Expose a complete target descriptor

### Current constraint

Executor sizing and native features need more than `os.name`. Current
`Runtime.availableProcessors` is hardcoded to `1` and a live v0.4.15 probe
returns `1`; architecture and path separator properties are incomplete.

### Upstream change

Expose OS, architecture, ABI/calling convention, libc, endianness, pointer
width, file/path-list separators, and available processor count as one stable
descriptor. Unknown values must be explicit.

### jolt-http payoff

Defaults can be based on real capacity and inherited native behavior can be
reported accurately. Pool size should remain configurable and workload-aware;
CPU count is an input, not the complete policy.

### Local proposal status

Jolt proposal commits `3105198a` and `34fabb2c` expose the zero-argument
`jolt.host/target` and replace fuzzy inference with an exact Chez machine-type
allowlist. The expanded focused suite passes 33/33 checks on Linux with
`scheme`.

Windows x86-64, Windows ARM64 and macOS arm64 no longer "still need native
validation": all are validated natively. `jolt.host/target` is asserted against
the real target by this repository's Windows gates —
`jolt.http.windows-runtime-test` takes the architecture the runner declares in
`JOLT_EXPECTED_ARCH` and refuses to run unless it observes the exact matching
`[:windows :x86-64 64]` or `[:windows :aarch64 64]` target, failing closed on a
missing or unknown value — and macOS arm64 runs the full hosted suite.

Because the architecture is declared rather than inferred from the running
process, the assertion is not satisfiable by emulation: an x86-64 Jolt on an
ARM64 runner fails the target check before any socket opens. The CI lane pairs
it with two independent checks on the same claim, `runner.arch == ARM64` and
Chez `(machine-type) == tarm64nt`.

Windows ARM64's `jolt.net` descriptor is no longer the outstanding gap — it is
reviewed and exercised over real loopback. What remains unvalidated on that
target is packaged/AOT distribution: every Windows result is source-runtime
evidence with `JOLT_AOT_CACHE=0`.

## 9. Add richer buffers and charset codecs only after the substrate

After bulk transfer and shared stream protocols land, consider:

- contiguous byte storage;
- a Jolt-native position/limit buffer with range views;
- charset values/codecs for at least US-ASCII, UTF-8, and ISO-8859-1; and
- zero-copy or ownership-transferring slices where the runtime can enforce
  lifetime.

The goal is not to reproduce all of `java.nio`. Each operation should correspond
to a measured HTTP/transport need. Explicit charset-name strings remain a valid
small API until a registry delivers more than nominal parity.

## Local work that should not wait for upstream

- Correct the stale comment in `jolt.http.body`: jolt-tcp dispatches write
  completions on a separate callback executor, not the handler pool.
- Correct documentation that calls the v0.4.15 cached executor unbounded; the
  current shim is fixed at 32. Preserve the FFI crash as a separate,
  evidence-seeking runtime issue.
- The local jolt-tcp fork now stops deterministically: it quiesces active
  handlers, retires jolt.net registrations and owned sockets, awaits close
  arities and callbacks, closes its poller, and rolls back partial starts.
- Supplied handler and callback executors are borrowed by default. jolt-http
  explicitly transfers the bounded pool it creates to jolt-tcp, while preserving
  borrowed ownership for a user pool unless `:shutdown-executor?` opts in.
- jolt-http now uses actual TCP endpoint metadata, supports a truthful
  kernel-selected `:server-port` after `:port 0`, and treats
  `:server-name`/`:remote-addr` as explicit overrides.
- jolt-http now rejects aggregate header overrun and unrepresentable request
  lengths, terminates every modeled partial-request EOF, and validates response
  status/header/framing metadata before the first byte is written. Its
  source-derived bounded models and semantic controls are recorded in
  [`proofs/http-fail-closed.md`](proofs/http-fail-closed.md).
- jolt-tcp now reports outcome-bearing write completion and jolt-http consumes
  it in the generic response sink. A peer reset either settles the queued
  completion as failed or closes admission first; both paths throw and unblock
  the producing task. Preserving the original close cause in the latter race is
  a diagnostic follow-on, not a liveness gap.
- The HTTP parser, keep-alive/pipelining, content-length/chunking, Ring-shaped
  contract, and bounded handler default remain library policy.

## Recommended implementation order

1. Specify the closed-world, fresh-process AOT build and whole-build digest. Do
   not ship the selective runtime namespace-cache prototype as the fix.
2. Reduce and fix concurrent-FFI safety; correct executor semantics separately.
3. Fix host-shim protocol dispatch.
4. Add bulk byte copy/range transfers.
5. Add the target descriptor and real CPU count.
6. Add daemon-thread/liveness control.
7. Define shared byte input/output protocols.
8. Factor the existing network implementations into cross-platform `jolt.net`.
9. Add richer buffers/storage and charset codecs only where measurement
   justifies them.
