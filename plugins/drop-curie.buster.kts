// SPEC §6.1, §7.5: a client SHALL expand a CURIE through _links.curies before matching it.
// Without the curies entry, "hr:forms" does not expand, so it is not the affordance set.
plugin("drop-curie") {
    spec("§6.1", "§7.5")
    on(Layer.RESOURCE)
    rungs(Rung.L1, Rung.L2)

    mutate {
        val doc = jsonBody
        val key = Hal.linkKey(doc, Hal.FORMS_REL) ?: skip("no forms link")
        skipUnless(':' in key && "://" !in key, "forms link is not a CURIE")
        val href = doc.formsHref ?: skip("forms link has no href")
        // Only where the client follows the link, so there is something to refuse.
        val follows = get(href)
        onlyWhere(client does follows)
        val links = Hal.links(doc) ?: skip("no _links")
        links.remove("curies")
        writeJson(doc)
        detail("removed _links.curies, so $key no longer expands")

        expect("treat $key as an unknown relation", window = 5) {
            seeing(logs("forms.missing").thisResponse) attests "its log says it found no forms link"
            seeing(follows) fails "followed $key to $href"
            superseded passes "read the resource again, and was answered a whole representation, before following $key"
            silence passes "never followed $key"
        }
    }
}
