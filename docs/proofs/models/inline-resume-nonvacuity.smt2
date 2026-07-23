; Non-vacuity boundary: one off-thread completion still queues exactly one
; resume and fits the minimum positive capacity.
; Chiasmus adds check-sat/model commands.
(declare-const off_thread_completions Int)
(declare-const capacity Int)
(declare-const resumes_per_completion Int)
(declare-const queued_controls Int)

(assert (! (= off_thread_completions 1)
           :named one_off_thread_completion))
(assert (! (= capacity 1)
           :named minimum_positive_capacity))
(assert (! (= resumes_per_completion 1)
           :named off_thread_completion_resumes))
(assert (! (= queued_controls (* off_thread_completions resumes_per_completion))
           :named queued_controls_definition))
(assert (! (= queued_controls 1)
           :named exact_one_resume))
(assert (! (<= queued_controls capacity)
           :named capacity_boundary_is_admitted))
