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
        /**
         * Object count per two-segment package prefix, most frequent first.
         *
         * Diagnostic only -- nothing in the search reads this. It exists because a package-prefix
         * predicate matching nothing looks exactly like a graph holding no tweet, and on a device we
         * cannot attach a debugger to, that ambiguity costs a release each time.
         */
        val census: List<Pair<String, Int>> = emptyList(),
        /**
         * Objects refused as dependency-injection plumbing, by two-segment prefix.
         *
         * Reported for the same reason as [census]: pruning that silently does nothing looks
         * exactly like pruning that works, and the 20260804 log spent ~24% of its budget inside
         * `dagger.internal` before this existed.
         */
        val pruned: List<Pair<String, Int>> = emptyList(),
    )

    /**
     * Breadth-first search for tweet models reachable from any of [roots].
     *
     * Level-synchronous across all roots at once, so "nearest" is nearest globally rather than
     * nearest within whichever root happened to be searched first.
     *
     * [maxVisits] and [maxDepth] default to the UI-thread budget. They are parameters so the
     * diagnostic sweep can run the shipping traversal at a size a share tap cannot afford,
     * rather than a second copy of it that would prove nothing about this code. [roots] share one visit budget
     * and [Outcome.exhausted] reports when it ran out, so a wasteful early root cannot silently
     * starve a later one.
     */
    fun find(
        roots: List<Pair<String, Any?>>,
        maxVisits: Int = MAX_VISITS,
        maxDepth: Int = MAX_DEPTH,
    ): Outcome {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val found = mutableListOf<Candidate>()
        val census = HashMap<String, Int>()
        val pruned = HashMap<String, Int>()
        var visits = 0

        // Roots get the same refusal as children. A caller can hand this a DI wrapper directly --
        // the probe's receiver is whatever object the host dispatched from -- and walking one costs
        // hundreds of visits for a subtree that cannot hold request state. Counted in `pruned`, so
        // a root that was refused is visible rather than looking like an empty graph.
        var frontier = roots.mapNotNull { (name, value) ->
            value?.let {
                if (isInjectionPlumbing(it.javaClass)) {
                    val p = packagePrefix(it.javaClass.name)
                    pruned[p] = (pruned[p] ?: 0) + 1
                    null
                } else {
                    name to it
                }
            }
        }
        var depth = 0

        while (frontier.isNotEmpty() && depth <= maxDepth) {
            val next = mutableListOf<Pair<String, Any>>()
            for ((path, node) in frontier) {
                if (!seen.add(node)) continue
                if (++visits > maxVisits) {
                    return Outcome(
                        found, visits, exhausted = true,
                        census = packageCensus(census), pruned = packageCensus(pruned),
                    )
                }
                val prefix = packagePrefix(node.javaClass.name)
                census[prefix] = (census[prefix] ?: 0) + 1

                if (HostResolver.isTweetModel(node.javaClass)) {
                    found.add(Candidate(node, path, depth))
                    if (found.size >= MAX_CANDIDATES)
                        return Outcome(
                            found, visits, false,
                            packageCensus(census), packageCensus(pruned),
                        )
                    // Do not descend into a candidate: its own fields are the tweet's internals,
                    // and a quoted tweet hanging off it is a different tweet, reported separately
                    // if it is reachable another way.
                    continue
                }

                if (depth == maxDepth) continue

                // A DI provider is a hub to the entire application singleton graph. Walking one
                // costs hundreds of visits and cannot pay out, because a tweet is request state,
                // never an injected singleton.
                //
                // Refused here, as a child, rather than on arrival: charging a visit for an object
                // we have already decided not to walk spent 28% of the budget on 20260804, where
                // `census dagger.internal.d` and `pruned dagger.internal.d` were both 967. Counted
                // in `pruned` either way, so a prune that matches nothing is still visible.
                for ((label, child) in childrenOf(node)) {
                    if (isInjectionPlumbing(child.javaClass)) {
                        val p = packagePrefix(child.javaClass.name)
                        pruned[p] = (pruned[p] ?: 0) + 1
                        continue
                    }
                    next.add("$path.$label" to child)
                }
            }
            frontier = next
            depth++
        }
        return Outcome(
            found, visits, exhausted = false,
            census = packageCensus(census), pruned = packageCensus(pruned),
        )
    }

    /** Children worth walking, labelled for the path report. */
    private fun childrenOf(node: Any): List<Pair<String, Any>> {
        val out = mutableListOf<Pair<String, Any>>()

        // A container is transparent: it contributes what it holds to the *current* level.
        //
        // Enqueuing the container itself used to cost a level of MAX_DEPTH, so an
        // `object -> List -> object` chain -- how the host actually stores a timeline -- consumed
        // two levels per model hop and halved the real reach to ~3 hops. Measured before this
        // change, a tweet 4 object-hops from the root was NOT found despite MAX_DEPTH = 6.
        if (isContainer(node)) {
            flattenContainer(node, "", out, 0)
            return out
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
                // Flatten a container field in place, for the same reason: the list is not a hop.
                if (isContainer(v)) flattenContainer(v, f.name, out, 0) else out.add(f.name to v)
            }
            c = c.superclass
        }
        return out
    }

    /** Whether [v] is a container the walk should see through rather than treat as a hop. */
    private fun isContainer(v: Any): Boolean =
        v is Collection<*> || v is Map<*, *> || v is Array<*>

    /**
     * Append [container]'s elements to [out], flattening nested containers.
     *
     * [nesting] bounds list-of-list recursion so a pathological structure cannot expand one level
     * without limit; each container level is independently capped at [MAX_FANOUT] elements.
     */
    private fun flattenContainer(
        container: Any,
        prefix: String,
        out: MutableList<Pair<String, Any>>,
        nesting: Int,
    ) {
        val values: Sequence<Any> = when (container) {
            is Collection<*> -> container.asSequence().filterNotNull()
            is Map<*, *> -> container.values.asSequence().filterNotNull()
            is Array<*> -> container.asSequence().filterNotNull()
            else -> return
        }

        values.take(MAX_FANOUT).forEachIndexed { i, v ->
            val label = if (prefix.isEmpty()) "[$i]" else "$prefix[$i]"
            when {
                // Collapse a nested container into this level, up to the bound.
                isContainer(v) && nesting < MAX_CONTAINER_NESTING ->
                    flattenContainer(v, label, out, nesting + 1)
                // Past the bound, hand it to the walk rather than discarding it.
                else -> out.add(label to v)
            }
        }
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

    /**
     * Whether [cls] is dependency-injection plumbing whose fields lead away from request state.
     *
     * Matched on the DI framework's own packages rather than on X's classes: Dagger's generated
     * factories live under the host's packages and are named by R8, so there is nothing stable to
     * match there, while `dagger.internal.*` is library code X does not rename. The 20260804 log
     * named these exact prefixes as the top budget consumer, which is the whole reason this exists.
     */
    private fun isInjectionPlumbing(cls: Class<*>): Boolean {
        val n = cls.name
        return n.startsWith("dagger.") || n.startsWith("javax.inject.")
    }

    /** Per-container element cap: a timeline list is long and the shared tweet is near its head. */
    private const val MAX_FANOUT = 64

    /**
     * How many levels of nested container to collapse into a single walk level.
     *
     * Containers are transparent, so nesting must be bounded or a list-of-lists could expand one
     * level without limit. Three covers the shapes the host uses (a paged list of sections of
     * items).
     *
     * Exceeding it must never DROP the container: a first version returned empty here, which made
     * a five-deep nesting unreachable that the pre-transparency code found without trouble. Past
     * the bound the container is emitted as a child instead, so the walk reaches it on the next
     * level exactly as it used to.
     */
    private const val MAX_CONTAINER_NESTING = 3
}

/** First three segments of a class name, e.g. `com.x.models` -- `com.x` alone merges unrelated trees. */
internal fun packagePrefix(className: String): String {
    var seen = 0
    for ((i, ch) in className.withIndex()) {
        if (ch == '.') {
            seen++
            if (seen == 3) return className.substring(0, i)
        }
    }
    return className
}

/** [counts] as a descending list, ties broken by name so the log is stable between runs. */
internal fun packageCensus(counts: Map<String, Int>, limit: Int = 12): List<Pair<String, Int>> =
    counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { it.key to it.value }
