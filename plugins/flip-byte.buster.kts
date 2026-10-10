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
        val letters = Hal.lettersInStrings(response.body)
        if (letters.isEmpty()) skip("no letters inside JSON strings")
        // Read the affordances before the flip, so the expectation knows which acts they allow.
        val forms = json()?.let { Forms.embedded(it) }?.elements()?.asSequence()?.toList()
        val at = letters[random.nextInt(letters.size)]
        val before = response.body[at].toInt().toChar()
        val after = if (before == 'x') 'y' else 'x'
        response.body = response.body.copyOf().also { it[at] = after.code.toByte() }
        val path = request.path
        val layer = classification.layer
        detail("byte $at of $path: '$before' became '$after'")

        expect("refuse the corrupted $layer and never act from it", window = 5) {
            onRequest { req ->
                when {
                    req.isGet && req.path == path -> pass("refetched $path")
                    // A schema has no targets of its own: any act before the refetch used it.
                    req.isAct && (forms == null || forms.any { Forms.actsOn(req, it) }) ->
                        fail("acted on ${req.path} before refetching $path")
                    else -> pending()
                }
            }
            onExpire { inconclusive("neither refetched $path nor acted") }
        }
    }
}
