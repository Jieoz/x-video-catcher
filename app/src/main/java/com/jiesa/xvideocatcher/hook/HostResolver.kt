package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Resolves the host's share-sheet classes by structure, verifying every candidate before use.
 *
 * Why this exists: [HostClasses.SHARE_SHEET_CONTROLLER] was read out of X 12.13.0-beta.0, where
 * the controller compiled to `com.twitter.tweet.action.legacy.h0`. In 12.13.0-release.0 the same
 * class is `e0`, and `h0` is a *different, unrelated* class that also exists. `loadClass("…h0")`
 * therefore succeeded and the module reported "controller resolved", then failed to find the show
 * method on it — one wrong class name produced two misleading FATAL lines and no download entry.
 *
 * The fix is to stop trusting any obfuscated name on its own. A name is only ever a candidate
 * here; it is accepted only if the class carries the controller's shape:
 *
 *  - a `void` method taking exactly one `FragmentManager` (shows the sheet)
 *  - a `java.util.List` instance field (the rendered entries)
 *  - a field typed to the tweet wrapper (the tweet the sheet was opened for)
 *
 * Verified against the real 12.13.0-release.0 APK: exactly two classes in the whole app declare
 * `void(FragmentManager)` plus a `List` field, and the other is `BaseConversationActionsDialog`
 * (3 fields, unobfuscated name, not in a tweet-action package). Requiring the tweet-wrapper field
 * as well leaves the controller unique.
 *
 * Search order is cheapest-first, so the common case costs one `loadClass`:
 *  1. the recorded name, structurally verified
 *  2. sibling names in the same package (`a`..`z`, `a0`..`z9`) — R8 keeps packages, so the class
 *     moves within its package rather than out of it
 *  3. give up and log why, leaving X untouched
 */
internal object HostResolver {

    /** Result of a successful controller resolution. */
    internal data class Controller(
        val cls: Class<*>,
        val showMethod: Method,
    )

    /**
     * Finds the share-sheet controller, or null.
     *
     * Never throws: a miss must degrade to "no download entry", never to an exception inside X.
     */
    fun shareSheetController(classLoader: ClassLoader): Controller? {
        val tried = mutableListOf<String>()

        for (name in candidateNames(HostClasses.SHARE_SHEET_CONTROLLER)) {
            val cls = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
            val show = showMethodOf(cls)
            if (show == null) {
                tried.add("$name(no-show)")
                continue
            }
            if (listField(cls) == null) {
                tried.add("$name(no-list)")
                continue
            }
            if (tweetField(cls) == null) {
                tried.add("$name(no-tweet)")
                continue
            }
            if (name != HostClasses.SHARE_SHEET_CONTROLLER) {
                DiagLog.line(
                    "controller drifted: recorded ${HostClasses.SHARE_SHEET_CONTROLLER} " +
                        "is not it; resolved $name by shape"
                )
            }
            return Controller(cls, show)
        }

        DiagLog.line("FATAL no class in ${packageOf(HostClasses.SHARE_SHEET_CONTROLLER)} has the")
        DiagLog.line("      controller shape (void(FragmentManager) + List + tweet field).")
        DiagLog.line("      rejected: ${tried.take(12).joinToString(", ")}")
        return null
    }

    /**
     * The item-model class (`ActionSheetItem`), verified by its constructor shape.
     *
     * Identified by the `(int drawableRes, int actionId, String title)` constructor, which is the
     * one the module uses to build an entry. Confirmed still present and unique within
     * `com.twitter.ui.dialog.actionsheet` in 12.13.0-release.0.
     */
    fun actionSheetItem(classLoader: ClassLoader): Class<*>? {
        for (name in candidateNames(HostClasses.ACTION_SHEET_ITEM)) {
            val cls = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
            if (entryConstructor(cls) != null) {
                if (name != HostClasses.ACTION_SHEET_ITEM) {
                    DiagLog.line("item model drifted: resolved $name by ctor shape")
                }
                return cls
            }
        }
        DiagLog.line("FATAL no (int,int,String) ctor in ${packageOf(HostClasses.ACTION_SHEET_ITEM)}")
        return null
    }

    /** The `(int, int, String)` constructor used to build an entry, or null. */
    fun entryConstructor(itemClass: Class<*>) =
        itemClass.declaredConstructors.firstOrNull { c ->
            val p = c.parameterTypes
            p.size == 3 &&
                p[0] == Int::class.javaPrimitiveType &&
                p[1] == Int::class.javaPrimitiveType &&
                p[2] == String::class.java
        }?.also { it.isAccessible = true }

    /** `void m(FragmentManager)` declared on [cls], or null. Name is never consulted. */
    fun showMethodOf(cls: Class<*>): Method? =
        cls.declaredMethods.firstOrNull { m ->
            m.returnType == Void.TYPE &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0].name == FRAGMENT_MANAGER &&
                !Modifier.isStatic(m.modifiers)
        }?.also { it.isAccessible = true }

    /** The controller's entry list: its only `java.util.List` instance field. */
    fun listField(cls: Class<*>) =
        cls.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && it.type == List::class.java }
            .singleOrNull()
            ?.also { it.isAccessible = true }

    /**
     * The field holding the tweet wrapper.
     *
     * Matched by package rather than exact class name: the wrapper's own name drifts too, but it
     * stays in `com.twitter.model.core`. Requiring exactly one match keeps this from latching onto
     * an unrelated model object.
     */
    fun tweetField(cls: Class<*>) =
        cls.declaredFields
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.type.name.startsWith(TWEET_MODEL_PACKAGE) &&
                    !it.type.isEnum
            }
            .let { fields ->
                fields.singleOrNull()
                    // 12.13 has one; if a build adds a second model field, prefer the one whose
                    // class declares the most fields — the tweet body is the fat object here.
                    ?: fields.maxByOrNull { it.type.declaredFields.size }
            }
            ?.also { it.isAccessible = true }

    /**
     * Candidate names for a recorded class: the record itself, then its siblings.
     *
     * R8 renames classes within their package, so a drifted controller is still in
     * `com.twitter.tweet.action.legacy`. The sibling space is generated in the same shape R8 uses
     * (`a`..`z`, then `a0`..`z9`), recorded name first so the common case is a single hit.
     */
    private fun candidateNames(recorded: String): Sequence<String> = sequence {
        yield(recorded)
        val pkg = packageOf(recorded)
        val seen = mutableSetOf(recorded)
        // Two-char names (a0..z9) first: the recorded names are all in that shape, so a drift
        // most likely landed on another two-char name.
        for (c in 'a'..'z') {
            for (d in '0'..'9') {
                val n = "$pkg.$c$d"
                if (seen.add(n)) yield(n)
            }
        }
        for (c in 'a'..'z') {
            val n = "$pkg.$c"
            if (seen.add(n)) yield(n)
        }
    }

    private fun packageOf(className: String) = className.substringBeforeLast('.')

    private const val FRAGMENT_MANAGER = "androidx.fragment.app.FragmentManager"
    private const val TWEET_MODEL_PACKAGE = "com.twitter.model.core."
}
