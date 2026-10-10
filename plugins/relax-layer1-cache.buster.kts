// SPEC §6.3 (revised 2026-10-10): Layer 1 is never fresh, for any length of time. A client
// SHALL send the request directive Cache-Control: no-cache on every Layer 1 request, so no
// cache on the path answers from storage unchecked, whatever the response said. This plugin
// tells the client it may reuse the resource for an hour. The act made straight after the
// read is not judged: that read was the fresh check, and If-Match guards the act. The next
// read of the same resource is judged: it must reach the server saying no-cache. A read the
// client's cache answered never reaches Buster, so it stays inconclusive.
// The body is unchanged, so the engine lets this plugin keep its Cache-Control. It is
// private, so no shared cache stores it.
plugin("relax-layer1-cache") {
    spec("§6.3")
    on(Layer.RESOURCE)

    mutate {
        val before = response.headers["Cache-Control"]
        response.headers["Cache-Control"] = "private, max-age=3600"
        keepCacheControl()
        val path = request.path
        detail("Cache-Control $before became private, max-age=3600")
        fun saysNoCache(req: BusterRequest) =
            req.headers["Cache-Control"].orEmpty().split(',').any { it.trim() == "no-cache" }
        // The read that got this response is wire evidence too: a client that said no-cache
        // on it reads Layer 1 that way, so the window ending without a re-read is a pass.
        val readWithNoCache = saysNoCache(request)

        expect("re-read Layer 1 with Cache-Control: no-cache, whatever the response said", window = 5) {
            onRequest { req ->
                when {
                    req.isGet && req.path == path && saysNoCache(req) -> pass("re-read $path with no-cache")
                    req.isGet && req.path == path -> fail("re-read $path without no-cache")
                    else -> pending()
                }
            }
            onExpire {
                if (readWithNoCache) {
                    pass("read $path with no-cache, and did not re-read it without")
                } else {
                    inconclusive("never re-read $path")
                }
            }
        }
    }
}
