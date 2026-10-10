// SPEC §6.3: a client SHALL revalidate Layer 1 before every act, whatever Cache-Control says.
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

        expect("revalidate Layer 1 before the next act", window = 5) {
            onRequest { req ->
                when {
                    req.isGet && req.path == path -> pass("revalidated $path")
                    // Acts on this resource's form targets sit under its path.
                    req.isAct && req.path.startsWith(path) -> fail("acted on ${req.path} without revalidating")
                    else -> pending()
                }
            }
            onExpire { inconclusive("never acted") }
        }
    }
}
