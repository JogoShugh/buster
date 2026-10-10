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
        val name = names[random.nextInt(names.size)]
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
            onRequest { req -> if (Forms.actsOn(req, dropped)) fail("acted on $name: $method ${req.path}") else pending() }
            onExpire { pass("never acted on $name") }
        }
    }
}
