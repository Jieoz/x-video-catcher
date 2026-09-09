package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog

/**
 * Real class names read out of the host's own dex files.
 *
 * ## Why this exists
 *
 * Every resolver in [HostResolver] searched a *guessed* name space: `a`..`z`, `a0`..`z9` inside a
 * package recorded from one build. That works only while two things hold at once — the package still
 * exists, and R8 still assigns short names inside it.
 *
 * The 20260828 device log (host `12.20.5-prod.01`) shows both assumptions failing together:
 *
 * ```
 * row provider: 0 candidates in com.x.share.impl
 * row model:    0 candidates in com.x.models.share
 * sheet open:   0 candidates in com.twitter.share.chooser
 * INJECT row-provider MISS -- no download row this session
 * ```
 *
 * Zero *candidates*, not "candidates that failed the shape test": the guessed names loaded nothing,
 * so no shape predicate ever ran. A better predicate cannot fix a wrong name space.
 *
 * Enumerating the dex removes the guess. The names come from `BaseDexClassLoader.pathList
 * .dexElements[i].dexFile.entries()` — private API, so every step is reflective and any failure
 * degrades to an empty list plus a log line, never an exception on X's thread.
 */
internal object HostDex {

    @Volatile
    private var cache: List<String>? = null

    /** Every class name in the host's dex files, or empty when enumeration is unavailable. */
    fun classNames(classLoader: ClassLoader): List<String> {
        cache?.let { return it }
        val names = ArrayList<String>(1 shl 14)
        runCatching { collect(classLoader, names) }
            .onFailure {
                DiagLog.line("$MARK enumeration failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        cache = names
        DiagLog.line("$MARK ${names.size} class name(s) enumerated from host dex")
        return names
    }

    private fun collect(classLoader: ClassLoader, out: MutableList<String>) {
        val baseDex = Class.forName("dalvik.system.BaseDexClassLoader")
        if (!baseDex.isInstance(classLoader)) {
            DiagLog.line("$MARK host loader is ${classLoader.javaClass.name}, not BaseDexClassLoader")
            return
        }
        val pathList = baseDex.getDeclaredField("pathList")
            .apply { isAccessible = true }
            .get(classLoader) ?: return
        val elements = pathList.javaClass.getDeclaredField("dexElements")
            .apply { isAccessible = true }
            .get(pathList) as? Array<*> ?: return
        for (element in elements) {
            if (element == null) continue
            val dexFile = runCatching {
                element.javaClass.getDeclaredField("dexFile")
                    .apply { isAccessible = true }
                    .get(element)
            }.getOrNull() ?: continue
            val entries = runCatching {
                dexFile.javaClass.getMethod("entries").invoke(dexFile)
            }.getOrNull() as? java.util.Enumeration<*> ?: continue
            while (entries.hasMoreElements()) {
                (entries.nextElement() as? String)?.let(out::add)
            }
        }
    }

    /**
     * Class names declared directly in [pkg], not in a sub-package.
     *
     * Exact-package on purpose: a package move must surface as "empty" here and then be named by
     * [packageCensusFor], instead of being papered over by a prefix match that quietly drags in a
     * neighbouring subtree.
     */
    fun classesIn(classLoader: ClassLoader, pkg: String): List<String> =
        namesIn(classNames(classLoader), pkg)

    /** Pure half of [classesIn], so the filter is assertable without a class loader. */
    internal fun namesIn(all: List<String>, pkg: String): List<String> {
        val prefix = "$pkg."
        return all.filter { name ->
            name.startsWith(prefix) && name.indexOf('.', prefix.length) < 0
        }
    }

    /**
     * Every class the host declares in a package whose name contains [needle].
     *
     * This is the module's search space, and it replaces the per-package lookups the resolvers used
     * up to 1.48. A recorded package name is a coordinate from one build; a needle is a word the
     * host itself chose for the *feature*, and a restructure that renames `com.x.share.impl` to
     * something else still has to call it something share-shaped or nobody inside X could find it
     * either.
     *
     * Measured on 12.20.5: needle `share` spans 28 packages and 250 classes — wide enough that the
     * row model survives a package move, and narrow enough that the shape predicates stay unique
     * inside it (they match exactly one class each on that build). Nested types are included, since
     * they carry no extra dot and so belong to their outer class's package.
     */
    fun classesMatching(classLoader: ClassLoader, needle: String): List<String> =
        namesMatching(classNames(classLoader), needle)

    /** Pure half of [classesMatching], so the search space is assertable without a class loader. */
    internal fun namesMatching(all: List<String>, needle: String): List<String> =
        all.filter { name ->
            val dot = name.lastIndexOf('.')
            dot > 0 && name.substring(0, dot).contains(needle, ignoreCase = true)
        }

    /**
     * Packages whose name contains [needle], with how many classes each declares.
     *
     * This is what turns "0 candidates" into an actionable fact: it names where the host keeps its
     * share code on the build in the user's hands, so the next anchor is read off the device rather
     * than guessed again.
     */
    fun packageCensusFor(
        classLoader: ClassLoader,
        needle: String,
        limit: Int = CENSUS_LIMIT,
    ): List<Pair<String, Int>> = censusOf(classNames(classLoader), needle, limit)

    /** Pure half of [packageCensusFor]. */
    internal fun censusOf(
        all: List<String>,
        needle: String,
        limit: Int = CENSUS_LIMIT,
    ): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        for (name in all) {
            val dot = name.lastIndexOf('.')
            if (dot <= 0) continue
            val pkg = name.substring(0, dot)
            if (!pkg.contains(needle, ignoreCase = true)) continue
            counts[pkg] = (counts[pkg] ?: 0) + 1
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key to it.value }
    }

    internal fun resetForTest() {
        cache = null
    }

    internal fun seedForTest(names: List<String>) {
        cache = names
    }

    const val CENSUS_LIMIT = 20
    private const val MARK = "HOSTDEX"
}
