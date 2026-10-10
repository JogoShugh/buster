Feature: Each mutation says what the client must do, judged from its next requests
  A plugin declares an expectation alongside its mutation. Buster watches the
  next requests from the same client and turns the expectation into a verdict:
  pass, fail, or inconclusive when nothing could prove the client right or wrong.
  So every plugin is also a conformance test.

  Background:
    Given a plugin "strip-integrity" that expects "refuse to follow the forms link" within 3 requests:
      | on a GET of | /forms/abc           |
      | it fails    | followed /forms/abc  |
      | at expiry   | never followed it    |
    And a Layer 1 response for "/beds/1" goes out to client "artie"

  Scenario Outline: <case>
    When client "<client>" sends <requests>
    Then the verdict for "strip-integrity" is <result> with reason "<reason>"

    Examples:
      | case                                    | client | requests                                    | result       | reason              |
      | the client never follows the link       | artie  | GET /beds/1, POST /beds/1/name, GET /beds/1 | pass         | never followed it   |
      | the client follows the link             | artie  | GET /beds/1, GET /forms/abc                 | FAIL         | followed /forms/abc |

  Scenario: Only requests from the same client count
    When client "other" sends GET /forms/abc, GET /forms/abc, GET /forms/abc
    Then "strip-integrity" has no verdict yet

  Scenario Outline: Ending the run closes open expectations
    When client "artie" sends <requests>
    And the run ends
    Then the verdict for "strip-integrity" is <result> with reason "<reason>"

    Examples:
      | requests     | result | reason            |
      | nothing      | pass   | never followed it |
      | GET /beds/1  | pass   | never followed it |

  # What silence means is the plugin's to say. Buster never decides it for the plugin.
  Scenario Outline: Silence is judged by the expectation's own rule, never by a shortcut
    Given a plugin "needs-the-act" that expects "make the act" within 3 requests:
      | on a GET of | /forms/abc |
      | it fails    | followed it |
      | at expiry   | never acted |
      | expiry is   | <rule>      |
    And a Layer 1 response for "/beds/1" goes out to client "artie"
    When client "artie" sends nothing
    And the run ends
    Then the verdict for "needs-the-act" is <result> with reason "never acted"

    Examples:
      | rule | result |
      | pass | pass   |
      | FAIL | FAIL   |

  Scenario: A verdict is logged with the mutation it judges
    When client "artie" sends GET /forms/abc
    Then the log has a verdict for sequence 1 by "strip-integrity" on "refuse to follow the forms link" with result "FAIL"

  Scenario: A plugin that skips leaves no expectation behind
    Given a plugin "skips-after-expecting" that expects "anything" and then skips
    When a Layer 1 response for "/beds/2" goes out to client "artie" using only that plugin
    And the run ends
    Then "skips-after-expecting" has no verdict
