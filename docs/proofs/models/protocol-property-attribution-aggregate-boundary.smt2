; Derived boundary control: the fixed runner reaches the exact 300000 ms
; aggregate timeout and must derive an attributed failure from its ordinary
; reporting equations.
(declare-datatypes () ((Outcome completed property_timeout aggregate_timeout threw)))
(declare-const outcome Outcome)
(declare-const property_id Int)
(declare-const reported_id Int)
(declare-const seed_known Bool)
(declare-const phase_known Bool)
(declare-const aggregate_elapsed_ms Int)
(declare-const incomplete_or_failed Bool)
(declare-const report_exact Bool)

(assert (! (and (<= 0 property_id) (< property_id 18)) :named property_domain))
(assert (! (= outcome aggregate_timeout) :named aggregate_timeout_control))
(assert (! (= aggregate_elapsed_ms 300000) :named exact_aggregate_boundary))
(assert (! (= incomplete_or_failed (not (= outcome completed)))
           :named incomplete_definition))
(assert (! (= reported_id (ite incomplete_or_failed property_id (- 1)))
           :named reported_id_definition))
(assert (! (= seed_known incomplete_or_failed) :named seed_definition))
(assert (! (= phase_known incomplete_or_failed) :named phase_definition))
(assert (! (= report_exact
              (and (= reported_id property_id) seed_known phase_known))
           :named attribution_definition))
(assert (! report_exact :named exact_report_query))
