// SPEC §6.1, §7.5: a client SHALL expand a CURIE through _links.curies before matching it.
// Without the curies entry, "hr:forms" does not expand, so it is not the affordance set.
plugin("drop-curie") {
    spec("§6.1", "§7.5")
    on(Layer.RESOURCE)
    rungs(Rung.L1, Rung.L2)

    mutate {
        val doc = json() ?: skip("body is not a JSON object")
        val key = Hal.linkKey(doc, Hal.FORMS_REL) ?: skip("no forms link")
        if (!key.contains(':') || key.contains("://")) skip("forms link is not a CURIE")
        val href = Hal.link(doc, Hal.FORMS_REL)?.path("href")?.asText() ?: skip("forms link has no href")
        val links = Hal.links(doc) ?: skip("no _links")
        links.remove("curies")
        writeJson(doc)
        detail("removed _links.curies, so $key no longer expands")

        val span = request.spanId
        expect("treat $key as an unknown relation", window = 5) {
            onEvent { ev ->
                if (ev.name == "forms.missing" && ev.spanId == span) attest("its log says it found no forms link") else pending()
            }
            onRequest { req -> if (req.isGet && req.path == href) fail("followed $key to $href") else pending() }
            onExpire { pass("never followed $key") }
        }
    }
}
