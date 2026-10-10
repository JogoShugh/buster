// A sample for any JSON API, nothing to do with H.E.A.R.T.: the server is overloaded.
// A successful plain JSON response becomes 503 Service Unavailable with Retry-After
// (RFC 9110 §15.6.4, §10.2.3). A client should retry the same request. Changing the
// resource it never managed to read is a guess, so that fails.
//
// It names Layer.OTHER: every response the H.E.A.R.T. classifier cannot place, which is
// every response of an ordinary JSON API without ETags.
plugin("flaky-503") {
    spec("RFC 9110 §15.6.4")
    on(Layer.OTHER)

    mutate {
        if (response.status !in 200..299) skip("only a success can turn flaky")
        if ("json" !in response.headers["Content-Type"].orEmpty()) skip("not JSON")
        val method = request.method
        val path = request.path
        // Silence is a FAIL here, so only where the dry run shows the client going on to use this
        // resource: there is then something it must not do after a 503.
        val seen = future ?: skip("no dry run, so silence would be a guess")
        if (seen.none { it.path == path }) skip("the client never goes on to use $path in its dry run")
        response.status = 503
        response.headers["Content-Type"] = "application/problem+json"
        response.headers["Retry-After"] = "1"
        response.body = """{"status":503,"title":"Service Unavailable"}""".toByteArray()
        detail("$method $path answered 503, Retry-After 1")

        expect("retry $path rather than change it unread", window = 3) {
            onRequest { req ->
                when {
                    req.method == method && req.path == path -> pass("retried $method $path")
                    req.isAct && req.path == path -> fail("${req.method} $path without reading it first")
                    else -> pending()
                }
            }
            onExpire { fail("never retried $path") }
        }
    }
}
