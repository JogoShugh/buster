// SPEC §6.3 (revised 2026-10-10): Layer 1 is never fresh, for any length of time. A client
// SHALL send the request directive Cache-Control: no-cache on every Layer 1 request, so no
// cache on the path answers from storage unchanged, whatever the response said. This plugin
// tells the client it may reuse the resource for an hour.
//
// Two reads can show the rule, and both are judged:
//  - the read that got this response: if it said no-cache, the client reads Layer 1 that
//    way, and that is the pass; if it did not, that read broke the rule, and that is a FAIL.
//  - a later read of the same resource: it must reach the server saying no-cache too.
// The act made straight after the read is not judged: that read was the fresh check, and
// If-Match guards the act.
//
// An act's response is Layer 1 of the resource too, answered at the action URL (and named by
// Content-Location). That response was got by a write, which carries no directive to judge,
// so only later reads of the resource are judged, and the mutation is made only where the
// client reads the resource again.
//
// The body is unchanged, so the engine lets this plugin keep its Cache-Control. It is
// private, so no shared cache stores it.
plugin("relax-layer1-cache") {
    spec("§6.3")
    on(Layer.RESOURCE)

    mutate {
        // The resource to read again, which for an act's response is not the action URL.
        // An act's response was got by a write: made only where the client reads the resource again.
        if (request.isAct) onlyWhere(client does get(resourcePath))
        header("Cache-Control").replace("private, max-age=3600")
        keepCacheControl()

        expect("re-read Layer 1 with Cache-Control: no-cache, whatever the response said", window = 5) {
            seeing(get(resource).with(cacheControl = "no-cache")) passes "re-read $resource with no-cache"
            seeing(get(resource)) fails "re-read $resource without no-cache"
            // The read that got this response is wire evidence too, and the client is done with
            // it already: silence at the end is judged on it. A write has no directive to judge.
            val readWithoutNoCache = trigger.isGet && !get(trigger.path).with(cacheControl = "no-cache").matches(trigger)
            if (readWithoutNoCache) {
                silence fails "read $resource without no-cache, and no later read said it"
            } else {
                silence passes "did not re-read $resource without no-cache"
            }
        }
    }
}
