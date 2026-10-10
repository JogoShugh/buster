# Buster

**Basically Unstoppable Saboteur Terrierizing Every Representation**

Buster is a chaos filter for H.E.A.R.T. servers. It sits in front of a server's
responses and tampers with them on purpose. Its job is to prove that ARTIE,
the reference client, follows the 0.5 spec when the server misbehaves. It is
named after a Russell terrier who chews remotes, bites ankles, and jumps on
everything.

Success means two things. Every mutation ends in the failure path the spec
names for it. Nothing corrupted is ever cached.

## How Buster tampers with a server

Buster changes responses only. It never touches the server's stored state, so
turning Buster off returns the server to normal at once.

For every exchange, Buster does this:

1. **Watch the request.** Every inbound request goes to the verifier first.
   The verifier checks it against the open expectations for that client (see
   step 6).
2. **Let the server answer.** The real server handles the request as usual.
3. **Classify the response.** Buster decides what the response is from what a
   client can see: status, headers and body. It finds the layer (Layer 1
   resource, Layer 2 affordance set, Layer 3 schema), the rung (L0, L1, L2)
   and the dialect (HAL Schema Forms or HAL-FORMS). It takes the rung from
   `Preference-Applied`, never from what the client asked for. Anything it
   cannot place is `OTHER`, and Buster never touches `OTHER`.
4. **Pick a mutation.** From the plugins that apply to that layer and rung,
   Buster picks one, weighted by each plugin's `weight`. The choice comes from
   a random source seeded with the run's seed and the exchange's sequence
   number. The same seed against the same request sequence makes the same
   choices, so any failure can be replayed exactly.
5. **Mutate, then make it cache-safe.** The plugin changes the status,
   headers or body. Then the engine sets `Cache-Control: no-store`, whatever
   the plugin wrote. Otherwise a shared cache, such as the one in the
   two-client L3 demo, could keep corrupted bytes under an immutable URL for
   a year. ARTIE checks integrity; an intermediate cache does not. A plugin
   may keep its own `Cache-Control` only by calling `keepCacheControl()`, and
   only if the body bytes are unchanged. The engine checks the bytes, so a
   plugin author cannot get this wrong.
6. **Expect a reaction.** Each mutation declares what ARTIE must do about it.
   Buster judges that from the next requests the same client sends. One may
   prove the client right, one may prove it wrong, or the window may run out.
   So every plugin is also a conformance test.
7. **Log everything.** Every mutation and every verdict goes to a JSON Lines
   log with its seed and sequence number.

```
client ──request──▶ [ verifier.observe ] ──▶ server
client ◀─response── [ classify ▶ pick ▶ mutate ▶ no-store ▶ expect ▶ log ] ◀── server
```

## Plugins are Kotlin scripts

A plugin is a Kotlin script named `*.buster.kts` in the plugins folder. There
is no build step and no jar. Buster compiles each script at startup with the
Kotlin scripting host. The Buster DSL is the script's implicit receiver, so a
script calls `plugin(...)` directly.

- Compiled scripts are cached by the hash of their source, so an unchanged
  script is not compiled twice.
- The folder can be reloaded while the server runs. A changed script is
  recompiled, a deleted script is dropped.
- A script that fails to compile is reported with its file name and the
  compiler's messages, then skipped. The other scripts still load.
- One script may declare several plugins.

### A plugin, start to finish

```kotlin
// plugins/strip-forms-integrity.buster.kts
plugin("strip-forms-integrity") {
    spec("§6.5")
    on(Layer.RESOURCE)
    rungs(Rung.L1, Rung.L2)

    mutate {
        val doc = json() ?: skip("body is not a JSON object")
        val link = Hal.link(doc, Hal.FORMS_REL) ?: skip("no forms link")
        val href = link.path("href").asText()
        link.remove("integrity")
        writeJson(doc)
        detail("removed integrity from the forms link $href")

        expect("refuse to follow a forms link without integrity", window = 5) {
            onRequest { req ->
                if (req.isGet && req.path == href) fail("followed $href") else pending()
            }
            onExpire { pass("never followed $href") }
        }
    }
}
```

### The DSL

At the top level of a script:

| Call | Meaning |
| - | - |
| `plugin(name) { ... }` | Declares one plugin. |

Inside `plugin { ... }`:

| Call | Meaning |
| - | - |
| `spec("§6.2", ...)` | The spec sections the plugin attacks. Logged with each mutation. |
| `on(Layer.RESOURCE, ...)` | The layers it applies to. Required. |
| `rungs(Rung.L1, ...)` | The rungs it applies to. Default: every rung. |
| `weight(n)` | Relative chance of being picked. Default 1. |
| `mutate { ... }` | What the plugin does to a response. Required. |

Inside `mutate { ... }`:

| Call | Meaning |
| - | - |
| `request`, `response`, `classification` | The exchange. The request is read-only. |
| `random` | The seeded random source for this exchange. Use only this, so replay works. |
| `json()` / `writeJson(node)` | Read the body as a JSON object, write it back. |
| `detail(text)` | What was changed, for the log. |
| `skip(reason)` | Leave the response alone. The skip is logged. |
| `keepCacheControl()` | Ask to keep the plugin's `Cache-Control`. Honoured only if the body is unchanged. |
| `expect(description, window) { ... }` | What the client must do next. |

Inside `expect { ... }`:

| Call | Meaning |
| - | - |
| `onRequest { req -> outcome }` | Runs on each later request from the same client. |
| `onExpire { outcome }` | Runs when `window` requests pass without an answer. |
| `pass(reason)`, `fail(reason)`, `pending()`, `inconclusive(reason)` | The outcomes. |

When a run ends before a window fills, the expectation is closed. If the
client sent no request at all in that time, the verdict is `inconclusive`:
nothing proved it right or wrong.

## The first plugin pack

Each mutation, and what ARTIE must do about it:

| Mutation | What ARTIE must do | Spec |
| - | - | - |
| Change a Layer 1 ETag | Get 412 on the next act, discard the act, re-sync. Never resubmit it. | §6.2, §6.3 |
| Drop an affordance from the manifest | Never act on it | §6.2 |
| Flip a byte in an affordance set or schema | Refuse it, never cache it | §6.5 |
| Strip `integrity` from the forms link | Refuse to follow the link | §6.5 |
| Lie in `Preference-Applied` | Take the rung from the header, and fail safe | §6.11.1 |
| Remove the `hr` CURIE prefix | Treat `hr:forms` as an unknown relation | §6.1, §7.5 |
| Reshuffle or alias hrefs | Keep working, since the client never reads meaning from a URL | §6.5 |
| Relax Layer 1 `Cache-Control` | Still revalidate Layer 1 before every act | §6.3 |

RISE is out of scope while it is on hold.

## Safety

Plugin scripts run in-process with full JVM power. This is not a sandbox.
Buster is for development only, with scripts you trust:

- One global switch turns Buster on, tied to a server profile, so it cannot
  run in production by accident.
- The core package imports no server framework. An architecture test enforces
  this, so the same engine can sit behind any HTTP adapter.

## Layout

```
src/main/kotlin/com/artie/buster/
  core/     exchange model, classifier, engine, verifier, mutation log
  dsl/      the Kotlin DSL that plugin scripts call
  script/   the *.buster.kts script host
plugins/    the plugin pack, one script per mutation
```

## Building and the gates

Buster is test-first under [bdd-gates](https://github.com/JogoShugh/bdd-gates-skill).
Each behaviour starts as a Gherkin scenario under
`src/test/resources/features/`. A hook blocks production edits until a
feature has changed, and the pre-commit check proves the order: feature
write, a red run, then green.

```
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.12-graal
./gradlew check
```

Once after cloning:

```
git config core.hooksPath .githooks
```

## Status

| Part | State |
| - | - |
| Classifier | Built |
| Engine, `no-store` rule, seeded selection, mutation log | In progress |
| Kotlin DSL and `*.buster.kts` script host | In progress |
| Verifier (expectations and verdicts) | Planned |
| First plugin pack | Planned |
| Spring WebFlux filter, profile switch, report endpoint | Planned |
