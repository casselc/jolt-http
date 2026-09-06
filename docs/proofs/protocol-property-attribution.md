# Bounded invariant: protocol-property failures remain attributable

Checked on 2026-09-05 with Chiasmus and Z3.

## Claim

For each of the 18 currently discovered protocol properties, a normal run has
a 60-second per-property watchdog inside the existing 300-second aggregate
watchdog. If the property times out, the aggregate gate times out, or the
property runner throws, the failure must identify the active property, its
replay seed, and the observable runner phase. A completed passing property must
still pass.

The phase is deliberately coarse: `:hegel-run` means the property has entered
the Hegel invocation. The pinned Hegel API does not expose whether libhegel is
currently generating, executing, or shrinking, so the model does not claim
that distinction.

## Source facts

- `test/jolt/http/protocol_property_test.clj:38-72` discovers every deftest in
  stable source order, runs its body under the correct testing-var binding with
  an explicit seed in the existing common Hegel options, and fails closed if
  the namespace gains fixtures the per-var runner does not implement.
- `test/jolt/http/server_test.clj:56-83` validates the per-property timeout,
  chooses a non-negative replay seed, and durably records property, seed, and
  phase before execution.
- `test/jolt/http/server_test.clj:85-133` classifies completion, throw, and
  timeout as data; thrown and timed-out results include the active context.
- `test/jolt/http/server_test.clj:135-181` supplies completed, thrown, blocked,
  nonempty-context, timeout-policy, and fixture-rejection semantic controls.
  The blocked worker is released and joined, so the positive suite does not
  leak the control future.
- `test/jolt/http/server_test.clj:1549-1592` validates fault selection, runs
  every discovered property separately, supplies its seed to Hegel, and stops
  the remaining suite after a timeout because Jolt has no cooperative future
  cancellation.

## Solver result and controls

| Query | Expected | Result | Evidence |
| --- | --- | --- | --- |
| Current attributed runner | no counterexample | **UNSAT** | core contains incomplete, id, seed, phase, gate, attribution, violation, and query definitions |
| Former namespace-wide runner | counterexample | **SAT** | property 0 times out with reported id -1, no seed, and no phase |
| Completed passing property | admitted | **SAT** | property 0 completes and the gate passes |
| Exact aggregate boundary | attributed failure | **SAT** | at 300000 ms, id, seed, and phase remain known |

Models:

- [`models/protocol-property-attribution.smt2`](models/protocol-property-attribution.smt2)
- [`models/protocol-property-attribution-bug-control.smt2`](models/protocol-property-attribution-bug-control.smt2)
- [`models/protocol-property-attribution-nonvacuity.smt2`](models/protocol-property-attribution-nonvacuity.smt2)
- [`models/protocol-property-attribution-aggregate-boundary.smt2`](models/protocol-property-attribution-aggregate-boundary.smt2)

Run `chiasmus_lint` and then `chiasmus_verify` with solver `z3`. Expected
statuses are `unsat`, `sat`, `sat`, and `sat`.

## Runtime witness and objective measure

The initial pre-change focused baseline on the same machine and dependency
cache was 25.82 seconds for 18 tests and 23 passing assertions. The target was
no more than 31 seconds (20 percent overhead), with every property bounded at
60 seconds and the aggregate bound unchanged at 300 seconds.

Because individual generative runs vary, the final comparison alternated three
base and three candidate runs on the same machine and dependency cache. Base
samples were 21.77, 19.19, and 24.12 seconds (median 21.77); candidate samples
were 18.75, 21.07, and 20.58 seconds (median 20.58). The candidate was 5.5
percent faster by median and every candidate sample stayed below the 31-second
target, with the same 18 properties and 23 passing assertions.

The process-level negative control used a 100 ms bound and deliberately blocked
`request-parsing-is-invariant-under-chunking`; it exited nonzero in 0.62 seconds
and reported the exact property, generated seed, and `:hegel-run` phase in both
stdout and `/tmp/jolt-http-test-progress.log`.

Run the controls with:

```sh
jolt -M:test "watchdog controls"

JOLT_HTTP_PROTOCOL_PROPERTY_TIMEOUT_MS=100 \
JOLT_HTTP_FAULT_HANG_PROPERTY=request-parsing-is-invariant-under-chunking \
jolt -M:test "protocol properties"
```

`JOLT_HTTP_FAULT_THROW_PROPERTY` exercises the real attributed throw path. An
unknown fault selector, an unhandled namespace fixture, and a non-positive
timeout all fail closed rather than silently turning a control into a different
run.

## Bounds and remaining gaps

- The formal property domain is the 18 vars present at verification time. The
  runtime discovers deftests, so new properties run automatically, but the
  finite model and this count must be refreshed when they are added.
- Z3 models result classification and attribution, not scheduler or Hegel
  semantics. The completed and forced-hang runtime controls connect those
  abstractions to the executable runner.
- A timed-out Jolt future cannot be cooperatively cancelled. The runner stops
  the remaining scenarios and reaches its existing `System/exit`; it does not
  start another property beside the abandoned worker.
- Ordinary top-level scenario timeouts preserve the former continue-and-report
  behavior. Only a per-property timeout or the protocol group aggregate timeout
  requests suite abort, which is covered by the timeout-policy controls.
- The seed makes a completed failure replayable and identifies a hung run, but
  replaying a timing-sensitive hang is not guaranteed to reproduce scheduler
  timing.
