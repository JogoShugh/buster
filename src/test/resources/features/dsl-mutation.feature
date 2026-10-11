Feature: A mutation says what it changed, and the log is written for it
  The helpers that change a response record what they changed, so the detail in the log
  cannot drift from what the plugin did. header(name) replaces or removes a header,
  formsHref and formsLink read the forms link from a document, flipLetterInString changes one
  letter inside a string, and answerWithProblem turns a response into an RFC 9457 problem.

  Scenario: Replacing a header logs what it was and what it became
    Given the plugin script:
      """
      plugin("etag") {
          on(Layer.RESOURCE)
          mutate {
              header("ETag").replace("\"forged\"")
          }
      }
      """
    When an L1 Layer 1 response goes out to client "artie"
    Then the response header "ETag" is "\"forged\""
    And the logged mutation detail is "ETag \"s1\" became \"forged\""

  Scenario: Removing a header logs what it was
    Given the plugin script:
      """
      plugin("lie") {
          on(Layer.RESOURCE)
          mutate {
              header("Preference-Applied").remove() ?: skip("no Preference-Applied")
          }
      }
      """
    When an L1 Layer 1 response goes out to client "artie"
    Then the response has no header "Preference-Applied"
    And the logged mutation detail is "removed Preference-Applied: forms=link"

  Scenario: Removing a header that is not there says so, and the plugin can skip
    Given the plugin script:
      """
      plugin("lie") {
          on(Layer.RESOURCE)
          mutate {
              header("X-Nope").remove() ?: skip("no X-Nope")
          }
      }
      """
    When an L1 Layer 1 response goes out to client "artie"
    Then the response went out unchanged
    And "lie" was skipped because "no X-Nope"

  Scenario: The forms link is read from a document
    Given the plugin script:
      """
      plugin("read") {
          on(Layer.RESOURCE)
          mutate {
              val doc = jsonBody
              detail("forms link ${doc.formsHref} with integrity ${doc.formsLink?.path("integrity")?.asText()}")
          }
      }
      """
    When an L1 Layer 1 response goes out to client "artie"
    Then the logged mutation detail is "forms link /forms/abc with integrity abc"

  Scenario: A body that is not a JSON object skips the mutation
    Given the plugin script:
      """
      plugin("read") {
          on(Layer.OTHER)
          mutate {
              val doc = jsonBody
              detail("never reached")
          }
      }
      """
    When a plain text response for "/todos/1" goes out to client "app"
    Then the response went out unchanged
    And "read" was skipped because "body is not a JSON object"

  Scenario: One letter inside a string changes, and the log names the byte
    Given the plugin script:
      """
      plugin("flip") {
          on(Layer.AFFORDANCE_SET)
          mutate {
              flipLetterInString()
          }
      }
      """
    When an affordance set response goes out to client "artie"
    Then the response was changed
    And the logged mutation detail contains "byte "
    And the logged mutation detail contains "/forms/abc: '"

  Scenario: A response becomes a problem, and the log says which
    Given the plugin script:
      """
      plugin("flaky") {
          on(Layer.OTHER)
          mutate {
              answerWithProblem(503, "Service Unavailable", retryAfterSeconds = 1)
          }
      }
      """
    When a plain JSON response for "/todos/1" goes out to client "app"
    Then the response went out as 503 with Retry-After "1" and Cache-Control "no-store"
    And the logged mutation detail is "GET /todos/1 answered 503, Retry-After 1"
