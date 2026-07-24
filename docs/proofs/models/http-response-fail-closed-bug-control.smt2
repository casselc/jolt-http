; Known-SAT semantic control. The old evaluator checked only header syntax,
; allowing invalid status/framing metadata to reach serialization.
; Chiasmus supplies check-sat and model commands.
(declare-datatypes ((StatusClass 0))
  (((status_valid) (status_invalid))))
(declare-datatypes ((HeaderClass 0))
  (((headers_safe) (headers_unsafe))))
(declare-datatypes ((ContentLengthClass 0))
  (((cl_none) (cl_valid) (cl_invalid))))
(declare-datatypes ((TransferEncodingClass 0))
  (((te_none) (te_chunked) (te_invalid))))

(declare-const status StatusClass)
(declare-const headers HeaderClass)
(declare-const content_length ContentLengthClass)
(declare-const transfer_encoding TransferEncodingClass)
(declare-const invalid_required_input Bool)
(declare-const old_evaluator_allows Bool)
(declare-const violation Bool)

(assert (! (= status status_invalid) :named invalid_status_witness))
(assert (! (= headers headers_safe) :named safe_headers_witness))
(assert (! (= content_length cl_valid) :named valid_content_length_witness))
(assert (! (= transfer_encoding te_none) :named absent_transfer_encoding_witness))
(assert (! (= invalid_required_input
              (or (= status status_invalid)
                  (= headers headers_unsafe)
                  (= content_length cl_invalid)
                  (= transfer_encoding te_invalid)))
           :named invalid_required_input_definition))
(assert (! (= old_evaluator_allows (= headers headers_safe))
           :named old_evaluator_definition))
(assert (! (= violation
              (and invalid_required_input old_evaluator_allows))
           :named violation_definition))
(assert (! violation :named known_bad_path))
