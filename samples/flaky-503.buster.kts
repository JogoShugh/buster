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
