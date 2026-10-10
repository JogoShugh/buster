Feature: A sample plugin for any JSON API, nothing to do with H.E.A.R.T.
  Buster is a chaos filter for any HTTP server. The H.E.A.R.T. pack in plugins/
  is one set of plugins; samples/ holds plugins for ordinary servers. The
  flaky-503 sample turns a successful plain JSON response into 503 Service
  Unavailable with Retry-After, the way an overloaded server would. A client
  that retries the same request passes. A client that changes the resource
  it never managed to read fails.

  Background:
    Given only the "flaky-503" plugin from the samples
    And the dry run of that client sends DELETE /todos/1

  Scenario: A plain JSON 200 becomes a 503 with Retry-After
    When a plain JSON response for "/todos/1" goes out to client "app"
    Then the response went out as 503 with Retry-After "1" and Cache-Control "no-store"

  Scenario Outline: A client that <behaviour> gets <result>
    Given a plain JSON response for "/todos/1" goes out to client "app"
    When client "app" sends <requests>
    And the run ends
    Then the verdict for "flaky-503" is <result>

    Examples:
      | behaviour                               | requests        | result       |
      | retries the same request                | GET /todos/1    | pass         |
      | deletes the resource it never read      | DELETE /todos/1 | FAIL         |
      | moves on to something else              | GET /todos/2    | FAIL         |

  Scenario: A response the client never goes on to use is not worth failing
    Given the dry run of that client sends GET /todos/2
    When a plain JSON response for "/todos/1" goes out to client "app"
    Then the response went out unchanged

  Scenario: An error response is left alone
    When a 404 problem response for "/todos/9" goes out to client "app"
    Then the response went out unchanged

  Scenario: A H.E.A.R.T. response is not its business
    When an L1 Layer 1 response goes out to client "app"
    Then the response went out unchanged
