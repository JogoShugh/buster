Feature: The engine picks a mutation, applies it, and makes the result cache-safe
  Buster changes responses only. A corrupted response must never reach a shared
  cache, so the engine, not the plugin author, sets Cache-Control: no-store.
  Choices come from the run's seed, so a run can be replayed exactly.

  Background:
    Given the seed is 42

  Scenario: A mutation is applied, forced to no-store, and logged
    Given a plugin "rename-bed" on RESOURCE that sets the body field "name" to "Chewed"
    When a Layer 1 response for "/beds/1" goes out
    Then the body field "name" is "Chewed"
    And Cache-Control is "no-store"
    And the log has a mutation by "rename-bed" on "/beds/1" with seed 42 and sequence 1

  Scenario: The engine overrides a plugin that tries to make corrupted bytes cacheable
    Given a plugin "sneaky" on RESOURCE that sets the body field "name" to "Chewed" and Cache-Control "public, max-age=31536000, immutable"
    When a Layer 1 response for "/beds/1" goes out
    Then Cache-Control is "no-store"

  Scenario Outline: A plugin keeps its Cache-Control only while the body is unchanged
    Given a plugin "relax" on RESOURCE that sets Cache-Control "private, max-age=3600" and keeps it, <body change>
    When a Layer 1 response for "/beds/1" goes out
    Then Cache-Control is "<cache control>"

    Examples:
      | body change                           | cache control         |
      | leaving the body alone                | private, max-age=3600 |
      | and sets the body field "name" to "x" | no-store              |

  Scenario: A response the classifier cannot place is left to plugins that name OTHER
    Given a plugin "rename-bed" on RESOURCE that sets the body field "name" to "Chewed"
    When a 404 problem response for "/beds/9" goes out
    Then the response is unchanged
    And the log has no mutations

  # Buster is not specific to H.E.A.R.T. The classifier places H.E.A.R.T. responses;
  # everything else is OTHER, and a plugin for any other kind of server names OTHER.
  Scenario: A plugin that names OTHER can mutate a response from any server
    Given a plugin "chew-anything" on OTHER that sets the body field "title" to "Chewed"
    When a 404 problem response for "/beds/9" goes out
    Then the body field "title" is "Chewed"
    And Cache-Control is "no-store"

  Scenario: A plugin only sees the layers it names
    Given a plugin "chew-set" on AFFORDANCE_SET that sets the body field "name" to "Chewed"
    When a Layer 1 response for "/beds/1" goes out
    Then the response is unchanged

  Scenario: A plugin that skips leaves the response alone and says why
    Given a plugin "picky" on RESOURCE that skips because "nothing to chew"
    When a Layer 1 response for "/beds/1" goes out
    Then the response is unchanged
    And the log notes that "picky" skipped because "nothing to chew"

  Scenario: The same seed makes the same choices, a different seed different ones
    Given a plugin "left" on RESOURCE that sets the body field "name" to "L"
    And a plugin "right" on RESOURCE that sets the body field "name" to "R"
    When 20 Layer 1 responses go out with seed 42, twice
    Then both runs chose the same plugins in the same order
    And a run with seed 7 chose a different order
