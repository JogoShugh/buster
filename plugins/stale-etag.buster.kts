// SPEC §6.2, §6.3: an act carries If-Match. When the ETag is stale the server answers 412,
// and the client SHALL discard the act and re-sync. It SHALL NOT resubmit it.
// The forged ETag never matches, so the real server sends the 412 itself.
plugin("stale-etag") {
    spec("§6.2", "§6.3")
    on(Layer.RESOURCE)
    // The act this ETag was for may never come, and that is a FAIL: so the mutation is made only
    // where the dry run shows this client acting on this ETag.
    silenceMeans(Silence.FAIL)

    mutate {
        val original = response.headers["ETag"] ?: skip("no ETag")
        onlyWhere(client does act(ifMatch = original))
        val forged = "\"buster-%08x\"".format(random.nextInt())
        response.headers["ETag"] = forged
        detail("ETag $original became $forged")

        // The resource the ETag belongs to, which for an act's response is not the action URL.
        expect("discard the act on 412 and re-sync; never resubmit it", window = 5) {
            val resubmit = act(ifMatch = forged)
            seeing(resubmit) { if (already(resubmit)) fail("resubmitted an act with the stale ETag") else pending() }
            seeing(get(resource)) passes { if (already(resubmit)) "re-synced after 412" else "revalidated before acting" }
            silence fails { if (already(resubmit)) "did not re-sync after 412" else "never made the act it made unmutated" }
        }
    }
}
