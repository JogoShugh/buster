Feature: Buster classifies a response the way a client would
  Buster decides which H.E.A.R.T. layer and rung a response is before it picks
  a mutation. It reads only what a client can see: status, headers and body.
  It trusts Preference-Applied, not what the client asked for (SPEC §6.4, §6.11.1).
  Anything it cannot place is OTHER; only plugins that name OTHER touch it.

  Scenario Outline: <case>
    Given a response with status <status> and Content-Type "<content type>"
    And Cache-Control "<cache control>", ETag "<etag>" and Preference-Applied "<applied>"
    And the body is the "<body>" fixture
    When Buster classifies it
    Then the layer is <layer> and the rung is <rung>

    Examples:
      | case                                        | status | content type                | cache control                     | etag | applied                  | body                 | layer          | rung |
      | Layer 1 with everything embedded            | 200    | application/hal+json        | no-cache                          | s1   |                          | resource             | RESOURCE       | L0   |
      | Layer 1 with the set by link                | 200    | application/hal+json        | no-cache                          | s1   | forms=link               | resource             | RESOURCE       | L1   |
      | Layer 1 with set and schemas by link        | 200    | application/hal+json        | no-cache                          | s1   | forms=link, schema=link  | resource             | RESOURCE       | L2   |
      | schema=link alone is not a rung             | 200    | application/hal+json        | no-cache                          | s1   | schema=link              | resource             | RESOURCE       | L0   |
      | affordance set with embedded schemas        | 200    | application/hal+json        | public, max-age=31536000, immutable |      |                          | set-embedded-schemas | AFFORDANCE_SET | L1   |
      | affordance set with schema pointers         | 200    | application/hal+json        | public, max-age=31536000, immutable |      |                          | set-schema-pointers  | AFFORDANCE_SET | L2   |
      | schema                                      | 200    | application/schema+json     | public, max-age=31536000, immutable |      |                          | schema               | SCHEMA         | L2   |
      | error status                                | 404    | application/problem+json    | no-cache                          | s1   |                          | resource             | OTHER          | L0   |
      | not JSON                                    | 200    | text/html                   | no-cache                          | s1   |                          | resource             | OTHER          | L0   |
      | JSON without an ETag and not immutable      | 200    | application/json            | no-cache                          |      |                          | resource             | OTHER          | L0   |
      | the service index, JSON Home with an ETag   | 200    | application/json-home      | no-cache                          | s1   |                          | resource             | OTHER          | L0   |
      | plain JSON with an ETag is not HAL          | 200    | application/json            | no-cache                          | s1   |                          | resource             | OTHER          | L0   |
      | body that is not a JSON object              | 200    | application/hal+json        | no-cache                          | s1   |                          | array                | OTHER          | L0   |

  Scenario: A HAL-FORMS response is classified in the HAL-FORMS dialect
    Given a response with status 200 and Content-Type "application/prs.hal-forms+json"
    And Cache-Control "no-cache", ETag "s1" and Preference-Applied ""
    And the body is the "resource" fixture
    When Buster classifies it
    Then the dialect is HAL_FORMS
