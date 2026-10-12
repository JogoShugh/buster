// SPEC §6.11.1, §6.4: a client SHALL take the rung from Preference-Applied, and no header
// means L0. Removing the header from an L1 or L2 response says the affordances arrived
// embedded. They did not, so a client that trusts the header finds none and fails safe:
// it neither follows the forms link it was not told about nor acts.
plugin("lie-preference-applied") {
    spec("§6.11.1", "§6.4")
    on(Layer.RESOURCE)
    rungs(Rung.L1, Rung.L2)

    mutate {
        header("Preference-Applied").value ?: skip("no Preference-Applied")
        val doc = jsonBody
        skipIf(Forms.embedded(doc) != null, "affordances are embedded, so L0 is not a lie")
        val href = doc.formsHref ?: skip("no forms link")
        // Only where the client follows the link, so there is something to refuse.
        val follows = get(href)
        onlyWhere(client does follows)
        header("Preference-Applied").remove()
        detail("the body still links $href")

        expect("take L0 from the header, find no affordances, and fail safe", window = 5) {
            // An act before the client has read the resource again was made with no affordances.
            val rereads = get(resource)
            seeing(follows) fails "followed $href: read the rung from the body, not the header"
            seeing(anyAct) { req ->
                if (already(rereads)) pending() else fail("acted on ${req.path} with no affordances")
            }
            seeing(logs("forms.skipped").thisResponse) attests "its log says it skipped $href at L0"
            superseded passes "read $resource again, and was answered a whole representation, before following $href"
            silence passes "treated $resource as L0 with no affordances"
        }
    }
}
