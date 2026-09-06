; Known-SAT control for the former namespace-wide watchdog. A timed-out
; property fails the gate but has no property id, seed, or phase attribution.
(declare-const property_id Int)
(declare-const reported_id Int)
(declare-const seed_known Bool)
(declare-const phase_known Bool)
(declare-const gate_pass Bool)
(declare-const incomplete_or_failed Bool)
(declare-const report_exact Bool)
(declare-const violation Bool)

(assert (! (and (<= 0 property_id) (< property_id 18)) :named property_domain))
(assert (! (= reported_id (- 1)) :named old_reported_id))
(assert (! (= seed_known false) :named old_seed_definition))
(assert (! (= phase_known false) :named old_phase_definition))
(assert (! (= gate_pass false) :named old_gate_definition))
(assert (! (= incomplete_or_failed true) :named timeout_control))
(assert (! (= report_exact
              (and (= reported_id property_id) seed_known phase_known))
           :named attribution_definition))
(assert (! (= violation
              (and incomplete_or_failed (or gate_pass (not report_exact))))
           :named violation_definition))
(assert (! violation :named violation_query))
