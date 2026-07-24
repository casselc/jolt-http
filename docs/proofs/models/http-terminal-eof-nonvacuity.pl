% Known-success controls for idle EOF, incomplete unclaimed messages, and the
% early-response race. Run the three queries in the comments as a batch.

partial_before_handler(partial_start_line).
partial_before_handler(partial_headers).

partial_body(fixed_length_body_pending).
partial_body(chunk_size_line_partial).
partial_body(chunk_data_pending).
partial_body(chunk_data_crlf_partial).
partial_body(trailer_section_partial).

transition(idle_empty_start_line, no_slot, silent_closed).
transition(State, no_slot, error_400_closed) :-
  partial_before_handler(State).
transition(State, unclaimed, error_400_closed) :-
  partial_body(State).
transition(State, claimed, existing_response_closed) :-
  partial_body(State).

response_count(silent_closed, 0).
response_count(error_400_closed, 1).
response_count(existing_response_closed, 1).

% Expected Chiasmus queries:
%   transition(idle_empty_start_line, no_slot, silent_closed),
%     response_count(silent_closed, 0).
%   transition(State, Slot, error_400_closed),
%     response_count(error_400_closed, 1).
%   transition(State, claimed, existing_response_closed),
%     response_count(existing_response_closed, 1).
% Results: one idle witness, seven error-response configurations, and all five
% body states with a previously claimed response.
