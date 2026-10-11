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
    // Silence is a FAIL here: a client that never retries was not tested. So only where the dry
    // run shows the client going on to use this resource, which it must not change after a 503.
    silenceMeans(Silence.FAIL)

    mutate {
        if (response.status !in 200..299) skip("only a success can turn flaky")
        if ("json" !in response.headers["Content-Type"].orEmpty()) skip("not JSON")
        val method = request.method
        val path = request.path
        onlyWhere(client does anyRequestTo(path))
        response.status = 503
        response.headers["Content-Type"] = "application/problem+json"
        response.headers["Retry-After"] = "1"
        response.body = """{"status":503,"title":"Service Unavailable"}""".toByteArray()
        detail("$method $path answered 503, Retry-After 1")

        expect("retry $path rather than change it unread", window = 3) {
            seeing(request(method, path)) passes "retried $method $path"
            seeing(actAt(path)) fails { "${it.method} $path without reading it first" }
            silence fails "never retried $path"
        }
    }
}
