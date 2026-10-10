Feature: Buster runs as the first WebFilter in a Spring WebFlux server
  Buster sits in front of every response, so no endpoint can opt out. It buffers
  the body, lets the engine mutate it, and writes it out with a correct length.
  Every request reaches the verifier first. It runs only under the buster
  profile, so it cannot run in production by accident.

  Background:
    Given a WebFlux server whose "/beds/1" answers a Layer 1 bed
    And whose "/missing" answers 404 with a problem body

  Scenario: A Layer 1 response is mutated on its way out
    Given Buster runs with a plugin that renames every bed to "Chewed"
    When a client GETs "/beds/1"
    Then the client receives a bed named "Chewed"
    And the response has Cache-Control "no-store"
    And the response's Content-Length matches its body

  Scenario: A response Buster cannot place passes through untouched
    Given Buster runs with a plugin that renames every bed to "Chewed"
    When a client GETs "/missing"
    Then the client receives exactly what the server wrote

  Scenario: Requests reach the verifier, and the report shows the verdict
    Given Buster runs with a plugin that expects no GET of "/forbidden"
    When client "artie" GETs "/beds/1"
    And client "artie" GETs "/forbidden"
    And a client GETs "/_buster/report"
    Then the report shows a FAIL verdict for client "artie"

  Scenario: Ending the run from the report closes open expectations
    Given Buster runs with a plugin that expects no GET of "/forbidden"
    When client "artie" GETs "/beds/1"
    And a client GETs "/_buster/report?flush=true"
    Then the report shows an inconclusive verdict for client "artie"

  Scenario: The report lists each mutation as it happens
    Given Buster runs with a plugin that renames every bed to "Chewed"
    When client "artie" GETs "/beds/1"
    And a client GETs "/_buster/report"
    Then the report lists mutation 1 by "rename" on "/beds/1" for client "artie"

  # The live view is htmx: the shell polls the report, and the server renders the rows.
  Scenario: A live view of the report, for a browser
    Given Buster runs with a plugin that renames every bed to "Chewed"
    When a client GETs "/_buster"
    Then the client receives an HTML representation that polls "/_buster/report" with htmx
    And the response has Cache-Control "no-store"

  Scenario: htmx gets the report as HTML rows
    Given Buster runs with a plugin that expects no GET of "/forbidden"
    When client "artie" GETs "/beds/1"
    And client "artie" GETs "/forbidden"
    And htmx GETs "/_buster/report"
    Then the client receives an HTML fragment with a "FAIL" verdict for plugin "forbid"
    And the response has Cache-Control "no-store"

  Scenario: The report escapes what it shows
    Given Buster runs with a plugin that notes "<script>alert(1)</script>"
    When a client GETs "/beds/1"
    And htmx GETs "/_buster/report"
    Then the HTML fragment shows "&lt;script&gt;" and never "<script>"

  # Slow motion: a pace holds every mutated response before it goes out, so a person
  # can watch a run case by case in the live view.
  Scenario: A pace holds each mutated response
    Given Buster paces mutations at "300ms"
    And Buster runs with a plugin that renames every bed to "Chewed"
    When a client GETs "/beds/1"
    Then the client receives a bed named "Chewed"
    And the response took at least 300 ms

  Scenario: A pace does not hold a response Buster leaves alone
    Given Buster paces mutations at "300ms"
    And Buster runs with a plugin that renames every bed to "Chewed"
    When a client GETs "/missing"
    Then the response took less than 300 ms

  Scenario: The pace comes from the buster.pace property
    Given a Spring context with profiles "buster", the plugin pack folder and "buster.pace=2s"
    Then the Buster filter holds each mutation for 2000 ms

  Scenario: The filter runs before every other filter
    Given Buster runs with a plugin that renames every bed to "Chewed"
    Then the Buster filter has the highest precedence

  Scenario Outline: Buster exists only under the buster profile
    Given a Spring context with profiles "<profiles>" and the plugin pack folder
    Then the context <has> a Buster filter

    Examples:
      | profiles   | has         |
      | buster     | has         |
      | dev,buster | has         |
      |            | does not have |
      | prod       | does not have |
