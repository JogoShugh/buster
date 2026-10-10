Feature: The first plugin pack attacks the client rules the spec names
  The pack is the *.buster.kts scripts in the plugins folder. Each script
  changes a Layer 1 response the way one spec rule forbids a server to, and
  declares what a conforming client must do next. A client that follows the
  rule passes; a client that breaks it fails.

  Scenario: The pack loads cleanly
    When Buster loads the plugin pack
    Then the pack has the plugins "drop-curie, relax-layer1-cache, stale-etag, strip-forms-integrity"

  Scenario Outline: <plugin> changes Layer 1: <change>
    Given only the "<plugin>" plugin from the pack
    When an L1 Layer 1 response for "/beds/1" goes out to client "artie"
    Then <change>

    Examples:
      | plugin                | change                                           |
      | strip-forms-integrity | the forms link has no integrity                  |
      | drop-curie            | the links have no curies                         |
      | stale-etag            | the ETag is no longer "s1"                       |
      | relax-layer1-cache    | the response keeps Cache-Control "private, max-age=3600" |

  Scenario Outline: <plugin>: a client that <behaviour> gets <result>
    Given only the "<plugin>" plugin from the pack
    And an L1 Layer 1 response for "/beds/1" goes out to client "artie"
    When client "artie" sends <requests>
    And the run ends
    Then the verdict for "<plugin>" is <result>

    Examples:
      | plugin                | behaviour                              | requests                                                                   | result       |
      | strip-forms-integrity | refuses the link                       | GET /beds/1, GET /beds/2                                                   | pass         |
      | strip-forms-integrity | follows the link                       | GET /forms/sha256-abc                                                      | FAIL         |
      | drop-curie            | treats hr:forms as unknown             | GET /beds/1, GET /beds/2                                                   | pass         |
      | drop-curie            | follows hr:forms anyway                | GET /forms/sha256-abc                                                      | FAIL         |
      | stale-etag            | revalidates before acting              | GET /beds/1, PUT /beds/1/name                                              | pass         |
      | stale-etag            | gets 412, then re-syncs                | PUT /beds/1/name with the mutated ETag, GET /beds/1                        | pass         |
      | stale-etag            | resubmits the stale act                | PUT /beds/1/name with the mutated ETag, PUT /beds/1/name with the mutated ETag | FAIL     |
      | stale-etag            | never acts                             | nothing                                                                    | inconclusive |
      | relax-layer1-cache    | revalidates before acting              | GET /beds/1, PUT /beds/1/name                                              | pass         |
      | relax-layer1-cache    | acts from its cached copy              | PUT /beds/1/name                                                           | FAIL         |
