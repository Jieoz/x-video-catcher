package com.jiesa.xvideocatcher.hook

import java.lang.reflect.Modifier

/**
 * Searches an object graph for host tweet models.
 *
 * ## Why this exists separately from [HostResolver.tweetFieldIn]
 *
 * `tweetFieldIn` answers "does *this class* declare a tweet field" — a one-hop question about a
 * class. The 1.6.0-probe device log showed one hop is not enough: the Compose share sheet's row
 * provider and its dispatchers hold a `Context`, coroutine plumbing and the share **URL string**,
 * with no tweet one level down either.
 *
 * The tweet is still in the process — the sheet was opened from a tweet detail screen that holds it
 * — so the search has to go deeper and start from more places. That is a different algorithm from
 * `tweetFieldIn`, and keeping it in a pure object is what makes it testable without a device.
 *
 * ## It reports candidates; it does not pick a winner
 *
 * Deciding *which* `com.twitter.model.core.*` object is the tweet being shared is exactly what this
 * build does not yet know. An earlier draft guessed by field count, which invented a second,
 * conflicting definition of "is a tweet" next to [HostResolver.isTweetModel]. So the predicate lives
 * in one place only, and this returns every match in breadth-first order. The caller runs the real
 * [TweetMedia] extractor over the candidates and logs what each yields — that is evidence about the
 * shipping path rather than a guess about it.
 *
 * ## Bounded on purpose
 *
 * This runs in someone else's foreground app, on a tap. An unbounded reflective walk reaches the
 * whole heap through `Context` and would freeze X's UI, so [MAX_DEPTH] caps hops, [MAX_VISITS] caps
 * objects examined (the real protection: depth says nothing about breadth when one node is a map
 * with thousands of entries), and the JDK is never entered. Identity is used for the visited set
 * because host models override `equals` and value-equality would prune distinct tweets.
 */
internal object TweetSearch {

    /** Hops from a root. 6 covers activity -> fragment -> viewmodel -> state -> item -> tweet. */
    const val MAX_DEPTH = 6

    /** Objects examined per search, across all roots. */
    const val MAX_VISITS = 4000

    /** Candidates reported per search. Enough to see the shape without flooding a user's log. */
    const val MAX_CANDIDATES = 8

    /** One reachable tweet-model candidate and the path walked to reach it. */
    internal data class Candidate(val value: Any, val path: String, val depth: Int)

    /** What a search found, plus the budget it spent. */
    internal data class Outcome(
        val candidates: List<Candidate>,
        val visits: Int,
        val exhausted: Boolean,
    )

    /**
     * Breadth-first search for tweet models reachable from any of [roots].
     *
     * Level-synchronous across all roots at once, so "nearest" is nearest globally rather than
     * nearest within whichever root happened to be searched first. [roots] share one visit budget
     * and [Outcome.exhausted] reports when it ran out, so a wasteful early root cannot silently
     * starve a later one.
     */
    fun find(roots: List<Pair<String, Any?>>): Outcome {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val found = mutableListOf<Candidate>()
        var visits = 0

        var frontier = roots.mapNotNull { (name, value) -> value?.let { name to it } }
        var depth = 0

        while (frontier.isNotEmpty() && depth <= MAX_DEPTH) {
            val next = mutableListOf<Pair<String, Any>>()
            for ((path, node) in frontier) {
                if (!seen.add(node)) continue
                if (++visits > MAX_VISITS) {
                    return Outcome(found, visits, exhausted = true)
                }

                if (HostResolver.isTweetModel(node.javaClass)) {
                    found.add(Candidate(node, path, depth))
                    if (found.size >= MAX_CANDIDATES) return Outcome(found, visits, false)
                    // Do not descend into a candidate: its own fields are the tweet's internals,
                    // and a quoted tweet hanging off it is a different tweet, reported separately
                    // if it is reachable another way.
                    continue
                }

                if (depth == MAX_DEPTH) continue
                for ((label, child) in childrenOf(node)) next.add("$path.$label" to child)
            }
            frontier = next
            depth++
        }
        return Outcome(found, visits, exhausted = false)
    }

    /** Children worth walking, labelled for the path report. */
    private fun childrenOf(node: Any): List<Pair<String, Any>> {
        val out = mutableListOf<Pair<String, Any>>()

        // Containers hold models; walk their elements rather than their internal structure.
        when (node) {
            is Collection<*> -> {
                node.asSequence().filterNotNull().take(MAX_FANOUT)
                    .forEachIndexed { i, v -> out.add("[$i]" to v) }
                return out
            }
            is Map<*, *> -> {
                node.values.asSequence().filterNotNull().take(MAX_FANOUT)
                    .forEachIndexed { i, v -> out.add("{$i}" to v) }
                return out
            }
            is Array<*> -> {
                node.asSequence().filterNotNull().take(MAX_FANOUT)
                    .forEachIndexed { i, v -> out.add("[$i]" to v) }
                return out
            }
        }

        if (!isTraversable(node.javaClass)) return out

        var c: Class<*>? = node.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                val v = runCatching {
                    f.isAccessible = true
                    f.get(node)
                }.getOrNull() ?: continue
                if (v is String || v is Number || v is Boolean || v is Char) continue
                out.add(f.name to v)
            }
            c = c.superclass
        }
        return out
    }

    /**
     * Whether to walk *into* an object's fields.
     *
     * Host and Android framework classes are both entered: the detail screen's tweet hangs off a
     * `Fragment`/`ViewModel`, so refusing framework objects would cut the only path to it. The JDK
     * and Kotlin runtime internals are refused — no host model lives under `java.*`, and entering a
     * `HashMap`'s node array spends the visit budget on nothing.
     */
    private fun isTraversable(cls: Class<*>): Boolean {
        val n = cls.name
        return !(
            n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("sun.") ||
                n.startsWith("kotlin.jvm.internal.") || n.startsWith("dalvik.")
            )
    }

    /** Per-container element cap: a timeline list is long and the shared tweet is near its head. */
    private const val MAX_FANOUT = 64
}
