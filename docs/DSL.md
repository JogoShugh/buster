# Writing Buster plugins

A Buster plugin is a short Kotlin script. It does two things: it changes one response in a
way a well-behaved client must notice, and it says what that client must do about it. Buster
then watches the client and gives one verdict.

This guide teaches the DSL from the first plugin to the escape hatches. Every complete plugin
in it is loaded by the test suite (`dsl-guide.feature`), so what you read compiles.

Contents

1. [The idea](#1-the-idea)
2. [Your first plugin](#2-your-first-plugin)
3. [Where a plugin applies](#3-where-a-plugin-applies)
4. [Changing a response](#4-changing-a-response)
5. [Arming a mutation: the dry run](#5-arming-a-mutation-the-dry-run)
6. [Judging the client](#6-judging-the-client)
7. [What silence means](#7-what-silence-means)
8. [The resource and the action URL](#8-the-resource-and-the-action-url)
9. [Cookbook](#9-cookbook)
10. [The escape hatch](#10-the-escape-hatch)
11. [Testing a plugin](#11-testing-a-plugin)
12. [Running a plugin](#12-running-a-plugin)
13. [Reference](#13-reference)
14. [What the DSL refuses](#14-what-the-dsl-refuses)

## 1. The idea

```
mutate { change the response }          expect { what the client must do next }
        │                                        │
        └── one response, changed ──────────────▶ judged on the same client's next requests
                                                  and on its own event log
```

A plugin has a `mutate` block. Inside it you change the response, and you call `expect` to
say what the client must do afterwards. Buster runs the `mutate` block on a copy, so a
`skip(...)` leaves the real response untouched.

Every mutation ends in one of three verdicts, and there is no fourth:

| Verdict | Means |
| - | - |
| `pass` | The wire showed the client did the right thing. |
| `pass (attested)` | The wire showed nothing wrong, and the client's own event log said it did the right thing. Weaker than `pass`, so the report keeps them apart. |
| `FAIL` | The client did the wrong thing. |

There is no "inconclusive". A plugin has to decide what a client's silence means, and the DSL
will not compile a plugin that leaves it open ([section 7](#7-what-silence-means)).

Two ideas run through the whole DSL:

- **A matcher is a value.** `get("/forms/x")` is a thing you can use to arm a mutation
  ("only where the client goes on to fetch this") and again to judge it ("FAIL if the client
  fetches this"). One definition, so arming and judging cannot drift apart.
- **Silence is declared.** Whether a client that does nothing passes or fails is part of the
  plugin's declaration, not an accident of how the code is written.

## 2. Your first plugin

This is `samples/flaky-503.buster.kts`. It is not specific to H.E.A.R.T.: it plays an
overloaded server in front of any JSON API. A successful JSON response becomes
`503 Service Unavailable` with `Retry-After`, and the client should retry the same request.

```kotlin
plugin("flaky-503") {
    spec("RFC 9110 §15.6.4")
    on(Layer.OTHER)
    // Silence is a FAIL here: a client that never retries was not tested. So only where the dry
    // run shows the client going on to use this resource, which it must not change after a 503.
    silenceMeans(Silence.FAIL)

    mutate {
        skipUnless(response.isSuccess, "only a success can turn flaky")
        skipUnless(response.isJson, "not JSON")
        val path = request.path
        val retry = request(request.method, path)
        onlyWhere(client does anyRequestTo(path))
        answerWithProblem(503, "Service Unavailable", retryAfterSeconds = 1)

        expect("retry $path rather than change it unread", window = 3) {
            seeing(retry) passes "retried $retry"
            seeing(actAt(path)) fails { "${it.method} $path without reading it first" }
            silence fails "never retried $path"
        }
    }
}
```

Read it top to bottom:

- `plugin("flaky-503") { ... }` declares the plugin. `spec(...)` names what it attacks; it is
  logged with each mutation.
- `on(Layer.OTHER)` says which responses it applies to
  ([section 3](#3-where-a-plugin-applies)).
- `silenceMeans(Silence.FAIL)` says that a client that does nothing has failed
  ([section 7](#7-what-silence-means)).
- In `mutate`, the two `skipUnless(...)` guards leave responses alone that are not the plugin's
  business. A guard says what must hold, so it reads the way the plugin is described. `onlyWhere(...)` makes the mutation only where the dry run shows the client
  using this path ([section 5](#5-arming-a-mutation-the-dry-run)). `answerWithProblem(...)`
  changes the response.
- `expect(...)` says what the client must do next, as rules
  ([section 6](#6-judging-the-client)): retrying the same request passes; changing the
  resource without having read it fails; doing nothing fails.

The smallest plugin changes a response and expects nothing. It is still a legal plugin, and
the engine still sends it `no-store`:

```kotlin
plugin("rename-bed") {
    on(Layer.RESOURCE)
    mutate {
        val doc = jsonBody
        doc.put("name", "Chewed")
        writeJson(doc)
        detail("renamed the bed to Chewed")
    }
}
```

## 3. Where a plugin applies

Buster classifies each response from what a client can see: status, headers and body.

| `Layer` | The response is |
| - | - |
| `RESOURCE` | Layer 1: a HAL representation of a resource, with an `ETag`. |
| `AFFORDANCE_SET` | Layer 2: a content-addressed, immutable affordance set. |
| `SCHEMA` | Layer 3: a content-addressed, immutable schema. |
| `OTHER` | Anything else: every response of a server that is not H.E.A.R.T., a JSON Home service index, plain JSON, error responses. |

For `RESOURCE`, the `Rung` (`L0`, `L1`, `L2`) is read from `Preference-Applied`, never from
what the client asked for. No header means `L0`.

```kotlin
on(Layer.RESOURCE)                 // required: at least one layer
on(Layer.AFFORDANCE_SET, Layer.SCHEMA)
rungs(Rung.L1, Rung.L2)            // default: every rung
weight(3)                          // relative chance of being picked; default 1
spec("§6.5", "§7.5")               // what the plugin attacks, for the log
```

Of all the plugins that apply to a response, Buster picks one, weighted by `weight`, from a
random source seeded with the run's seed and the exchange's sequence number. The same seed
against the same requests makes the same choices, so a failure replays exactly. Inside a
plugin, use only `random`, never your own source.

## 4. Changing a response

Inside `mutate`, `request` is read-only and `response` is yours to change. The helpers below
note what they changed in the mutation's `detail`, so the log says what the plugin did, not
what it meant to do.

```kotlin
header("ETag").replace("\"forged\"")        // detail: ETag "s1" became "forged"
header("Preference-Applied").remove()       // detail: removed Preference-Applied: forms=link
header("Preference-Applied").value          // read it; null when absent

val doc = jsonBody                          // the body as a JSON object; skips if it is not one
doc.formsHref                               // where the forms link points, or null
doc.formsLink                               // the forms link object, or null
writeJson(doc)                              // write your change back

flipLetterInString()                        // one letter inside a JSON string; still valid JSON
answerWithProblem(503, "Service Unavailable", retryAfterSeconds = 1)

detail("what else you changed")             // for anything the helpers did not note
skipUnless(response.isSuccess, "only a success")   // guards: say what must hold
skipIf(response.status == 404, "leave a 404 alone")
skip("why this response is not the plugin's business")
```

`response.status`, `response.headers` and `response.body` are also open to you directly, for a
change no helper covers.

Three things the engine does for you:

- **A skip discards everything.** The plugin works on a copy.
- **Mutated responses are sent `no-store`.** A shared cache must never keep corrupted bytes.
  A plugin keeps its own `Cache-Control` only by calling `keepCacheControl()`, and only if
  the body bytes are unchanged. The engine checks the bytes.
- **The mutation is logged** with the seed and sequence number, and so is every verdict.

## 5. Arming a mutation: the dry run

A plugin whose silence is a FAIL must not mutate a response the client will never act on:
there is nothing to judge, and silence would be a guess. Buster solves this with a *dry run*:
the client runs once against the unmutated server, and Buster records what it sends. When
the same client runs again with a round armed, each mutation is aimed at a request the dry
run showed. See [Campaigns](../README.md#campaigns-aiming-each-mutation).

In a plugin, the dry run is `future`: what this client sent after this request in the dry
run, or `null` when there is no dry run. You rarely read it directly. You say what the client
must be shown to do, with a matcher:

```kotlin
onlyWhere(client does get("/forms/abc"))
```

`onlyWhere` skips the mutation, with a reason that names the action ("the client never does
GET /forms/abc in its dry run"), when the dry run shows the client never does it. With no dry
run it lets the mutation go on, unless the plugin declared `silenceMeans(Silence.FAIL)`, in
which case there is no mutation without a dry run.

### Matchers

A `Matcher` tests one request. Build one with:

| Matcher | Matches |
| - | - |
| `get("/beds/1")` | A GET of exactly that path. |
| `request("DELETE", "/beds/1")` | That method at exactly that path. |
| `anyRequestTo("/beds/1")` | Any method to exactly that path. |
| `anyAct` | Any request that is not a read. |
| `actAt("/beds/1/name")` | An act made at exactly that path. |
| `act(ifMatch = etag)` | An act carrying that `If-Match`. |
| `act(on = form)` | An act on that affordance: its method, and a path its target template expands to. |
| `anyOf(a, b, ...)` | Any one of them. |
| `m.with(cacheControl = "no-cache")` | The same request, carrying that request directive. |

A matcher is also a value you can name, pass around and test:

```kotlin
val follows = get(href)
onlyWhere(client does follows)
// later, inside expect:
seeing(follows) fails "followed $href"
```

`follows.matches(request)` answers directly, for the rare rule that needs it.

## 6. Judging the client

`expect(description, window)` says what the client must do next. It is judged on the next
`window` requests the same client sends. Inside it you write rules.

```kotlin
expect("refuse to follow a forms link without integrity", window = 5) {
    seeing(follows) fails "followed $href"
    seeing(logs("integrity.refused").about(href)) attests "its log says it refused $href"
    superseded passes "read the resource again, and was answered a whole representation"
    silence passes "never followed $href"
}
```

### `seeing(matcher)`: what a request means

```kotlin
seeing(get("/x")) passes "read it"
seeing(get("/x")) fails "read it"
seeing(anyAct) fails { "acted on ${it.path}" }        // the reason may read the request
seeing(follows) { req ->                              // or the whole decision in a block
    if (req.query.orEmpty().contains(alias)) pass("followed it") else fail("built the URL itself")
}
```

The rules are tried in the order written; **the first one that matches a request decides it**.
A request no rule matches is ignored. `pending()` from a block means "this request settles
nothing", and the window goes on.

### `already(matcher)`: what came before

A rule can ask whether the client already did something earlier in this window. You never keep
a flag of your own:

```kotlin
val resubmit = act(ifMatch = forged)
seeing(resubmit) { if (already(resubmit)) fail("resubmitted an act with the stale ETag") else pending() }
```

### `logs(name)`: the client's own event log

A client may write an event log. A line of it can only ever **attest**: the wire is what
proves a pass, and the log says what the client thought it did.

```kotlin
seeing(logs("forms.skipped").thisResponse) attests "its log says it skipped the link"
seeing(logs("integrity.refused").about("/forms/abc")) attests "..."
seeing(logs("affordance.absent").with("heart.rel", "rename")) attests "..."
```

| Narrowing | Matches a line that |
| - | - |
| `logs(name)` | has that `event_name`. |
| `.thisResponse` | is about the request whose response was mutated (its `span_id`). |
| `.about(path)` | has a `url.full` with that path. |
| `.with(key, value)` | carries that attribute. |

### The rest of `expect`

| Name | Meaning |
| - | - |
| `trigger` | The request whose response was mutated, to judge the read that got the mutation. |
| `resource` | The path of the resource the mutated response is the state of ([section 8](#8-the-resource-and-the-action-url)). |
| `superseded passes "…"` | What it means that the client was given the resource again, whole and unmutated. The mutated representation is gone, so the window closes. |
| `silence passes "…"` / `fails "…"` | What silence means ([section 7](#7-what-silence-means)). |

## 7. What silence means

The window closes in three ways: `window` requests have gone by, the client sent
`client.done`, or the round was ended. Closing always runs the silence rule, even for a client
that sent nothing after the mutation. Every expectation therefore has to say what that means:

```kotlin
silence passes "never followed $href"
silence fails "never retried $path"
silence fails { if (already(resubmit)) "did not re-sync" else "never made the act" }
```

Which one is right depends on the rule:

- **Pass.** The rule is one a client can only break by doing something. A client that never
  follows the forms link has not followed it without integrity. It has nothing to answer for.
- **FAIL.** The rule is one a client breaks by not doing something. If a client must retry
  after a 503 and never does, it failed.

State this once at the plugin level too, with `silenceMeans`:

```kotlin
silenceMeans(Silence.FAIL)    // default: Silence.PASS
```

A plugin that declares `Silence.FAIL` is *never made without a dry run*, and a FAIL plugin
that never calls `onlyWhere` is skipped with a note. So a false FAIL for "the client was never
going to do that anyway" cannot be written.

When silence depends on what happened, give a block:

```kotlin
silence fails { if (already(resubmit)) "did not re-sync after 412" else "never made the act" }
```

or branch and call `silence passes` / `silence fails` on each side.

## 8. The resource and the action URL

An act answers at its action URL (`PUT /beds/1/name`), but its response is the new state of a
resource (`/beds/1`). Judging a write as if it were a read of the action URL would wait for a
request no client ever makes.

`resourcePath` (in `mutate`) and `resource` (in `expect`) are the path of the resource a
response is the state of: `Content-Location` (RFC 9110 §8.7), else the HAL self link, else the
request path. Use it for "the client reads the resource again":

```kotlin
seeing(get(resource)) passes "revalidated"
```

An act's `2xx` answer that carries a `Content-Location` also counts as a whole, unmutated
representation of that resource when Buster did not mutate it. That closes the window
(`superseded`) for a plugin that said what the client does with a fresh representation.

## 9. Cookbook

Each recipe is a real plugin from the pack, with what it teaches.

### Refuse a link: `strip-forms-integrity`

Remove the integrity from the link, and expect the client not to follow it. Silence passes:
a client that never follows it has nothing to answer for.

```kotlin
plugin("strip-forms-integrity") {
    spec("§6.5")
    on(Layer.RESOURCE)
    rungs(Rung.L1, Rung.L2)

    mutate {
        val doc = jsonBody
        val link = doc.formsLink ?: skip("no forms link")
        val href = doc.formsHref ?: skip("forms link has no href")
        link.remove("integrity") ?: skip("forms link already has no integrity")
        // Only where the client follows the link, so there is something to refuse.
        val follows = get(href)
        onlyWhere(client does follows)
        writeJson(doc)
        detail("removed integrity from the forms link $href")

        expect("refuse to follow a forms link without integrity", window = 5) {
            // Refused before any request, so the event names the link by its URL, not a span.
            seeing(logs("integrity.refused").about(href)) attests "its log says it refused $href"
            seeing(follows) fails "followed $href"
            superseded passes "read the resource again, and was answered a whole representation, before following $href"
            silence passes "never followed $href"
        }
    }
}
```

### Forge a validator: `stale-etag`

Silence is a FAIL: the act this ETag was for may never come, so the plugin names the act it
needs, with `onlyWhere`, and uses `already` to tell "resubmitted" from "re-synced".

```kotlin
plugin("stale-etag") {
    spec("§6.2", "§6.3")
    on(Layer.RESOURCE)
    // The act this ETag was for may never come, and that is a FAIL: so the mutation is made only
    // where the dry run shows this client acting on this ETag.
    silenceMeans(Silence.FAIL)

    mutate {
        val original = header("ETag").value ?: skip("no ETag")
        onlyWhere(client does act(ifMatch = original))
        val forged = header("ETag").replace("\"buster-%08x\"".format(random.nextInt()))

        // The resource the ETag belongs to, which for an act's response is not the action URL.
        expect("discard the act on 412 and re-sync; never resubmit it", window = 5) {
            val resubmit = act(ifMatch = forged)
            seeing(resubmit) { if (already(resubmit)) fail("resubmitted an act with the stale ETag") else pending() }
            seeing(get(resource)) passes { if (already(resubmit)) "re-synced after 412" else "revalidated before acting" }
            silence fails { if (already(resubmit)) "did not re-sync after 412" else "never made the act it made unmutated" }
        }
    }
}
```

### Corrupt bytes: `flip-byte`

The same matcher, `uses`, arms the mutation and judges the client. A schema has no targets of
its own, so any act uses it.

```kotlin
plugin("flip-byte") {
    spec("§6.5")
    on(Layer.AFFORDANCE_SET, Layer.SCHEMA)

    mutate {
        // Read the affordances before the flip, so the expectation knows which acts they allow.
        // A schema has no targets of its own: any act of the client's uses it.
        val forms = json()?.let { Forms.embedded(it) }?.elements()?.asSequence()?.toList()
        val uses = if (forms == null) anyAct else anyOf(*forms.map { act(on = it) }.toTypedArray())
        onlyWhere(client does uses)
        flipLetterInString()
        val path = request.path
        val layer = classification.layer

        expect("refuse the corrupted $layer and never act from it", window = 5) {
            seeing(logs("integrity.refused").thisResponse) attests "its log says it refused $path"
            seeing(get(path)) passes "refetched $path"
            seeing(uses) fails { "acted on ${it.path} before refetching $path" }
            silence passes "neither refetched $path nor acted from it"
        }
    }
}
```

### Remove a capability: `drop-affordance`

This one chooses what to mutate by looking at the dry run: it drops the affordance the client
goes on to act on.

```kotlin
plugin("drop-affordance") {
    spec("§6.2")
    on(Layer.RESOURCE)
    rungs(Rung.L0)

    mutate {
        val doc = jsonBody
        val forms = Forms.embedded(doc) ?: skip("no embedded affordances")
        val names = forms.fieldNames().asSequence().toList()
        skipIf(names.isEmpty(), "no affordances")
        // With a dry run, drop the affordance the client goes on to act on. Without one, any.
        val name = future?.let { seen ->
            names.firstOrNull { n -> seen.any { Forms.actsOn(it, forms.get(n)) } }
                ?: skip("the client never acts on an affordance of ${request.path} in its dry run")
        } ?: names[random.nextInt(names.size)]
        val dropped = forms.get(name)
        val method = Forms.method(dropped)
        val target = Forms.target(dropped) ?: skip("affordance $name has no target")
        forms.remove(name)
        // Another affordance with the same method and target would make an act on it legal.
        val shared = forms.elements().asSequence().any { Forms.method(it) == method && Forms.target(it) == target }
        skipIf(shared, "another affordance shares $method $target")
        writeJson(doc)
        detail("dropped affordance $name ($method $target)")

        expect("never act on the dropped affordance $name", window = 5) {
            seeing(logs("affordance.absent").with("heart.rel", name)) attests "its log says it found $name gone"
            seeing(act(on = dropped)) fails { "acted on $name: $method ${it.path}" }
            // The resource, given again unmutated, holds a manifest that may list the act again,
            // and acting on it is then what §6.2 asks.
            superseded passes "was given the resource again, unmutated, before acting on $name"
            silence passes "never acted on $name"
        }
    }
}
```

### Lie about the rung: `lie-preference-applied`

Removing a header changes what a client that trusts it will find. The rule is judged partly on
what the client did *before* it read the resource again.

```kotlin
plugin("lie-preference-applied") {
    spec("§6.11.1", "§6.4")
    on(Layer.RESOURCE)
    rungs(Rung.L1, Rung.L2)

    mutate {
        header("Preference-Applied").value ?: skip("no Preference-Applied")
        val doc = jsonBody
        skipIf(Forms.embedded(doc) != null, "affordances are embedded, so L0 is not a lie")
        val href = doc.formsHref ?: skip("no forms link")
        // Only where the client follows the link, so there is something to refuse.
        val follows = get(href)
        onlyWhere(client does follows)
        header("Preference-Applied").remove()
        detail("the body still links $href")

        expect("take L0 from the header, find no affordances, and fail safe", window = 5) {
            // An act before the client has read the resource again was made with no affordances.
            val rereads = get(resource)
            seeing(follows) fails "followed $href: read the rung from the body, not the header"
            seeing(anyAct) { req ->
                if (already(rereads)) pending() else fail("acted on ${req.path} with no affordances")
            }
            seeing(logs("forms.skipped").thisResponse) attests "its log says it skipped $href at L0"
            superseded passes "read $resource again, and was answered a whole representation, before following $href"
            silence passes "treated $resource as L0 with no affordances"
        }
    }
}
```

## 10. The escape hatch

The rules cover what plugins have needed. When a plugin needs something they cannot say, drop
to the callbacks. They are never removed, and they combine with rules: a `seeing` rule is
tried first, and `onRequest` sees the requests no rule matched.

| Callback | Does |
| - | - |
| `onRequest { req -> outcome }` | Sees each later request from the same client. |
| `onEvent { event -> outcome }` | Sees each line of the client's event log. Only `attest(...)` counts. |
| `onSuperseded { decided }` | The same as `superseded passes/fails`, as a block. |
| `onExpire { decided }` | The same as `silence passes/fails`, as a block. It can only return `pass` or `fail`. |
| `pass(r)`, `fail(r)`, `attest(r)`, `pending()` | The outcomes. `pending()` is not allowed from an expiry. |

For example, a plugin that counts how often the client hammers a resource it was told to back
off from. A counter is state the rules do not keep, so it reaches for `onRequest`:

```kotlin
plugin("busy-429") {
    spec("RFC 6585 §4")
    on(Layer.OTHER)
    silenceMeans(Silence.FAIL)

    mutate {
        skipUnless(response.isSuccess, "only a success can turn busy")
        val path = request.path
        onlyWhere(client does anyRequestTo(path))
        answerWithProblem(429, "Too Many Requests", retryAfterSeconds = 2)

        var tries = 0
        expect("back off from $path", window = 4) {
            onRequest { req ->
                if (req.path != path) {
                    pending()
                } else {
                    tries++
                    if (tries > 1) fail("hammered $path $tries times") else pending()
                }
            }
            onExpire { if (tries == 0) fail("never came back to $path") else pass("came back once") }
        }
    }
}
```

## 11. Testing a plugin

The plugin is a script, so a test can put it in the feature itself and drive it. The step
`Given the plugin script:` loads a plugin from a doc string, and the steps that follow send a
response through it and say what the client does next. This is `dsl-rules.feature`:

```gherkin
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
```

Write scenarios for the three things a plugin must get right:

- **It is made where it should be.** A dry run that shows the action gives a changed
  response; one that does not gives an unchanged response and a skip that names the action.
- **It judges what it should.** Each behaviour a client can have gets a scenario with its
  verdict and its reason.
- **Silence means what you said.** A client that sends nothing, then `the run ends`.

Run them with `./gradlew test`. The pack's own scenarios, in `plugin-pack.feature`, are the
best examples of what to cover.

## 12. Running a plugin

Put the script in the plugins folder (`buster.plugins`, or `BUSTER_PLUGINS`) with the name
`<anything>.buster.kts`. Buster compiles it at startup, caches by the hash of its source, and
reports a script that does not compile with its file name and the compiler's messages, then
skips it; the other scripts still load. One script may declare several plugins.

Run a campaign to aim each plugin at the requests your client really makes:
[Campaigns](../README.md#campaigns-aiming-each-mutation). The report names the plugin, the
client, the mutation, the verdict and its reason.

## 13. Reference

### At the top of a script

| Call | Meaning |
| - | - |
| `plugin(name) { ... }` | Declares one plugin. A script may declare several. |

### Inside `plugin { ... }`

| Call | Meaning |
| - | - |
| `spec("§6.2", ...)` | The spec sections or RFCs the plugin attacks. Logged with each mutation. |
| `on(Layer.RESOURCE, ...)` | The layers it applies to. Required. |
| `rungs(Rung.L1, ...)` | The rungs it applies to. Default: every rung. |
| `weight(n)` | Relative chance of being picked. Default 1. |
| `silenceMeans(Silence.FAIL)` | What a client that does nothing means. Default `Silence.PASS`. |
| `mutate { ... }` | What the plugin does to a response. Required. |

### Inside `mutate { ... }`

| Name | Meaning |
| - | - |
| `request` | The inbound request, read-only: `method`, `path`, `query`, `headers`, `clientId`, `spanId`, `label`, `isGet`, `isAct`, `ifMatch`. |
| `response` | The response, yours to change: `status`, `headers`, `body`. |
| `classification` | `layer`, `rung`, `dialect`. |
| `random` | The seeded random source. Use only this. |
| `resourcePath` | The path of the resource the response is the state of. |
| `future` | What the client sent after this request in the dry run, or `null`. |
| `client does matcher` | Reads as `onlyWhere(client does get("/x"))`. |
| `onlyWhere(matcher)` | Make the mutation only where the dry run shows the client doing that. |
| matchers | `get`, `request`, `anyRequestTo`, `anyAct`, `actAt`, `act(ifMatch=)`, `act(on=)`, `anyOf`. |
| `jsonBody`, `json()`, `writeJson(node)` | The body as a JSON object, and writing it back. |
| `node.formsLink`, `node.formsHref` | The forms link of a document, and where it points. |
| `header(name).replace(v)`, `.remove()`, `.value` | Change or read a header, noted in the detail. |
| `flipLetterInString()` | Change one letter inside a JSON string. |
| `answerWithProblem(status, title, retryAfterSeconds)` | Turn the response into an RFC 9457 problem. |
| `detail(text)` | What else was changed, for the log. |
| `skipUnless(condition, reason)`, `skipIf(condition, reason)` | Leave the response alone unless the condition holds, or when it does. |
| `skip(reason)` | Leave the response alone. |
| `response.isSuccess`, `response.isJson` | A 2xx; a JSON `Content-Type`. |
| `keepCacheControl()` | Keep the plugin's `Cache-Control`, if the body bytes are unchanged. |
| `expect(description, window) { ... }` | What the client must do next. |

### Inside `expect { ... }`

| Name | Meaning |
| - | - |
| `seeing(matcher) passes/fails "…"` | What a request means. The reason may be a block. |
| `seeing(matcher) { req -> outcome }` | The whole decision in a block. |
| `seeing(logs(name)...) attests "…"` | What a line of the client's log means. |
| `logs(name)` `.thisResponse` `.about(path)` `.with(k, v)` | Event matchers. |
| `already(matcher)` | Whether the client did that earlier in this window. |
| `trigger`, `resource` | The mutated response's request, and the resource it is the state of. |
| `superseded passes/fails "…"` | The client was given the resource again, whole and unmutated. |
| `silence passes/fails "…"` | What silence means. Required, as is `onExpire`; one of them. |
| `onRequest`, `onEvent`, `onSuperseded`, `onExpire` | The callbacks, for what the rules cannot say. |
| `pass`, `fail`, `attest`, `pending` | The outcomes. |

## 14. What the DSL refuses

| You write | Buster does |
| - | - |
| No `on(...)` | The script fails to load: `on(...) names no layer`. |
| No `mutate { }` | The script fails to load: `no mutate { } block`. |
| `expect` with no `silence` and no `onExpire` | The expectation is refused when it is built, the first time the plugin mutates: `no onExpire { } block, so silence has no meaning`. |
| `onExpire { pending() }` | Does not compile: an expiry can only be `pass` or `fail`. |
| `silenceMeans(Silence.FAIL)`, run without a dry run | The mutation is skipped: `no dry run, so silence would be a guess`. |
| `silenceMeans(Silence.FAIL)`, `onlyWhere` never called | The mutation is skipped: `silenceMeans(FAIL) but mutate never called onlyWhere`. |
| `window = 0` | The expectation is refused when it is built: `window must be at least 1`. |
| Your own `Random` | Not refused, but it breaks replay. Use `random`. |
