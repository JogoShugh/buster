// SPEC §6.5: a client follows links and never reads meaning from a URL or builds one.
// The forms link gets a query the server did not write. The server ignores unknown query
// parameters and serves the same bytes, so integrity still holds. A client that follows
// the link it was given keeps working; one that requests the bare path built the URL.
plugin("alias-forms-href") {
    spec("§6.5")
    on(Layer.RESOURCE)
    rungs(Rung.L1, Rung.L2)

    mutate {
        val doc = json() ?: skip("body is not a JSON object")
        val link = Hal.link(doc, Hal.FORMS_REL) ?: skip("no forms link")
        val href = link.path("href").asText()
        if ('?' in href) skip("forms link already has a query")
        // Silence here is a FAIL: a client that never follows the link was never tested. So the
        // mutation is made only where the dry run shows this client following it.
        val seen = future ?: skip("no dry run, so silence would be a guess")
        if (seen.none { it.isGet && it.path == href }) skip("the client never follows $href in its dry run")
        val alias = "buster=%08x".format(random.nextInt())
        link.put("href", "$href?$alias")
        writeJson(doc)
        detail("forms link $href became $href?$alias")

        expect("follow the aliased forms link as given", window = 5) {
            onRequest { req ->
                when {
                    req.isGet && req.path == href && req.query.orEmpty().contains(alias) -> pass("followed $href?$alias")
                    req.isGet && req.path == href -> fail("requested $href without the alias: built the URL itself")
                    else -> pending()
                }
            }
            onExpire { fail("never followed the forms link, which its dry run did") }
        }
    }
}
