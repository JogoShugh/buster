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
        response.headers.remove("Preference-Applied")
        val path = request.path
        detail("removed Preference-Applied: $applied; the body still links $href")

        expect("take L0 from the header, find no affordances, and fail safe", window = 5) {
            onRequest { req ->
                when {
                    req.isGet && req.path == href -> fail("followed $href: read the rung from the body, not the header")
                    req.isAct && req.path.startsWith(path) -> fail("acted on ${req.path} with no affordances")
                    else -> pending()
                }
            }
            onExpire { pass("treated $path as L0 with no affordances") }
        }
    }
}
