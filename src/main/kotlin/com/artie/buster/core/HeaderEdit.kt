package com.artie.buster.core

/**
 * One header of the response a plugin is changing. Every change is noted in the mutation's
 * detail as it is made, so the log says what the plugin did, not what it meant to do.
 */
class HeaderEdit internal constructor(
    private val name: String,
    private val headers: Headers,
    private val note: (String) -> Unit,
) {
    /** What the header holds now, or null. */
    val value: String? get() = headers[name]

    /** Sets the header, and returns what it was set to. */
    fun replace(new: String): String {
        val old = headers[name]
        headers[name] = new
        note(if (old == null) "$name set to $new" else "$name $old became $new")
        return new
    }

    /** Removes the header, and returns what it held; null, with nothing noted, when it was not there. */
    fun remove(): String? {
        val old = headers[name] ?: return null
        headers.remove(name)
        note("removed $name: $old")
        return old
    }
}
