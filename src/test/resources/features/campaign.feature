Feature: A campaign aims every mutation where the client will exercise the rule
  A dry run records what each client sends. The plan lists a mutation only where
  that client's later requests exercise the rule it attacks, grouped into rounds
  of one combination per client. Arming a round mutates exactly those exchanges,
  matched by the client's label and the request's position; everything else
  passes through untouched. A mutation that no client could exercise is never
  made, so no verdict is a guess.

  Scenario: Recording mutates nothing
    Given a campaign over the plugin pack
    When the campaign starts recording
    And client "alice" requests GET "/beds/1" and gets an L1 Layer 1
    Then the last response went out unchanged

  Scenario: The plan names a mutation only where the dry run exercises the rule
    Given a campaign over the plugin pack
    And the campaign starts recording
    And client "alice" requests GET "/beds/1" and gets an L1 Layer 1
    And client "alice" requests PUT "/beds/1/name" with the original ETag and gets an L1 Layer 1
    When the campaign is planned
    Then the plan arms "stale-etag" for client "alice" on request 1
    And the plan arms "relax-layer1-cache" for client "alice" on request 1
    And the plan does not arm "alias-forms-href" for client "alice"
    And the plan does not arm "strip-forms-integrity" for client "alice"

  Scenario: A client that follows the forms link arms the link plugins
    Given a campaign over the plugin pack
    And the campaign starts recording
    And client "alice" requests GET "/beds/1" and gets an L1 Layer 1
    And client "alice" requests GET the forms link and gets an affordance set
    When the campaign is planned
    Then the plan arms "strip-forms-integrity" for client "alice" on request 1
    And the plan arms "drop-curie" for client "alice" on request 1
    And the plan arms "lie-preference-applied" for client "alice" on request 1
    And the plan arms "alias-forms-href" for client "alice" on request 1
    And the plan does not arm "flip-byte" for client "alice"

  Scenario: flip-byte is armed only where the client acts using the set it fetched
    Given a campaign over the plugin pack
    And the campaign starts recording
    And client "alice" requests GET "/forms/sha256-abc" and gets an affordance set
    And client "alice" requests PUT "/beds/1/name" and gets an L1 Layer 1
    When the campaign is planned
    Then the plan arms "flip-byte" for client "alice" on request 1

  Scenario: drop-affordance drops the affordance the client acts on
    Given a campaign over the plugin pack
    And the campaign starts recording
    And client "alice" requests GET "/beds/1" and gets an L0 Layer 1
    And client "alice" requests PUT "/beds/1/name" and gets an L1 Layer 1
    When the campaign is planned
    Then the plan arms "drop-affordance" for client "alice" on request 1

  # A readiness probe checks that the server is up. It is not a client under test, and
  # arming it would mutate a check that has no rule to follow.
  Scenario: A readiness probe is left out of the plan
    Given a campaign over the plugin pack that ignores clients matching "^curl/"
    And the campaign starts recording
    And client "curl/8.4.0" requests GET "/beds/1" and gets an L1 Layer 1
    And client "alice" requests GET "/beds/1" and gets an L1 Layer 1
    When the campaign is planned
    Then the plan arms "relax-layer1-cache" for client "alice" on request 1
    And the plan does not arm "relax-layer1-cache" for client "curl/8.4.0"

  Scenario: Each round arms at most one combination for each client
    Given a campaign over the plugin pack
    And the campaign starts recording
    And client "alice" requests GET "/beds/1" and gets an L1 Layer 1
    And client "alice" requests PUT "/beds/1/name" with the original ETag and gets an L1 Layer 1
    And client "bob" requests GET "/beds/1" and gets an L1 Layer 1
    When the campaign is planned
    # alice's PUT answers with the bed too, but nothing reads the bed after it, so
    # relax-layer1-cache is not made there: there is no read to judge.
    Then the plan has 2 rounds
    And round 1 arms one combination each for "alice, bob"
    And round 2 arms one combination each for "alice"

  Scenario: Arming a round mutates only the exchanges it names
    Given a recorded dry run of alice and bob over the plugin pack
    When round 1 is armed
    And client "alice" requests GET "/beds/1" and gets an L1 Layer 1
    Then the last response went out mutated by "relax-layer1-cache"
    When client "bob" requests GET "/beds/1" and gets an L1 Layer 1
    Then the last response went out mutated by "relax-layer1-cache"

  Scenario: Unarmed clients and unplanned requests pass through untouched
    Given a recorded dry run of alice and bob over the plugin pack
    When round 1 is armed
    And client "carol" requests GET "/beds/1" and gets an L1 Layer 1
    Then the last response went out unchanged
    When client "alice" requests GET "/beds/1" and gets an L1 Layer 1
    And client "alice" requests GET "/beds/2" and gets an L1 Layer 1
    Then the last response went out unchanged

  Scenario: An armed expectation stays open until the client is done
    Given a recorded dry run of alice and bob over the plugin pack
    And round 1 is armed
    And client "alice" requests GET "/beds/1" and gets an L1 Layer 1
    When client "alice" reads 8 more other beds
    Then "relax-layer1-cache" has no verdict yet
    When client "alice" reports "client.done" for nothing
    Then the verdict for "relax-layer1-cache" is FAIL

  Scenario: Arming the next round closes the windows of the one before
    Given a recorded dry run of alice and bob over the plugin pack
    And round 1 is armed
    And client "alice" requests GET "/beds/1" and gets an L1 Layer 1
    When round 2 is armed
    Then the verdict for "relax-layer1-cache" is FAIL

  Scenario: A plugin with no combination is a coverage hole
    Given a campaign over the plugins "relax-layer1-cache, alias-forms-href"
    And the campaign starts recording
    And client "alice" requests GET "/beds/1" and gets an L1 Layer 1
    When the campaign is planned
    Then the plan has a coverage hole for "alias-forms-href"
    And the plan has no coverage hole for "relax-layer1-cache"
    And the campaign is not ok

  Scenario: A campaign is ok only with no FAIL and no hole
    Given a campaign over the plugins "relax-layer1-cache"
    And the campaign starts recording
    And client "alice" requests GET "/beds/1" and gets an L1 Layer 1
    And the campaign is planned
    And round 1 is armed
    When client "alice" requests GET "/beds/1" with no-cache and gets an L1 Layer 1
    And client "alice" reports "client.done" for nothing
    Then the verdict for "relax-layer1-cache" is pass
    And the campaign is ok

  Scenario: Any FAIL fails the campaign
    Given a campaign over the plugins "relax-layer1-cache"
    And the campaign starts recording
    And client "alice" requests GET "/beds/1" and gets an L1 Layer 1
    And the campaign is planned
    And round 1 is armed
    When client "alice" requests GET "/beds/1" and gets an L1 Layer 1
    And client "alice" reports "client.done" for nothing
    Then the verdict for "relax-layer1-cache" is FAIL
    And the campaign is not ok
