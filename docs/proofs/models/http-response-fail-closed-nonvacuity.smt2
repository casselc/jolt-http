; Known-SAT boundary: valid response metadata is admitted, and a valid
; Transfer-Encoding takes precedence over Content-Length in the output.
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
(declare-const evaluator_allows Bool)
(declare-const output_has_content_length Bool)
(declare-const output_has_transfer_encoding Bool)

(assert (! (= status status_valid) :named valid_status))
(assert (! (= headers headers_safe) :named safe_headers))
(assert (! (= content_length cl_valid) :named valid_content_length))
(assert (! (= transfer_encoding te_chunked) :named valid_chunked_encoding))
(assert (! (= evaluator_allows
              (and (= status status_valid)
                   (= headers headers_safe)
                   (not (= content_length cl_invalid))
                   (not (= transfer_encoding te_invalid))))
           :named evaluator_definition))
(assert (! evaluator_allows :named valid_response_is_admitted))
(assert (! (= output_has_transfer_encoding
              (= transfer_encoding te_chunked))
           :named transfer_encoding_output_definition))
(assert (! (= output_has_content_length
              (and (= content_length cl_valid)
                   (not (= transfer_encoding te_chunked))))
           :named content_length_output_definition))
(assert (! output_has_transfer_encoding :named chunked_output_is_preserved))
(assert (! (not output_has_content_length)
           :named chunked_output_suppresses_content_length))
