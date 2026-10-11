Feature: An expectation is a list of rules, not a function over requests
  Inside expect, seeing(matcher) says what a request means, logs(name) says what a line of the
  client's event log means, and silence says what doing nothing means. The first rule that
  matches a request decides it. already(matcher) asks whether the client did that earlier in
  the window, so a plugin keeps no flags of its own. onRequest and onExpire stay for a rule
  these cannot say.

  Scenario Outline: seeing(matcher) says what a request means, and silence says what none means
    Given the plugin script:
      """
      plugin("watch") {
          on(Layer.RESOURCE)
          mutate {
              expect("read the right bed", window = 3) {
                  seeing(get("/beds/2")) fails "read it"
                  seeing(get("/beds/3")) passes "read that"
                  silence passes "left it"
              }
          }
      }
      """
    And an L1 Layer 1 response goes out to client "artie"
    When client "artie" sends <requests>
    And the run ends
    Then the verdict for "watch" is <result> with reason "<reason>"

    Examples:
      | requests     | result | reason    |
      | GET /beds/2  | FAIL   | read it   |
      | GET /beds/3  | pass   | read that |
      | GET /beds/4  | pass   | left it   |
      | nothing      | pass   | left it   |

  Scenario: A reason can be written from the request that settled it
    Given the plugin script:
      """
      plugin("watch") {
          on(Layer.RESOURCE)
          mutate {
              expect("make no act", window = 3) {
                  seeing(anyAct) fails { "acted on ${it.path}" }
                  silence passes "made no act"
              }
          }
      }
      """
    And an L1 Layer 1 response goes out to client "artie"
    When client "artie" sends PUT /beds/1/name
    Then the verdict for "watch" is FAIL with reason "acted on /beds/1/name"

  # What the client did earlier in the window is asked for, never kept in a flag.
  Scenario Outline: already(matcher) tells what came before
    Given the plugin script:
      """
      plugin("stale") {
          on(Layer.RESOURCE)
          silenceMeans(Silence.FAIL)
          mutate {
              val original = response.headers["ETag"] ?: skip("no ETag")
              onlyWhere(client does act(ifMatch = original))
              response.headers["ETag"] = "\"forged\""
              expect("discard the act on 412 and re-sync; never resubmit it", window = 5) {
                  val resubmit = act(ifMatch = "\"forged\"")
                  seeing(resubmit) {
                      if (already(resubmit)) fail("resubmitted an act with the stale ETag") else pending()
                  }
                  seeing(get(resource)) passes { if (already(resubmit)) "re-synced after 412" else "revalidated before acting" }
                  silence fails { if (already(resubmit)) "did not re-sync after 412" else "never made the act it made unmutated" }
              }
          }
      }
      """
    And the dry run of that client sends PUT /beds/1/name with the original ETag
    And an L1 Layer 1 response goes out to client "artie"
    When client "artie" sends <requests>
    And the run ends
    Then the verdict for "stale" is <result> with reason "<reason>"

    Examples:
      | requests                                                                | result | reason                                |
      | PUT /beds/1/name with the mutated ETag, GET /beds/1                     | pass   | re-synced after 412                   |
      | GET /beds/1                                                             | pass   | revalidated before acting             |
      | PUT /beds/1/name with the mutated ETag, PUT /beds/1/name with the mutated ETag | FAIL | resubmitted an act with the stale ETag |
      | PUT /beds/1/name with the mutated ETag                                  | FAIL   | did not re-sync after 412             |
      | nothing                                                                 | FAIL   | never made the act it made unmutated  |

  Scenario: A whole, unmutated representation closes the window, as a rule
    Given the plugin script:
      """
      plugin("watch") {
          on(Layer.RESOURCE)
          rungs(Rung.L1)
          mutate {
              expect("make no act", window = 3) {
                  seeing(anyAct) fails { "acted on ${it.path}" }
                  superseded passes "was given the resource again before acting"
                  silence passes "made no act"
              }
          }
      }
      """
    And an L1 Layer 1 response goes out to client "artie"
    When client "artie" is answered an unmutated representation of "/beds/1"
    And client "artie" sends PUT /beds/1/name
    Then the verdict for "watch" is pass with reason "was given the resource again before acting"

  Scenario Outline: logs(name) says what a line of the client's log means
    Given the plugin script:
      """
      plugin("watch") {
          on(Layer.RESOURCE)
          mutate {
              expect("say so", window = 3) {
                  seeing(<narrowed>) attests "its log says it did"
                  silence passes "said nothing"
              }
          }
      }
      """
    And an L1 Layer 1 response goes out to client "artie"
    When client "artie" reports "integrity.refused" for <subject>
    And the run ends
    Then the verdict for "watch" is <result>

    Examples:
      | narrowed                                                | subject          | result   |
      | logs("integrity.refused")                               | that response    | attested |
      | logs("integrity.refused").thisResponse                  | that response    | attested |
      | logs("integrity.refused").thisResponse                  | another response | pass     |
      | logs("integrity.refused").about("/beds/1")              | another response | attested |
      | logs("integrity.refused").about("/beds/9")              | another response | pass     |
      | logs("forms.skipped")                                   | that response    | pass     |

  Scenario Outline: logs(name).with(key, value) narrows by an attribute
    Given the plugin script:
      """
      plugin("watch") {
          on(Layer.RESOURCE)
          mutate {
              expect("say so", window = 3) {
                  seeing(logs("affordance.absent").with("heart.rel", "<rel>")) attests "its log says it found it gone"
                  silence passes "said nothing"
              }
          }
      }
      """
    And an L1 Layer 1 response goes out to client "artie"
    When client "artie" finds the dropped affordance "rename" gone on a re-read, and reports "affordance.absent"
    And the run ends
    Then the verdict for "watch" is <result>

    Examples:
      | rel    | result   |
      | rename | attested |
      | water  | pass     |
