; Known-SAT control for the former async finalization rule. The adapter closed
; only synchronous responses, so an async body that did not close its own sink
; produced no terminal chunk and no downstream finalization.
(declare-const body_closes Bool)
(declare-const async_handler Bool)
(declare-const adapter_attempts_close Bool)
(declare-const total_close_attempts Int)
(declare-const observable_finalizations Int)
(declare-const violation Bool)

(assert (! (= async_handler true) :named async_witness))
(assert (! (= body_closes false) :named body_does_not_close_witness))
(assert (! (= adapter_attempts_close (not async_handler))
           :named old_adapter_rule))
(assert (! (= total_close_attempts
              (+ (ite body_closes 1 0)
                 (ite adapter_attempts_close 1 0)))
           :named close_attempt_count))
(assert (! (= observable_finalizations
              (ite (> total_close_attempts 0) 1 0))
           :named idempotent_sink_rule))
(assert (! (= violation (not (= observable_finalizations 1)))
           :named violation_definition))
(assert (! violation :named violation_query))
