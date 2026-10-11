package com.artie.buster.core

import com.fasterxml.jackson.databind.JsonNode

/**
 * A test over one request, with a name that reads in a skip reason or a verdict. It is a value, so
 * a plugin can arm a mutation with it ([MutationScope.onlyWhere]) and judge the client with it
 * ([matches]) and the two say the same thing.
 */
class Matcher(val description: String, private val test: (BusterRequest) -> Boolean) {
    fun matches(req: BusterRequest): Boolean = test(req)

    /** The same request, carrying a request directive in Cache-Control. */
    fun with(cacheControl: String): Matcher = Matcher("$description with Cache-Control: $cacheControl") { req ->
        test(req) && req.headers["Cache-Control"].orEmpty().split(',').any { it.trim() == cacheControl }
    }

    override fun toString() = description
}

/** What a plugin says about its client in a mutation and in an expectation. */
interface Matchers {
    /** A GET of exactly [path]. */
    fun get(path: String): Matcher = Matcher("GET $path") { it.isGet && it.path == path }

    /** Any request that is not a read. */
    val anyAct: Matcher get() = Matcher("an act") { it.isAct }

    /** An act that carries this If-Match. */
    fun act(ifMatch: String): Matcher = Matcher("an act with If-Match $ifMatch") { it.isAct && it.ifMatch == ifMatch }

    /** An act on this affordance: its method, and a path its target template expands to. */
    fun act(on: JsonNode): Matcher =
        Matcher("an act on ${Forms.method(on)} ${Forms.target(on)}") { Forms.actsOn(it, on) }

    /** An act made at exactly [path]. */
    fun actAt(path: String): Matcher = Matcher("an act at $path") { it.isAct && it.path == path }

    /** [method] at exactly [path]. */
    fun request(method: String, path: String): Matcher =
        Matcher("$method $path") { it.method.equals(method, ignoreCase = true) && it.path == path }

    /** Any request, of any method, to exactly [path]. */
    fun anyRequestTo(path: String): Matcher = Matcher("a request to $path") { it.path == path }

    /** Any one of [matchers]. */
    fun anyOf(vararg matchers: Matcher): Matcher =
        Matcher(matchers.joinToString(" or ") { it.description }) { req -> matchers.any { it.matches(req) } }
}

/** Reads as `onlyWhere(client does get("/beds/2"))`. */
object Client {
    infix fun does(matcher: Matcher): Matcher = matcher
}
