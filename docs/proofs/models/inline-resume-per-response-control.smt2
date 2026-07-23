; Known-SAT semantic control: the pre-fix path queued one resume per inline
; response. Chiasmus adds check-sat/model commands.
(declare-const requests Int)
(declare-const capacity Int)
(declare-const inline_resumes_per_response Int)
(declare-const queued_controls Int)
(declare-const violation Bool)

(assert (! (and (<= 0 requests) (<= requests 128))
           :named requests_in_domain))
(assert (! (and (<= 1 capacity) (<= capacity 64))
           :named capacity_in_domain))
(assert (! (= inline_resumes_per_response 1)
           :named faulty_inline_completion_resumes))
(assert (! (= queued_controls (* requests inline_resumes_per_response))
           :named queued_controls_definition))
(assert (! (= violation (> queued_controls capacity))
           :named violation_definition))
(assert (! violation :named queried_control_overflow))
