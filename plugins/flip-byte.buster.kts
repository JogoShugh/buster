// SPEC §6.5: a client SHALL verify the content address of an affordance set or schema over
// the received bytes, and refuse and never cache bytes that do not match.
// One letter inside a JSON string changes, so the body is still valid JSON and only the
// integrity check can catch it. The engine sends it no-store, so no shared cache keeps it.
// The client fetched these bytes because it needed them. Acting before it refetches the
// same URL means it used the corrupted copy.
plugin("flip-byte") {
    spec("§6.5")
    on(Layer.AFFORDANCE_SET, Layer.SCHEMA)

    mutate {
        // Read the affordances before the flip, so the expectation knows which acts they allow.
        // A schema has no targets of its own: any act of the client's uses it.
        val forms = json()?.let { Forms.embedded(it) }?.elements()?.asSequence()?.toList()
        val uses = if (forms == null) anyAct else anyOf(*forms.map { act(on = it) }.toTypedArray())
        onlyWhere(client does uses)
        flipLetterInString()
        val path = request.path
        val layer = classification.layer

        expect("refuse the corrupted $layer and never act from it", window = 5) {
            seeing(logs("integrity.refused").thisResponse) attests "its log says it refused $path"
            seeing(get(path)) passes "refetched $path"
            seeing(uses) fails { "acted on ${it.path} before refetching $path" }
            silence passes "neither refetched $path nor acted from it"
        }
    }
}
