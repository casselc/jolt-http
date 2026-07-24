% Known-success semantic control for the former behavior: EOF was not an input
% to incomplete parser states, so every incomplete parser/slot configuration
% had no successor and remained live.

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
transition(complete_request, no_slot, handler_ready).

terminal(silent_closed).
terminal(error_400_closed).
terminal(existing_response_closed).
terminal(handler_ready).

violation(State, Slot, nonterminal_dead_state) :-
  initial(State, Slot),
  \+ transition(State, Slot, _).

% Expected Chiasmus query:
%   violation(State, Slot, Reason).
% Result: two pre-handler states and both ownership configurations of the five
% body states are witnesses (12 configurations).
