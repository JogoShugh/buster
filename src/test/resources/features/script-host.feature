Feature: Plugins are Kotlin scripts loaded from a folder at runtime
  A plugin is a *.buster.kts file in the plugins folder. There is no build step
  and no jar. The Buster DSL is the script's implicit receiver, and the core and
  DSL packages are imported by default. A script that fails is reported and
  skipped; the rest still load.

  Background:
    Given an empty plugins folder

  Scenario: One script may declare several plugins
    Given the script "pair.buster.kts":
      """
      plugin("left") {
          on(Layer.RESOURCE)
          mutate { detail("left") }
      }
      plugin("right") {
          on(Layer.AFFORDANCE_SET, Layer.SCHEMA)
          rungs(Rung.L2)
          mutate { detail("right") }
      }
      """
    When Buster loads the plugins folder
    Then the loaded plugins are "left, right"
    And no script failed

  Scenario: A plugin from a script mutates a response through the engine
    Given the script "rename.buster.kts":
      """
      plugin("rename-bed") {
          spec("§6.2")
          on(Layer.RESOURCE)
          mutate {
              val doc = json() ?: skip("not a JSON object")
              doc.put("name", "Chewed")
              writeJson(doc)
          }
      }
      """
    When Buster loads the plugins folder
    And a Layer 1 response goes out through the loaded plugins
    Then the body field "name" of that response is "Chewed"

  Scenario Outline: A broken script is reported and skipped, the others still load
    Given the script "good.buster.kts":
      """
      plugin("good") {
          on(Layer.RESOURCE)
          mutate { }
      }
      """
    And the script "broken.buster.kts":
      """
      <source>
      """
    When Buster loads the plugins folder
    Then the loaded plugins are "good"
    And "broken.buster.kts" failed with a message containing "<message>"

    Examples:
      | case                 | source                                  | message                  |
      | does not compile     | plugin("broken") { on(Layer.NOWHERE) }  | NOWHERE                  |
      | breaks a DSL rule    | plugin("broken") { on(Layer.RESOURCE) } | no mutate { } block      |
      | throws while it runs | error("chewed through the cable")       | chewed through the cable |
      | expires undecided    | plugin("broken") { on(Layer.RESOURCE); mutate { expect("x", 1) { onExpire { pending() } } } } | mismatch |

  Scenario: Files that are not plugin scripts are ignored
    Given the script "notes.kts":
      """
      plugin("ignored") { on(Layer.RESOURCE); mutate { } }
      """
    When Buster loads the plugins folder
    Then the loaded plugins are ""

  Scenario: Reloading compiles only what changed and drops what was deleted
    Given the script "stays.buster.kts":
      """
      plugin("stays") { on(Layer.RESOURCE); mutate { } }
      """
    And the script "changes.buster.kts":
      """
      plugin("before") { on(Layer.RESOURCE); mutate { } }
      """
    And the script "goes.buster.kts":
      """
      plugin("goes") { on(Layer.RESOURCE); mutate { } }
      """
    When Buster loads the plugins folder
    And the script "changes.buster.kts" becomes:
      """
      plugin("after") { on(Layer.RESOURCE); mutate { } }
      """
    And the script "goes.buster.kts" is deleted
    And Buster reloads the plugins folder
    Then the loaded plugins are "after, stays"
    And the reload compiled only "changes.buster.kts"
