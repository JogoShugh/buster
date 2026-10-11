// SPEC §6.2: the manifest is the only source of legal acts. A client SHALL NOT act on an
// affordance the manifest does not list, even one it saw before.
// Applies at L0, where the affordances are embedded in Layer 1 and carry no integrity.
plugin("drop-affordance") {
    spec("§6.2")
    on(Layer.RESOURCE)
    rungs(Rung.L0)

    mutate {
        val doc = json() ?: skip("body is not a JSON object")
        val forms = Forms.embedded(doc) ?: skip("no embedded affordances")
        val names = forms.fieldNames().asSequence().toList()
        if (names.isEmpty()) skip("no affordances")
        // With a dry run, drop the affordance the client goes on to act on. Without one, any.
        val name = future?.let { seen ->
            names.firstOrNull { n -> seen.any { Forms.actsOn(it, forms.get(n)) } }
                ?: skip("the client never acts on an affordance of ${request.path} in its dry run")
        } ?: names[random.nextInt(names.size)]
        val dropped = forms.get(name)
        val method = Forms.method(dropped)
        val target = Forms.target(dropped) ?: skip("affordance $name has no target")
        forms.remove(name)
        // Another affordance with the same method and target would make an act on it legal.
        if (forms.elements().asSequence().any { Forms.method(it) == method && Forms.target(it) == target }) {
            skip("another affordance shares $method $target")
        }
        writeJson(doc)
        detail("dropped affordance $name ($method $target)")

        expect("never act on the dropped affordance $name", window = 5) {
            // After a 412 a client re-reads the resource, finds the affordance gone and drops its
            // act. Its log says so against the re-read's span, not this response's, so the event
            // is matched by client and relation.
            onEvent { ev ->
                if (ev.name == "affordance.absent" && ev.attributes["heart.rel"] == name) {
                    attest("its log says it found $name gone")
                } else {
                    pending()
                }
            }
            onRequest { req -> if (Forms.actsOn(req, dropped)) fail("acted on $name: $method ${req.path}") else pending() }
            // The resource, given again unmutated, holds a manifest that may list the act again,
            // and acting on it is then what §6.2 asks.
            onSuperseded { pass("was given the resource again, unmutated, before acting on $name") }
            onExpire { pass("never acted on $name") }
        }
    }
}
