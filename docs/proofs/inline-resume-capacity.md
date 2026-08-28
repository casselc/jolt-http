# Bounded invariant: inline responses do not fill the control queue

Checked on 2026-07-23 with Chiasmus and Z3.

## Claim

During one serialized parser read invocation, a response completed synchronously
inside the Ring handler contributes **zero** `resume-reads` control events.
Consequently, that source of events cannot overflow a positive-capacity control
queue, regardless of how many already-buffered requests the invocation parses.

This is a bounded, source-level invariant, not a proof that every producer of
socket controls is capacity-safe.

## Source facts

The model is extracted from these implementation branches:

- `src/jolt/http/protocol.clj:563-574`: `finish` calls `resume-reads` only when
  the request is streaming (`read-buffer` is `nil`) or the handler has already
  returned.
- `src/jolt/http/protocol.clj:619-625`: `handler-returned?` starts false and is
  set true only in the handler invocation's `finally`.
- `src/jolt/http/protocol.clj:627-633`: the synchronous/simple path passes its
  non-nil read buffer into that responder.
- `src/jolt/http/protocol.clj:635-652`: the streaming path deliberately passes
  `nil`, preserving its required off-thread wake-up.
- jolt-tcp's serialized worker contract prevents the reactor from draining
  controls for the same connection while its read handler remains `WORKING`.

Thus, for the scope of this claim:

```
inline-resumes-per-response = 0
resume-attempts = requests * inline-resumes-per-response
overflow = resume-attempts > capacity
```

The negated query asks for an overflow with `requests` in `0..128` and
`capacity` in `1..64`.

## Solver result and semantic controls

| Query | Expected | Z3 result | Evidence |
| --- | --- | --- | --- |
| Current inline completion branch | no counterexample | **UNSAT** | core: `capacity_in_domain`, `inline_completion_skips_resume`, `queued_controls_definition`, `violation_definition`, `queried_control_overflow` |
| Pre-fix branch, one resume per inline response | counterexample | **SAT** | `requests=2`, `capacity=1`, `resume-attempts=2` |
| Required off-thread wake-up at the minimum capacity | admitted behavior | **SAT** | one completion, one resume, capacity one |

The files are Chiasmus-ready SMT-LIB fragments; `chiasmus_verify` supplies the
solver commands:

- [`models/inline-resume-capacity.smt2`](models/inline-resume-capacity.smt2)
- [`models/inline-resume-per-response-control.smt2`](models/inline-resume-per-response-control.smt2)
- [`models/inline-resume-nonvacuity.smt2`](models/inline-resume-nonvacuity.smt2)

Run `chiasmus_lint` and then `chiasmus_verify` with solver `z3` against each
file. The expected statuses are `unsat`, `sat`, and `sat`, respectively.

## Runtime witness

`test/jolt/http/server_test.clj:292-329` sends 64 synchronous pipelined
requests through a server whose control queue capacity is 32. It requires all
64 responses and no control-queue error.

Before the inline-resume guard, the 33rd response attempted to enqueue into the
full queue; only 32 responses arrived and cleanup later encountered a spent
transient. `test/jolt/http/server_test.clj:806-819` separately pins the cleanup
behavior so any original parser error remains observable.

Run the focused companion test with:

```sh
jolt -M:test "pipelining" "exception cleanup"
```

The full gate is:

```sh
jolt -M:test
```

### Companion property-harness invariant

`test/jolt/http/server_property_test.clj:131-158` keeps exactly one future
around each complete receive loop. The former per-`recv` timeout scheme could
leave a timed-out reader blocked on the same fd, start a replacement reader,
and let the abandoned one consume the eventual response without appending it to
the live reader's accumulator. The observed two-live-reader execution therefore
made a correct server look as if it had timed out.

With the current shape, a successful `read-into!` call cannot leave a recv
behind: its one future owns every recv until the framing predicate or EOF ends
the loop. If the whole-loop deadline expires, the property aborts and its
`finally` closes that case's fd. This is a test-oracle soundness argument, not a
claim about production server scheduling.

## Bounds, assumptions, and gaps

- The arithmetic domain is finite: 0 to 128 inline completions and capacities
  1 to 64. The source equality is exact within that domain.
- The queue starts empty and is not drained during the modeled parser
  invocation. Allowing drains only weakens the overflow query.
- Only resume events originating from synchronous inline response completion
  are counted. Explicit application controls, callback-bearing controls, and
  other producers require separate capacity arguments.
- The non-vacuity control is intentionally separate: off-thread and streaming
  completions still enqueue one resume so the fix does not suppress a required
  wake-up.
