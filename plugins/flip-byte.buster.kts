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
        // Only where the client acts using this set or schema, so there is an act to hold back.
        // A schema has no targets of its own: any act of the client's uses it.
        future?.let { seen ->
            if (seen.none { it.isAct && (forms == null || forms.any { form -> Forms.actsOn(it, form) }) }) {
                skip("the client never acts using ${request.path} in its dry run")
            }
        }
        val at = letters[random.nextInt(letters.size)]
        val before = response.body[at].toInt().toChar()
        val after = if (before == 'x') 'y' else 'x'
        response.body = response.body.copyOf().also { it[at] = after.code.toByte() }
        val path = request.path
        val layer = classification.layer
        detail("byte $at of $path: '$before' became '$after'")

        val span = request.spanId
        expect("refuse the corrupted $layer and never act from it", window = 5) {
            onEvent { ev ->
                if (ev.name == "integrity.refused" && ev.spanId == span) attest("its log says it refused $path") else pending()
            }
            onRequest { req ->
                when {
                    req.isGet && req.path == path -> pass("refetched $path")
                    // A schema has no targets of its own: any act before the refetch used it.
                    req.isAct && (forms == null || forms.any { Forms.actsOn(req, it) }) ->
                        fail("acted on ${req.path} before refetching $path")
                    else -> pending()
                }
            }
            onExpire { pass("neither refetched $path nor acted from it") }
        }
    }
}
