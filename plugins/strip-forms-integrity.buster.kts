// SPEC §6.5: a client SHALL NOT follow a forms link that carries no integrity,
// because it cannot verify the affordance set before caching it.
plugin("strip-forms-integrity") {
    spec("§6.5")
    on(Layer.RESOURCE)
    rungs(Rung.L1, Rung.L2)

    mutate {
        val doc = json() ?: skip("body is not a JSON object")
        val link = Hal.link(doc, Hal.FORMS_REL) ?: skip("no forms link")
        val href = link.path("href").asText()
        link.remove("integrity") ?: skip("forms link already has no integrity")
        // Only where the client follows the link, so there is something to refuse.
        future?.let { seen -> if (seen.none { it.isGet && it.path == href }) skip("the client never follows $href") }
        writeJson(doc)
        detail("removed integrity from the forms link $href")

        val client = request.clientId
        expect("refuse to follow a forms link without integrity", window = 5) {
            // Refused before any request, so the event names the link by its URL, not a span.
            onEvent { ev ->
                if (ev.name == "integrity.refused" && ev.traceId == client && ev.path == href) {
                    attest("its log says it refused $href")
                } else {
                    pending()
                }
            }
            onRequest { req -> if (req.isGet && req.path == href) fail("followed $href") else pending() }
            onSuperseded { pass("read the resource again, and was answered a whole representation, before following $href") }
            onExpire { pass("never followed $href") }
        }
    }
}
