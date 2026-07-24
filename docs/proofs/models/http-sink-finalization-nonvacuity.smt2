; Known-SAT non-vacuity witness. A custom body closes the sink and the adapter's
; finally closes it again, but idempotence exposes exactly one finalization.
(declare-const body_closes Bool)
(declare-const async_handler Bool)
(declare-const adapter_attempts_close Bool)
(declare-const total_close_attempts Int)
(declare-const observable_finalizations Int)

(assert (! (= async_handler true) :named async_witness))
(assert (! (= body_closes true) :named self_closing_body_witness))
(assert (! (= adapter_attempts_close true) :named current_adapter_rule))
(assert (! (= total_close_attempts
              (+ (ite body_closes 1 0)
                 (ite adapter_attempts_close 1 0)))
           :named close_attempt_count))
(assert (! (= observable_finalizations
              (ite (> total_close_attempts 0) 1 0))
           :named idempotent_sink_rule))
(assert (! (= total_close_attempts 2) :named both_close_attempts_reachable))
(assert (! (= observable_finalizations 1)
           :named one_observable_finalization))
