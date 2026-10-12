// Spike plugin: turn a success into 503 Service Unavailable with Retry-After. It claims nothing
// about the client; it exists to show that a mutation made by Buster reaches the caller through Envoy.
plugin("always-503") {
    spec("RFC 9110 §15.6.4")
    on(Layer.OTHER)
    mutate {
        skipUnless(response.isSuccess, "only a success can turn into a 503")
        answerWithProblem(503, "Service Unavailable", retryAfterSeconds = 1)
        expect("spike: no judgement", window = 1) {
            silence passes "nothing judged"
        }
    }
}
