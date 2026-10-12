// SPEC §6.5: a client follows links and never reads meaning from a URL or builds one.
// The forms link gets a query the server did not write. The server ignores unknown query
// parameters and serves the same bytes, so integrity still holds. A client that follows
// the link it was given keeps working; one that requests the bare path built the URL.
plugin("alias-forms-href") {
    spec("§6.5")
    on(Layer.RESOURCE)
    rungs(Rung.L1, Rung.L2)
    // A client that never follows the link was never tested, so only where its dry run does.
    silenceMeans(Silence.FAIL)

    mutate {
        val doc = jsonBody
        val link = doc.formsLink ?: skip("no forms link")
        val href = doc.formsHref ?: skip("forms link has no href")
        skipIf('?' in href, "forms link already has a query")
        val follows = get(href)
        onlyWhere(client does follows)
        val alias = "buster=%08x".format(random.nextInt())
        link.put("href", "$href?$alias")
        writeJson(doc)
        detail("forms link $href became $href?$alias")

        expect("follow the aliased forms link as given", window = 5) {
            seeing(follows) { req ->
                if (req.query.orEmpty().contains(alias)) {
                    pass("followed $href?$alias")
                } else {
                    fail("requested $href without the alias: built the URL itself")
                }
            }
            superseded passes "read the resource again, and was answered a whole representation, before following $href"
            silence fails "never followed the forms link, which its dry run did"
        }
    }
}
