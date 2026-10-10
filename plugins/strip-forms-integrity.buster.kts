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
            onExpire { pass("never followed $href") }
        }
    }
}
