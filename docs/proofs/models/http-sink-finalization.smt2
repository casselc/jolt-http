; Counterexample query: every synchronous StreamableBody response, whether the
; Ring handler itself is sync or async and whether the body closes early or not,
; must expose exactly one downstream sink finalization.
(declare-const body_closes Bool)
(declare-const async_handler Bool)
(declare-const adapter_attempts_close Bool)
(declare-const total_close_attempts Int)
(declare-const observable_finalizations Int)
(declare-const violation Bool)

; async_handler deliberately remains free: UNSAT covers both values.
(assert (! (= adapter_attempts_close true) :named adapter_finally_rule))
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
