# Bounded invariants: response validation, terminal EOF, and sink finalization

Checked on 2026-07-24 with Chiasmus, Z3, and SWI-Prolog.

These are small source-derived models with executable controls. They prove the
stated properties of the extracted finite abstractions; the companion fixed and
Hegel tests connect those abstractions back to the implementation. They are not
a proof of all HTTP parser behavior.

## Invalid response metadata cannot reach serialization

### Claim

Before the first response byte is written, the response evaluator classifies
status, header syntax, `Content-Length`, and `Transfer-Encoding`. If any required
class is invalid, the handler response is replaced with the fixed internal 500.
A valid `Transfer-Encoding: chunked` takes precedence over a valid
`Content-Length`, which is removed from the serialized response.

### Source facts

- `jolt.http.protocol/unsafe-field-value?`, `content-length-state`,
  `transfer-encoding-state`, and `valid-status?` classify unsafe response
  headers, representable decimal content lengths, transfer encoding, and
  three-digit integer statuses.
- `jolt.http.protocol/sanitize-response` constructs one `problems` collection,
  returns the fixed 500 when it is nonempty, and otherwise removes both original
  framing fields before adding at most one canonical field.
- `jolt.http.protocol/sanitize-response` selects chunked transfer encoding
  before content length, so both fields cannot be emitted.
- `jolt.http.protocol/ring-responder` sanitizes before clearing or writing the
  response buffer.

The finite model has two status classes, two header classes, three content-length
classes, and three transfer-encoding classes. Its counterexample query asks for
both an invalid required input and permission to serialize.

### Solver result and controls

| Query | Expected | Result | Evidence |
| --- | --- | --- | --- |
| Current fail-closed evaluator | no counterexample | **UNSAT** | core includes the invalid-input, evaluator, violation, and query definitions |
| Old header-only evaluator | counterexample | **SAT** | invalid status + safe headers + valid content length is admitted |
| Valid chunked + content-length input | admitted and canonicalized | **SAT** | output keeps transfer encoding and removes content length |

Models:

- [`models/http-response-fail-closed.smt2`](models/http-response-fail-closed.smt2)
- [`models/http-response-fail-closed-bug-control.smt2`](models/http-response-fail-closed-bug-control.smt2)
- [`models/http-response-fail-closed-nonvacuity.smt2`](models/http-response-fail-closed-nonvacuity.smt2)

Run `chiasmus_lint` and then `chiasmus_verify` with solver `z3`; expected
statuses are `unsat`, `sat`, and `sat`.

## Streamable response sinks finalize exactly once

### Claim

`StreamableBody` is synchronous at the sink boundary. Whether a Ring handler
calls `respond` synchronously or asynchronously, the adapter closes the sink in
a `finally`. A custom body may close it early; the coalescing and chunked sink
wrappers make repeated close attempts idempotent. The observable downstream
finalization count is therefore exactly one across the four combinations of
handler mode and custom-body close behavior.

### Source facts

- `jolt.http.protocol/write-via-sink` creates the sink chain and closes it in
  `finally` without conditioning on handler mode.
- `jolt.http.body/coalescing-sink` makes the coalescing wrapper's close
  idempotent and flushes buffered bytes before delegating close.
- `jolt.http.body/chunked-sink` makes the chunked wrapper's close idempotent,
  emits one terminal chunk, and delegates one downstream close.
- `jolt.http.body/StreamableBody` documents the synchronous body contract and
  forbids retaining the sink.

The Z3 model ranges over both Boolean handler modes and both custom-body close
choices. It derives close-attempt count, then derives observable finalization
count from the wrappers' idempotence. Its violation query requires that count
to differ from one.

### Solver result and controls

| Query | Expected | Result | Evidence |
| --- | --- | --- | --- |
| Current unconditional adapter finalizer | no counterexample | **UNSAT** | core contains adapter-finally, attempt-count, idempotence, violation definition, and query |
| Former async-conditioned finalizer | counterexample | **SAT** | async handler + body does not close gives zero attempts/finalizations |
| Self-closing async body | reachable and exactly once | **SAT** | two close attempts, one observable finalization |

Models:

- [`models/http-sink-finalization.smt2`](models/http-sink-finalization.smt2)
- [`models/http-sink-finalization-bug-control.smt2`](models/http-sink-finalization-bug-control.smt2)
- [`models/http-sink-finalization-nonvacuity.smt2`](models/http-sink-finalization-nonvacuity.smt2)

All three were linted and verified through Chiasmus with solver `z3`; expected
statuses are `unsat`, `sat`, and `sat`.

## EOF cannot strand an incomplete request

### Claim

Once transport EOF is observable, an empty idle parser closes silently and a
complete request remains eligible for its handler. An incomplete request whose
response slot is still available produces one 400 and closes. If a streaming
handler already claimed that slot before body truncation became observable, the
connection closes behind that existing response without emitting a second one.
None of those states waits for bytes that can no longer arrive or accepts more
than one response.

### Source facts

- `jolt.http.protocol/read-header` turns EOF during a partial header section
  into `:incomplete-request`.
- `jolt.http.protocol/read-chunked-body` and `read-known-length-body` turn EOF
  in fixed-length, chunk-size, chunk-data, chunk-CRLF, and trailer states into
  an incomplete-body error.
- `jolt.http.protocol/buffer-reads` closes an idle connection only after the
  previous response is complete and no buffered request remains.
- `jolt.http.protocol/body-error` atomically distinguishes an available
  body-response slot from one an early handler response already claimed.
- `jolt.http.protocol/write-error-response` closes after the error response and
  returns `:done`, preventing re-entry and a second error write.
- `jolt.http.protocol/tcp-handler` has no transition out of `:done`.

The Prolog model enumerates the parser-state classes involved at EOF and, for
body states, both response-slot ownership values. Its violation query checks for
an explicit wait, a state with no transition, a nonterminal successor, or more
than one accepted response.

### Solver result and controls

| Query | Expected | Result | Evidence |
| --- | --- | --- | --- |
| Current EOF transition relation | no violation | **no answers** | every initial class has a terminal successor; response count is at most one |
| Old EOF-insensitive relation | counterexamples | **12 answers** | each incomplete parser/slot configuration is a nonterminal dead state |
| Idle, error, and claimed-response witnesses | reachable behavior | **1 + 7 + 5 answers** | idle closes silently; available slots get one 400; claimed body slots retain exactly one response |

Models:

- [`models/http-terminal-eof.pl`](models/http-terminal-eof.pl)
- [`models/http-terminal-eof-bug-control.pl`](models/http-terminal-eof-bug-control.pl)
- [`models/http-terminal-eof-nonvacuity.pl`](models/http-terminal-eof-nonvacuity.pl)

Run `chiasmus_lint` and then `chiasmus_verify` with solver `prolog`. Query the
main and bug-control models with `violation(State, Slot, Reason).`; run the three
commented queries in the non-vacuity model as a batch.

## Implementation witnesses and bounds

- `jolt.http.server-test/test-response-metadata-fails-closed` pins invalid
  response metadata, canonical framing, and fail-closed output.
- `jolt.http.server-test/test-incomplete-request-eof-is-terminal` covers every
  incomplete EOF class, aggregate header boundaries, and unrepresentable
  content length.
- `jolt.http.server-test/test-async-streamable-bodies-finalize` checks that
  sequential and custom streamable bodies terminate exactly once.
- `jolt.http.server-test/test-peer-reset-unblocks-streaming-response` injects a
  deterministic connection-reset outcome after request admission and proves
  the async response task unblocks.
- `jolt.http.body-property-test/content-length-is-bounded-by-the-parser-counter`,
  `content-length-parser-is-total-over-decimal-input`, and
  `content-length-range-check-does-not-need-a-wide-integer` check bounds and
  total parsing across generated decimal strings.
- `jolt.http.body-property-test/sink-chain-finalization-is-observably-exactly-once`
  observes one downstream close and one chunk terminator after both a custom
  close and the adapter's final close.
- `jolt.http.protocol-property-test/aggregate-header-count-is-an-exact-boundary`
  and `aggregate-header-bytes-include-the-final-crlf` generate exact aggregate
  header byte/count boundaries.
- `jolt.http.protocol-property-test/response-metadata-is-canonical-or-fails-closed`
  generates response status and framing classes and requires either canonical
  output or a safe 500.
- `jolt.http.protocol-property-test/terminal-eof-rejects-every-nonempty-request-prefix`
  and `terminal-eof-does-not-duplicate-an-already-claimed-response` cover every
  nonempty EOF prefix and the already-claimed response branch.
- `jolt.http.protocol-property-test/fake-close-awaits-streaming-handler-settlement`
  pins the test harness's cleanup assumption: closing an incomplete request-body
  channel is a barrier for that connection's submitted handler work.

The response model deliberately collapses byte strings into syntax/framing
classes; classification correctness is tested, not proven by Z3. The EOF model
collapses buffer positions and callback scheduling into parser-state plus
response-slot classes; the compare-and-set linearization is a source assumption
pinned by the claimed-response test. It does not prove TCP delivery, handler
progress, or application liveness. Malformed-line grammar, arithmetic
boundaries, and transition coverage remain implementation-test obligations.
The deterministic write-failure witness proves terminal settlement at the HTTP
sink boundary and exercises both allowed TCP timing outcomes. A write already
in flight can surface the reset directly. If the response head closes the
connection before the next body completion is admitted, TCP preserves its
synchronous `::socket-closed` admission contract and attaches the first reset
as `ex-cause`. The test requires that causal link rather than accepting an
opaque closed-socket failure.
