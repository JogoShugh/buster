// SPEC §6.2, §6.3: an act carries If-Match. When the ETag is stale the server answers 412,
// and the client SHALL discard the act and re-sync. It SHALL NOT resubmit it.
// The forged ETag never matches, so the real server sends the 412 itself.
plugin("stale-etag") {
    spec("§6.2", "§6.3")
    on(Layer.RESOURCE)

    mutate {
        val original = response.headers["ETag"] ?: skip("no ETag")
        val forged = "\"buster-%08x\"".format(random.nextInt())
        response.headers["ETag"] = forged
        val path = request.path
        detail("ETag $original became $forged")

        var acted = false
        expect("discard the act on 412 and re-sync; never resubmit it", window = 5) {
            onRequest { req ->
                when {
                    req.isAct && req.ifMatch == forged && acted -> fail("resubmitted an act with the stale ETag")
                    req.isAct && req.ifMatch == forged -> pending().also { acted = true }
                    req.isGet && req.path == path -> pass(if (acted) "re-synced after 412" else "revalidated before acting")
                    else -> pending()
                }
            }
            onExpire { if (acted) fail("did not re-sync after 412") else inconclusive("never acted") }
        }
    }
}
