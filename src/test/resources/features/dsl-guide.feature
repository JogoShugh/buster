Feature: The guide to writing plugins shows only plugins that load
  docs/DSL.md teaches the DSL with examples. An example that no longer compiles would
  teach a plugin author something false, so every complete plugin in the guide is
  loaded as a script, and the guide must show at least a handful.

  Scenario: Every plugin example in the guide loads
    Then every plugin example in "docs/DSL.md" loads, and there are at least 8

  Scenario: The README points at the guide
    Then the README links to "docs/DSL.md"
