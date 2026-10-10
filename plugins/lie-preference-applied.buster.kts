// SPEC §6.11.1, §6.4: a client SHALL take the rung from Preference-Applied, and no header
// means L0. Removing the header from an L1 or L2 response says the affordances arrived
// embedded. They did not, so a client that trusts the header finds none and fails safe:
// it neither follows the forms link it was not told about nor acts.
plugin("lie-preference-applied") {
    spec("§6.11.1", "§6.4")
    on(Layer.RESOURCE)
    rungs(Rung.L1, Rung.L2)

    mutate {
        val applied = response.headers["Preference-Applied"] ?: skip("no Preference-Applied")
        val doc = json() ?: skip("body is not a JSON object")
        if (Forms.embedded(doc) != null) skip("affordances are embedded, so L0 is not a lie")
        val href = Hal.link(doc, Hal.FORMS_REL)?.path("href")?.asText() ?: skip("no forms link")
        // Only where the client follows the link, so there is something to refuse.
        future?.let { seen -> if (seen.none { it.isGet && it.path == href }) skip("the client never follows $href") }
        response.headers.remove("Preference-Applied")
        val path = request.path
        detail("removed Preference-Applied: $applied; the body still links $href")

        val span = request.spanId
        // An act before the client has read $path again was made with no affordances to act from.
        var reread = false
        expect("take L0 from the header, find no affordances, and fail safe", window = 5) {
            onRequest { req ->
                when {
                    req.isGet && req.path == href -> fail("followed $href: read the rung from the body, not the header")
                    req.isGet && req.path == path -> pending().also { reread = true }
                    req.isAct && !reread -> fail("acted on ${req.path} with no affordances")
                    else -> pending()
                }
            }
            onEvent { ev ->
                if (ev.name == "forms.skipped" && ev.spanId == span) attest("its log says it skipped $href at L0") else pending()
            }
            onExpire { pass("treated $path as L0 with no affordances") }
        }
    }
}
