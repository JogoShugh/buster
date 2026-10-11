// SPEC §6.5: a client SHALL NOT follow a forms link that carries no integrity,
// because it cannot verify the affordance set before caching it.
plugin("strip-forms-integrity") {
    spec("§6.5")
    on(Layer.RESOURCE)
    rungs(Rung.L1, Rung.L2)

    mutate {
        val doc = jsonBody
        val link = doc.formsLink ?: skip("no forms link")
        val href = doc.formsHref ?: skip("forms link has no href")
        link.remove("integrity") ?: skip("forms link already has no integrity")
        // Only where the client follows the link, so there is something to refuse.
        val follows = get(href)
        onlyWhere(client does follows)
        writeJson(doc)
        detail("removed integrity from the forms link $href")

        expect("refuse to follow a forms link without integrity", window = 5) {
            // Refused before any request, so the event names the link by its URL, not a span.
            seeing(logs("integrity.refused").about(href)) attests "its log says it refused $href"
            seeing(follows) fails "followed $href"
            superseded passes "read the resource again, and was answered a whole representation, before following $href"
            silence passes "never followed $href"
        }
    }
}
