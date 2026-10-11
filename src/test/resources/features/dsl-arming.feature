Feature: A plugin says what it needs from the client's dry run with matchers, not with loops
  A matcher is a value: get("/forms/x"), act(ifMatch = etag), anyAct. The same matcher
  arms a mutation (onlyWhere(client does it)) and judges what the client does next, so the
  two cannot drift apart. silenceMeans says whether a client that does nothing passes or
  FAILs; a plugin whose silence is a FAIL is never made without a dry run, and without
  onlyWhere it is not made at all.

  Scenario: onlyWhere keeps the mutation where the client does the action
    Given the plugin script:
      """
      plugin("rename") {
          on(Layer.RESOURCE)
          mutate {
              onlyWhere(client does get("/beds/2"))
              val doc = json() ?: skip("not a JSON object")
              doc.put("name", "Chewed")
              writeJson(doc)
          }
      }
      """
    And the dry run of that client sends GET /beds/2
    When an L1 Layer 1 response goes out to client "artie"
    Then the response was changed

  Scenario: onlyWhere skips, and says which action the client never does
    Given the plugin script:
      """
      plugin("rename") {
          on(Layer.RESOURCE)
          mutate {
              onlyWhere(client does get("/beds/2"))
              val doc = json() ?: skip("not a JSON object")
              doc.put("name", "Chewed")
              writeJson(doc)
          }
      }
      """
    And the dry run of that client sends GET /beds/3
    When an L1 Layer 1 response goes out to client "artie"
    Then the response went out unchanged
    And "rename" was skipped because "the client never does GET /beds/2 in its dry run"

  Scenario: Without a dry run, onlyWhere does not hold a mutation back, and silence is a pass
    Given the plugin script:
      """
      plugin("rename") {
          on(Layer.RESOURCE)
          mutate {
              onlyWhere(client does get("/beds/2"))
              val doc = json() ?: skip("not a JSON object")
              doc.put("name", "Chewed")
              writeJson(doc)
          }
      }
      """
    When an L1 Layer 1 response goes out to client "artie"
    Then the response was changed

  Scenario: A plugin whose silence is a FAIL is not made without a dry run
    Given the plugin script:
      """
      plugin("rename") {
          on(Layer.RESOURCE)
          silenceMeans(Silence.FAIL)
          mutate {
              onlyWhere(client does get("/beds/2"))
              val doc = json() ?: skip("not a JSON object")
              doc.put("name", "Chewed")
              writeJson(doc)
          }
      }
      """
    When an L1 Layer 1 response goes out to client "artie"
    Then the response went out unchanged
    And "rename" was skipped because "no dry run, so silence would be a guess"

  Scenario: A plugin whose silence is a FAIL and that never says what it needs is not made
    Given the plugin script:
      """
      plugin("rename") {
          on(Layer.RESOURCE)
          silenceMeans(Silence.FAIL)
          mutate {
              val doc = json() ?: skip("not a JSON object")
              doc.put("name", "Chewed")
              writeJson(doc)
          }
      }
      """
    And the dry run of that client sends GET /beds/2
    When an L1 Layer 1 response goes out to client "artie"
    Then the response went out unchanged
    And "rename" was skipped because "silenceMeans(FAIL) but mutate never called onlyWhere"

  Scenario: The matcher that armed the mutation judges the client
    Given the plugin script:
      """
      plugin("watch") {
          on(Layer.RESOURCE)
          mutate {
              val follows = get("/beds/2")
              onlyWhere(client does follows)
              detail("watching for $follows")
              expect("do not read /beds/2", window = 3) {
                  onRequest { req -> if (follows.matches(req)) fail("read /beds/2") else pending() }
                  onExpire { pass("never read it") }
              }
          }
      }
      """
    And the dry run of that client sends GET /beds/2
    And an L1 Layer 1 response goes out to client "artie"
    When client "artie" sends GET /beds/2
    Then the verdict for "watch" is FAIL

  Scenario Outline: <matcher> arms a mutation when the dry run holds <dry run>
    Given the plugin script:
      """
      plugin("rename") {
          on(Layer.RESOURCE)
          mutate {
              onlyWhere(client does <matcher>)
              val doc = json() ?: skip("not a JSON object")
              doc.put("name", "Chewed")
              writeJson(doc)
          }
      }
      """
    And the dry run of that client sends <dry run>
    When an L1 Layer 1 response goes out to client "artie"
    Then the response <outcome>

    Examples:
      | matcher                              | dry run                                  | outcome                |
      | anyAct                               | PUT /beds/1/name                         | was changed            |
      | anyAct                               | GET /beds/1                              | went out unchanged     |
      | act(ifMatch = "\"s1\"")              | PUT /beds/1/name with the original ETag  | was changed            |
      | act(ifMatch = "\"s2\"")              | PUT /beds/1/name with the original ETag  | went out unchanged     |
      | anyOf(get("/beds/9"), anyAct)        | PUT /beds/1/name                         | was changed            |

  Scenario Outline: act(on = form) matches the acts an embedded affordance allows
    Given the plugin script:
      """
      plugin("rename") {
          on(Layer.RESOURCE)
          mutate {
              val form = Forms.embedded(json() ?: skip("not a JSON object"))?.get("rename") ?: skip("no rename form")
              onlyWhere(client does act(on = form))
              val doc = json() ?: skip("not a JSON object")
              doc.put("name", "Chewed")
              writeJson(doc)
          }
      }
      """
    And the dry run of that client sends <dry run>
    When an L0 Layer 1 response goes out to client "artie"
    Then the response <outcome>

    Examples:
      | dry run            | outcome            |
      | PUT /beds/1/name   | was changed        |
      | PUT /beds/1/colour | went out unchanged |

  Scenario Outline: A matcher can ask for a request directive
    Given the plugin script:
      """
      plugin("watch") {
          on(Layer.RESOURCE)
          mutate {
              val noCache = get("/beds/2").with(cacheControl = "no-cache")
              expect("re-read with no-cache", window = 3) {
                  onRequest { req -> if (noCache.matches(req)) pass("re-read with no-cache") else pending() }
                  onExpire { fail("never re-read with no-cache") }
              }
          }
      }
      """
    And an L1 Layer 1 response goes out to client "artie"
    When client "artie" sends <request>
    And the run ends
    Then the verdict for "watch" is <result>

    Examples:
      | request                       | result |
      | GET /beds/2 with no-cache     | pass   |
      | GET /beds/2                   | FAIL   |
