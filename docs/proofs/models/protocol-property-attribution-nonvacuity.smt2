; A completed passing property must still pass the gate.
(declare-const property_id Int)
(declare-const property_pass Bool)
(declare-const completed Bool)
(declare-const gate_pass Bool)

(assert (! (and (<= 0 property_id) (< property_id 18)) :named property_domain))
(assert (! (= completed true) :named completed_control))
(assert (! (= property_pass true) :named passing_control))
(assert (! (= gate_pass (and completed property_pass)) :named gate_definition))
(assert (! gate_pass :named nonvacuous_pass_query))
