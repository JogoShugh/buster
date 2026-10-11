Feature: The first plugin pack attacks the client rules the spec names
  The pack is the *.buster.kts scripts in the plugins folder. Each script
  changes a response the way one spec rule forbids a server to, and declares
  what a conforming client must do next. A client that follows the rule
  passes; a client that breaks it fails.

  Scenario: The pack loads cleanly
    When Buster loads the plugin pack
    Then the pack has the plugins "alias-forms-href, drop-affordance, drop-curie, flip-byte, lie-preference-applied, relax-layer1-cache, stale-etag, strip-forms-integrity"

  Scenario Outline: <plugin> changes <response>: <change>
    Given only the "<plugin>" plugin from the pack
    And the dry run of that client sends GET the forms link, PUT /beds/1/name with the original ETag
    And <response> response goes out to client "artie"
    Then <change>

    Examples:
      | plugin                 | response          | change                                                   |
      | strip-forms-integrity  | an L1 Layer 1     | the forms link has no integrity                          |
      | drop-curie             | an L1 Layer 1     | the links have no curies                                 |
      | stale-etag             | an L1 Layer 1     | the ETag is no longer "s1"                               |
      | relax-layer1-cache     | an L1 Layer 1     | the response keeps Cache-Control "private, max-age=3600" |
      | lie-preference-applied | an L1 Layer 1     | the response has no Preference-Applied                   |
      | alias-forms-href       | an L1 Layer 1     | the forms link has a query the server did not write      |
      | drop-affordance        | an L0 Layer 1     | the affordance "rename" is gone                          |
      | flip-byte              | an affordance set | the body is still JSON but one character differs         |
      | flip-byte              | a schema          | the body is still JSON but one character differs         |

  Scenario Outline: <plugin>: a client that <behaviour> gets <result>
    Given only the "<plugin>" plugin from the pack
    And the dry run of that client sends GET the forms link, PUT /beds/1/name with the original ETag
    And <response> response goes out to client "artie"
    When client "artie" sends <requests>
    And the run ends
    Then the verdict for "<plugin>" is <result>

    Examples:
      | plugin                 | response          | behaviour                         | requests                                                                       | result       |
      | strip-forms-integrity  | an L1 Layer 1     | refuses the link                  | GET /beds/1, GET /beds/2                                                       | pass         |
      | strip-forms-integrity  | an L1 Layer 1     | follows the link                  | GET /forms/sha256-abc                                                          | FAIL         |
      | drop-curie             | an L1 Layer 1     | treats hr:forms as unknown        | GET /beds/1, GET /beds/2                                                       | pass         |
      | drop-curie             | an L1 Layer 1     | follows hr:forms anyway           | GET /forms/sha256-abc                                                          | FAIL         |
      | stale-etag             | an L1 Layer 1     | revalidates before acting         | GET /beds/1, PUT /beds/1/name                                                  | pass         |
      | stale-etag             | an L1 Layer 1     | gets 412, then re-syncs           | PUT /beds/1/name with the mutated ETag, GET /beds/1                            | pass         |
      | stale-etag             | an L1 Layer 1     | resubmits the stale act           | PUT /beds/1/name with the mutated ETag, PUT /beds/1/name with the mutated ETag | FAIL         |
      | stale-etag             | an L1 Layer 1     | never makes the act it made unmutated | nothing                                                                    | FAIL         |
      | relax-layer1-cache     | an L1 Layer 1     | re-reads it, saying no-cache      | GET /beds/1 with no-cache                                                      | pass         |
      | relax-layer1-cache     | an L1 Layer 1     | re-reads it without no-cache      | GET /beds/1                                                                    | FAIL         |
      | relax-layer1-cache     | an L1 Layer 1     | acts right after reading it       | PUT /beds/1/name                                                               | FAIL         |
      | relax-layer1-cache     | a no-cache L1 Layer 1 | read it with no-cache, then reads another | GET /beds/2                                                        | pass         |
      | relax-layer1-cache     | a no-cache L1 Layer 1 | read it with no-cache, then stops | nothing                                                                    | pass         |
      | relax-layer1-cache     | an L1 Layer 1     | read it without no-cache, then stops | nothing                                                                     | FAIL         |
      | lie-preference-applied | an L1 Layer 1     | reads L0 and finds no affordances | GET /beds/2                                                                    | pass         |
      | lie-preference-applied | an L1 Layer 1     | follows the forms link anyway     | GET /forms/sha256-abc                                                          | FAIL         |
      | lie-preference-applied | an L1 Layer 1     | acts with no affordances          | PUT /beds/1/name                                                               | FAIL         |
      | alias-forms-href       | an L1 Layer 1     | follows the link it was given     | GET the forms link                                                             | pass         |
      | alias-forms-href       | an L1 Layer 1     | builds the URL itself             | GET /forms/sha256-abc                                                          | FAIL         |
      | drop-affordance        | an L0 Layer 1     | never acts on the dropped one     | GET /beds/1                                                                    | pass         |
      | drop-affordance        | an L0 Layer 1     | acts on the dropped one anyway    | PUT /beds/1/name                                                               | FAIL         |
      | flip-byte              | an affordance set | refetches the set before acting   | GET /forms/sha256-abc, PUT /beds/1/name                                        | pass         |
      | flip-byte              | an affordance set | acts from the corrupted set       | PUT /beds/1/name                                                               | FAIL         |
      | flip-byte              | an affordance set | never uses the set, nor refetches it | nothing                                                                     | pass         |
      | alias-forms-href       | an L1 Layer 1     | never follows the link            | nothing                                                                        | FAIL         |
      | flip-byte              | a schema          | refetches the schema              | GET /schemas/sha256-def                                                        | pass         |
      | flip-byte              | a schema          | acts with the corrupted schema    | PUT /beds/1/name                                                               | FAIL         |

  Scenario Outline: <plugin>: a client whose log says <event> gets an attested pass
    Some right behaviour sends nothing. ARTIE's event log says what it
    decided, tied by trace and span to the request the decision was about;
    that can turn silence into an attested pass, never a failure into one.
    Given only the "<plugin>" plugin from the pack
    And the dry run of that client sends GET the forms link, PUT /beds/1/name with the original ETag
    And <response> response goes out to client "artie"
    When client "artie" reports "<event>" for <subject>
    Then the verdict for "<plugin>" is attested

    Examples:
      | plugin                 | response          | event             | subject           |
      | lie-preference-applied | an L1 Layer 1     | forms.skipped     | that response     |
      | drop-curie             | an L1 Layer 1     | forms.missing     | that response     |
      | flip-byte              | an affordance set | integrity.refused | that response     |
      | flip-byte              | a schema          | integrity.refused | that response     |
      | strip-forms-integrity  | an L1 Layer 1     | integrity.refused | the forms link    |

  Scenario: relax-layer1-cache leaves the service index alone
    The service index is a JSON Home document, not a Layer 1 resource, so the rule
    that Layer 1 is never fresh is not about it.
    Given only the "relax-layer1-cache" plugin from the pack
    And a service index response goes out to client "artie"
    Then the response went out unchanged

  Scenario: drop-affordance: a client whose log says it found the dropped affordance gone gets an attested pass
    After a 412 a client re-reads the resource, finds the affordance gone and drops
    its act. The event names the relation and the span of the re-read, not of the
    response that was mutated.
    Given only the "drop-affordance" plugin from the pack
    And the dry run of that client sends PUT /beds/1/name
    And an L0 Layer 1 response goes out to client "artie"
    When client "artie" finds the dropped affordance "rename" gone on a re-read, and reports "affordance.absent"
    Then the verdict for "drop-affordance" is attested

  Scenario: drop-affordance: an event about another relation changes nothing
    Given only the "drop-affordance" plugin from the pack
    And the dry run of that client sends PUT /beds/1/name
    And an L0 Layer 1 response goes out to client "artie"
    When client "artie" finds the dropped affordance "harvest" gone on a re-read, and reports "affordance.absent"
    Then "drop-affordance" has no verdict yet

  # An act answers at the action URL, but its response is the resource's new state.
  # Content-Location (RFC 9110 §8.7) names the resource, the HAL self link says it too.
  # Reading the action URL as "the resource" would make a plugin wait for a GET of a
  # URL no client reads, and judge a write as if it were a read.
  Scenario Outline: <plugin>: an act's response is judged by the resource it names, not the action URL
    Given only the "<plugin>" plugin from the pack
    And the dry run of that client sends <future>
    And <response> goes out to client "artie"
    When client "artie" sends <requests>
    And the run ends
    Then the verdict for "<plugin>" is <result>

    Examples:
      | plugin                 | future                                   | response                               | requests                                            | result |
      | stale-etag             | PUT /beds/1/name with the original ETag  | an L1 act response naming "/beds/1"    | PUT /beds/1/name with the mutated ETag, GET /beds/1 | pass   |
      | stale-etag             | PUT /beds/1/name with the original ETag  | an L1 act response with only a self link | PUT /beds/1/name with the mutated ETag, GET /beds/1 | pass   |
      | relax-layer1-cache     | GET /beds/1                              | an L1 act response naming "/beds/1"    | GET /beds/1 with no-cache                           | pass   |
      | relax-layer1-cache     | GET /beds/1                              | an L1 act response naming "/beds/1"    | GET /beds/1                                         | FAIL   |
      | relax-layer1-cache     | GET /beds/1                              | an L1 act response naming "/beds/1"    | nothing                                             | pass   |
      | lie-preference-applied | GET the forms link                       | an L1 act response naming "/beds/1"    | GET /beds/1, PUT /beds/1/name                       | pass   |

  # Buster sends no-store with a mutation, so the client keeps nothing. If the client enters
  # the resource again, it gets a whole, unmutated representation, and what it does with
  # that is its own business. The mutated one is gone; judging the client on the fresh one
  # would fail a client for doing exactly what it should.
  Scenario Outline: <plugin>: a fresh, unmutated representation of the resource replaces the mutated one
    Given only the "<plugin>" plugin from the pack
    And the dry run of that client sends GET the forms link
    And an L1 Layer 1 response goes out to client "artie"
    When client "artie" is answered an unmutated representation of "/beds/1"
    And client "artie" sends GET the forms link
    And the run ends
    Then the verdict for "<plugin>" is pass

    Examples:
      | plugin                 |
      | drop-curie             |
      | lie-preference-applied |
      | strip-forms-integrity  |
      | alias-forms-href       |

  # An act's 2xx answer is a whole representation of the resource it names (RFC 9110 §8.7),
  # as a GET's answer is. A client given the resource again, by an act, holds a manifest
  # that may list the dropped act, and acting on it is then what §6.2 asks.
  Scenario: drop-affordance: an act's unmutated answer that names the resource replaces the mutated one
    Given only the "drop-affordance" plugin from the pack
    And the dry run of that client sends PUT /beds/1/name
    And an L0 Layer 1 response goes out to client "artie"
    When client "artie" is answered an unmutated act response naming "/beds/1"
    And client "artie" sends PUT /beds/1/name
    And the run ends
    Then the verdict for "drop-affordance" is pass

  Scenario: drop-affordance: an act's answer that names another resource changes nothing
    Given only the "drop-affordance" plugin from the pack
    And the dry run of that client sends PUT /beds/1/name
    And an L0 Layer 1 response goes out to client "artie"
    When client "artie" is answered an unmutated act response naming "/beds/2"
    And client "artie" sends PUT /beds/1/name
    Then the verdict for "drop-affordance" is FAIL

  Scenario: Following the link before the fresh representation arrives still FAILs
    Given only the "drop-curie" plugin from the pack
    And the dry run of that client sends GET the forms link
    And an L1 Layer 1 response goes out to client "artie"
    When client "artie" sends GET the forms link
    And client "artie" is answered an unmutated representation of "/beds/1"
    Then the verdict for "drop-curie" is FAIL

  Scenario: Another client's fresh representation changes nothing
    Given only the "drop-curie" plugin from the pack
    And the dry run of that client sends GET the forms link
    And an L1 Layer 1 response goes out to client "artie"
    When client "someone-else" is answered an unmutated representation of "/beds/1"
    Then "drop-curie" has no verdict yet

  Scenario: relax-layer1-cache is not made on an act's response nobody reads again
    An act is a write, so it carries no Cache-Control directive to judge. The rule is
    about reading the resource again, so it is made only where the client does.
    Given only the "relax-layer1-cache" plugin from the pack
    And the dry run of that client sends PUT /beds/1/name
    And an L1 act response naming "/beds/1" goes out to client "artie"
    Then the response went out unchanged
