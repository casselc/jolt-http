# Upstream improvements for jolt-http

This document records changes to Jolt and jolt-tcp that would make jolt-http
safer, smaller, or easier to scale. It is a local planning document, not an
upstream issue tracker.

## Verification baseline

Revalidated 2026-07-23 against:

- installed `joltc v0.4.15`;
- Jolt commit `d5aaf503fc7a45c5638d21215eb153b426a7e8dc` in
  `jolt-tcp/refs/jolt`; and
- the current local jolt-http and jolt-tcp sources.

jolt-http has no direct FFI. It layers an HTTP/1.1 parser and Ring-shaped handler
contract over jolt-tcp's poll reactor, so native safety and byte-transfer
changes are inherited. See
[jolt-tcp's detailed upstream document](../../jolt-tcp/docs/UPSTREAM-IMPROVEMENTS.md).

## Priority summary

| Priority | Upstream area | Independently landable change | Main payoff |
| --- | --- | --- | --- |
| P0 | Jolt loader | Dependency-correct AOT cache identity | Prevent stale compiled HTTP/parser/adapter behavior |
| P1 | Jolt runtime | Reproduce and harden concurrent foreign calls | Remove an uncharacterized process-corruption risk |
| P1 | Jolt concurrency | Make JVM-named executor shims semantically honest | Stop presenting fixed-32 pools as cached, virtual, or work-stealing |
| P1 | Jolt host shims | Make type tests and protocol dispatch agree | Remove silent `java.io.File` fallthrough |
| P1 | Jolt threads | Daemon threads or daemon core.async infrastructure | Let a server process exit normally |
| P1 | Jolt/jolt-tcp bytes | Bulk array copy and range-aware native transfer | Remove inherited transport copies and body-range loops |
| P2 | Jolt runtime | Genuine M:N lightweight tasks | Scale blocking handlers without one OS thread each |
| P2 | Jolt stdlib | Shared byte input/output protocols | Replace local request-body and response-sink shapes |
| P2 | Jolt stdlib | Cross-platform `jolt.net` with peer address | Report real clients and inherit audited socket behavior |
| P2 | `jolt.host` | Complete target descriptor and real CPU count | Make executor/platform defaults honest |
| P3 | Jolt bytes | Contiguous byte storage, richer buffers, charset registry | Consolidate the remaining HTTP byte plumbing |

P0 is a demonstrated wrong-code class. P1 covers runtime correctness, semantic
fidelity, and process lifecycle. P2 adds reusable runtime/stdlib capability. P3
should follow the smaller bulk-byte substrate.

## 1. Make AOT identity dependency-correct

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

### Upstream change

Fingerprint all compile inputs: namespace bytes, compile-time dependencies
(macros, data readers, namespaces loaded while compiling), compiler options,
Jolt/compiler version, and target identity. Advance the cache format and define
how load-site source metadata remains current.

Use a reusable native streaming SHA-256 primitive. A fast non-cryptographic hash
does not meet the cache identity requirement.

### Acceptance criteria

- Same-length different sources never share an artifact.
- Changing only a macro dependency forces the unchanged consumer to recompile
  and observe the new expansion.
- Compiler/target changes cannot load an incompatible artifact.
- Current source paths appear in metadata and diagnostics.

### jolt-http payoff

Resolved source and executable HTTP behavior cannot silently diverge. Cache
deletion stops being part of routine correctness guidance after the fixed Jolt
release becomes the minimum.

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

Transport copies disappear underneath HTTP and response-body slicing can use
one efficient primitive. The remaining buffer API becomes smaller and driven by
measured needs.

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
- The local jolt-tcp fork now stops deterministically: it retains reactor
  completion, closes both wake-pipe descriptors, frees per-server native
  buffers, rolls back partial starts, and tests wake/close serialization. Keep
  that lifecycle contract when migrating transport primitives upstream.
- Supplied handler and callback executors are now borrowed by default; HTTP
  applications can share longer-lived pools safely unless they explicitly opt
  into jolt-tcp shutdown ownership.
- The HTTP parser, keep-alive/pipelining, content-length/chunking, Ring-shaped
  contract, and bounded handler default remain library policy.

## Recommended implementation order

1. Fix dependency-aware AOT cache identity.
2. Reduce and fix concurrent-FFI safety; correct executor semantics separately.
3. Fix host-shim protocol dispatch.
4. Add bulk byte copy/range transfers.
5. Add the target descriptor and real CPU count.
6. Add daemon-thread/liveness control.
7. Define shared byte input/output protocols.
8. Factor the existing network implementations into cross-platform `jolt.net`.
9. Add richer buffers/storage and charset codecs only where measurement
   justifies them.
