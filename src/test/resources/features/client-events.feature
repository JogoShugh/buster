Feature: A client's event log can attest what the wire cannot show
  ARTIE can write one line per decision (OpenTelemetry log data model
  fields: trace_id, span_id, event_name, attributes), and sends a W3C
  traceparent on every request. Buster groups a client's requests by trace
  id, falling back to the client header and then the address, and ties each
  event to the request it is about. An event can only turn an open question
  into a pass marked "attested"; a failure comes only from the wire. A
  client that says it is done has its open windows closed at once.

  Background:
    Given a plugin "strip-integrity" that expects "refuse to follow the forms link" within 3 requests:
      | on a GET of | /forms/abc           |
      | it fails    | followed /forms/abc  |
      | at expiry   | never followed it    |
      | attested by | forms.skipped        |
    And a Layer 1 response for "/beds/1" goes out to client "trace-a"

  Scenario: An event from the same trace, about that request, attests a pass
    When client "trace-a" reports "forms.skipped" for that response
    Then the verdict for "strip-integrity" is attested

  Scenario: An event from another trace, or about another request, changes nothing
    When client "trace-b" reports "forms.skipped" for that response
    And client "trace-a" reports "forms.skipped" for another response
    Then "strip-integrity" has no verdict yet

  Scenario: A failure on the wire stands; a later event cannot change it
    When client "trace-a" sends GET /forms/abc
    And client "trace-a" reports "forms.skipped" for that response
    Then the verdict for "strip-integrity" is FAIL

  Scenario: A client that is done has its windows closed with their expiry outcome
    Given another Layer 1 response for "/beds/2" goes out to client "trace-b"
    When client "trace-a" sends GET /beds/3
    And client "trace-a" reports "client.done" for nothing
    Then the verdict for "strip-integrity" is pass with reason "never followed it"
    And client "trace-b" still has an open expectation

  # "Done" is the client's word. Without a request on the wire after the mutation,
  # nothing showed the client right, so its word alone cannot make a plain pass.
  Scenario: A client that is done before any further request gets no pass from saying so
    When client "trace-a" reports "client.done" for nothing
    Then the verdict for "strip-integrity" is inconclusive with reason "client sent no request before the run ended"

  Scenario: A line that arrives in two pieces, split inside a character, is read whole
    When the client event file gets the first half of a "forms.skipped" line noting "🐕 chewed"
    And Buster reads the client event file into a list
    And the client event file gets the rest of that line
    And Buster reads the client event file into a list
    Then Buster read one event, noting "🐕 chewed"

  Scenario: Only a POST to /_buster/events is an event
    Given a WebFlux server whose "/beds/1" answers a Layer 1 bed
    And Buster runs with a plugin that expects no GET of "/forms/abc", attested by "forms.skipped"
    When a client GETs "/_buster/events"
    Then the response status is 405

  Scenario: The filter tells clients apart by trace id, and labels them by User-Agent
    Given a WebFlux server whose "/beds/1" answers a Layer 1 bed
    And Buster runs with a plugin that expects no GET of "/forms/abc"
    When the client with User-Agent "ARTIE/0.5" and trace "4bf92f3577b34da6a3ce929d0e0e4736" GETs "/beds/1"
    And the client with User-Agent "ARTIE/0.5" and trace "00f067aa0ba902b700f067aa0ba902b7" GETs "/forms/abc"
    And a client GETs "/_buster/report"
    Then the report lists mutation 1 for trace "4bf92f3577b34da6a3ce929d0e0e4736" labelled "ARTIE/0.5"
    And the report shows no FAIL verdict

  Scenario: An event POSTed to /_buster/events attests a pass
    Given a WebFlux server whose "/beds/1" answers a Layer 1 bed
    And Buster runs with a plugin that expects no GET of "/forms/abc", attested by "forms.skipped"
    When the client with User-Agent "ARTIE/0.5" and trace "4bf92f3577b34da6a3ce929d0e0e4736" GETs "/beds/1"
    And the client POSTs a "forms.skipped" event for that request to "/_buster/events"
    And a client GETs "/_buster/report"
    Then the report shows a "pass (attested)" verdict for client "ARTIE/0.5"

  Scenario: Events appended to the client event file are read
    When client "trace-a" appends a "forms.skipped" line for that response to the client event file
    And Buster reads the client event file
    Then the verdict for "strip-integrity" is attested
