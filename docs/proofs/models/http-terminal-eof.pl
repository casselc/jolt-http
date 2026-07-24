% Every parser/response-slot state observed after transport EOF must move to a
% terminal outcome without producing more than one accepted response.

partial_before_handler(partial_start_line).
partial_before_handler(partial_headers).

partial_body(fixed_length_body_pending).
partial_body(chunk_size_line_partial).
partial_body(chunk_data_pending).
partial_body(chunk_data_crlf_partial).
partial_body(trailer_section_partial).

initial(idle_empty_start_line, no_slot).
initial(State, no_slot) :- partial_before_handler(State).
initial(State, unclaimed) :- partial_body(State).
initial(State, claimed) :- partial_body(State).
initial(complete_request, no_slot).

transition(idle_empty_start_line, no_slot, silent_closed).
transition(State, no_slot, error_400_closed) :-
  partial_before_handler(State).
transition(State, unclaimed, error_400_closed) :-
  partial_body(State).
transition(State, claimed, existing_response_closed) :-
  partial_body(State).
transition(complete_request, no_slot, handler_ready).

terminal(silent_closed).
terminal(error_400_closed).
terminal(existing_response_closed).
terminal(handler_ready).

response_count(silent_closed, 0).
response_count(error_400_closed, 1).
response_count(existing_response_closed, 1).
response_count(handler_ready, 0).

violation(State, Slot, waiting_after_eof) :-
  initial(State, Slot),
  transition(State, Slot, waiting_after_eof).
violation(State, Slot, nonterminal_dead_state) :-
  initial(State, Slot),
  \+ transition(State, Slot, _).
violation(State, Slot, nonterminal_successor) :-
  initial(State, Slot),
  transition(State, Slot, Next),
  \+ terminal(Next).
violation(State, Slot, duplicate_response) :-
  initial(State, Slot),
  transition(State, Slot, Next),
  response_count(Next, Count),
  Count > 1.

% Expected Chiasmus query:
%   violation(State, Slot, Reason).
% Result: no answers.
