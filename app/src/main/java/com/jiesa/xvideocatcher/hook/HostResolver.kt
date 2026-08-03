package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Resolves the host's *live* share sheet by structure, verifying every candidate before use.
 *
 * ## Why the previous anchors were deleted, not kept as a fallback
 *
 * Versions 1.2–1.4 hooked, in turn, a tweet-action controller, an action-sheet bind method, and an
 * override of that bind method. All three installed cleanly and never fired. Instruction-level
 * cross-referencing of the real APK finally explained it: `com.twitter.app.share.ui.d.n0` — the
 * 1.4.0 anchor — has **zero call sites in the entire application**, as does
 * `ShareSheetDialogFragment`. That whole View-based sheet is dead code in 12.13.
 *
 * The lesson is about the *method*, not the class: shape verification cannot detect a dead class,
 * because a dead class still has the right shape. Three releases reported "resolved" and "hooked"
 * while pointing at code the host never executes. Anchors are therefore now chosen by reachability
 * first — established with a disassembler — and confirmed by shape second.
 *
 * Keeping the old constants as a fallback would only preserve a path proven unreachable, so they
 * are gone.
 *
 * ## What the live sheet looks like
 *
 * `chooser.j.J0` attaches a `ComposeView` to the Activity's decor view and calls `setContent`. It is
 * Compose: there is no view hierarchy to insert a row into, and no adapter list holding item views.
 * So the module targets the **data** the sheet renders from:
 *
 *  - [rowClass] — the row model: 3 `String`s, a `Drawable`, a `boolean`.
 *  - [rowProvider] — `(String) -> ArrayList` on the class that owns a `Context` and can hand out a
 *    `PackageManager`; this is what enumerates shareable apps, and its return value is the row list.
 *  - [actionClass] / [dispatchPoints] — the sealed action carrying a chosen row, and every method
 *    that receives one. Three classes declare it (interface plus two implementations) and all are
 *    hooked, for the same reason 1.4.0 needed both `n0` declarations: an override that does not call
 *    `super` is its own entry point.
 *  - [sheetOpen] — `chooser.j.J0`, hooked only to record that the sheet opened at all. Without an
 *    unconditional "panel opened" record, "the hook never fired" and "the log never landed" are the
 *    same symptom, which is what made the last three diagnoses ambiguous.
 *
 * ## Anchoring rules
 *
 * No obfuscated name is trusted, and no class name is hard-coded except
 * `com.twitter.app.common.dialog.BaseDialogFragment`, which the host instantiates by name so R8 must
 * keep it. Everything else is found by enumerating short obfuscated names within a recorded package
 * (`a`..`z`, `a0`..`z9`, plus nested `x$y`) and accepting only on shape. R8 renames a class within
 * its package rather than moving it out, so the package is a valid search space; each predicate below
 * was measured to match exactly one class inside that space on 12.13.0-release.0.
 *
 * Every resolver returns null/empty rather than throwing. A miss must degrade to "no download row",
 * never to an exception on X's UI thread.
 */
internal object HostResolver {

    /** A method that receives a sheet action, plus the action root type it accepts. */
    internal data class DispatchPoint(
        val method: Method,
        val actionRoot: Class<*>,
    )

    /**
     * The share-row model: exactly 3 `String` + 1 `Drawable` + 1 `boolean`, with data-class methods.
     *
     * Verified unique inside `com.x.models.share` (real: `models.share.a`, holding package name,
     * activity name, label, icon, and a flag).
     */
    fun rowClass(classLoader: ClassLoader): Class<*>? {
        val hits = candidateClasses(classLoader, HostClasses.SHARE_ROW_PACKAGE)
            .filter { isRowShape(it) }
            .toList()
        if (hits.size != 1) {
            DiagLog.line("row model: ${hits.size} candidates in ${HostClasses.SHARE_ROW_PACKAGE}")
            return null
        }
        return hits[0]
    }

    /** Shape match for one share row, split out so it can be asserted on directly. */
    internal fun isRowShape(cls: Class<*>): Boolean {
        val fields = instanceFields(cls)
        if (fields.size != ROW_FIELD_COUNT) return false
        val strings = fields.count { it.type == String::class.java }
        val drawables = fields.count { it.type.name == DRAWABLE }
        val booleans = fields.count { it.type == Boolean::class.javaPrimitiveType }
        if (strings != 3 || drawables != 1 || booleans != 1) return false
        val methods = cls.declaredMethods.map { it.name }.toSet()
        return methods.containsAll(listOf("equals", "hashCode", "toString"))
    }

    /**
     * The method building the row list: `(String) -> ArrayList` on a class holding a `Context` and
     * declaring a no-arg `PackageManager` getter.
     *
     * Verified unique inside `com.x.share.impl` (real: `share.impl.c.a`). `ArrayList` rather than
     * `List` in the signature is deliberate — it is what the host declares, and it is also what
     * makes appending safe: the concrete return type is mutable, so a row can be added in place
     * without replacing the object the caller already holds.
     */
    fun rowProvider(classLoader: ClassLoader): Method? {
        val hits = mutableListOf<Method>()
        for (cls in candidateClasses(classLoader, HostClasses.SHARE_IMPL_PACKAGE)) {
            if (instanceFields(cls).none { it.type == android.content.Context::class.java }) continue
            val hasPmGetter = cls.declaredMethods.any {
                it.parameterTypes.isEmpty() && it.returnType.name == PACKAGE_MANAGER
            }
            if (!hasPmGetter) continue
            cls.declaredMethods.filterTo(hits) { m ->
                !Modifier.isStatic(m.modifiers) &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.returnType == ArrayList::class.java
            }
        }
        if (hits.size != 1) {
            DiagLog.line("row provider: ${hits.size} candidates in ${HostClasses.SHARE_IMPL_PACKAGE}")
            return null
        }
        return hits[0].also { it.isAccessible = true }
    }

    /**
     * The tap action carrying a chosen row: a class whose instance fields are exactly
     * `(String, rowClass)`.
     *
     * Verified unique inside `com.x.dms.components.sharesheet` including nested types
     * (real: `sharesheet.t$g`). Taking [rowClass] as a parameter keeps this derived from an already
     * verified anchor instead of a second independent guess.
     */
    fun actionClass(classLoader: ClassLoader, rowClass: Class<*>): Class<*>? {
        val hits = candidateClasses(classLoader, HostClasses.SHARESHEET_PACKAGE, nested = true)
            .filter { cls ->
                val types = instanceFields(cls).map { it.type }
                types.size == 2 && types[0] == String::class.java && types[1] == rowClass
            }
            .toList()
        if (hits.size != 1) {
            DiagLog.line("action model: ${hits.size} candidates in ${HostClasses.SHARESHEET_PACKAGE}")
            return null
        }
        return hits[0]
    }

    /**
     * Every method receiving a sheet action: `(actionRoot) -> void` on a class that also declares a
     * no-arg `getState()`.
     *
     * The action root is [actionClass]'s superclass — the sealed parent — so a tap on any row type
     * arrives here. Returns *all* matches: on 12.13 there are three (the interface and two
     * implementations), and hooking one while assuming coverage is the mistake 1.3.0 made.
     *
     * `getState()` is part of the predicate because it survives obfuscation: it is a Kotlin property
     * accessor on an interface, so its name is fixed by the JVM naming convention rather than chosen
     * by R8.
     */
    fun dispatchPoints(classLoader: ClassLoader, actionRoot: Class<*>): List<DispatchPoint> {
        val found = mutableListOf<DispatchPoint>()
        val seen = mutableSetOf<String>()
        for (pkg in DISPATCH_PACKAGES) {
            for (cls in candidateClasses(classLoader, pkg)) {
                val hasState = cls.declaredMethods.any {
                    it.name == "getState" && it.parameterTypes.isEmpty()
                }
                if (!hasState) continue
                for (m in cls.declaredMethods) {
                    if (m.returnType != Void.TYPE) continue
                    if (m.parameterTypes.size != 1) continue
                    if (m.parameterTypes[0] != actionRoot) continue
                    // An abstract method has no body to instrument, and XposedBridge.hookMethod
                    // throws IllegalArgumentException on one, which aborted install() in
                    // 1.5.0-probe. The filter belongs here, not at the call site: "dispatch point"
                    // means somewhere execution can be intercepted, and an interface declaration
                    // is not one. Implementors are returned separately, so nothing is lost.
                    if (Modifier.isAbstract(m.modifiers)) continue
                    if (seen.add("${cls.name}.${m.name}")) {
                        m.isAccessible = true
                        found.add(DispatchPoint(m, actionRoot))
                    }
                }
            }
        }
        if (found.isEmpty()) {
            DiagLog.line("FATAL no dispatch (${actionRoot.simpleName})->void found")
            DiagLog.line("      searched: ${DISPATCH_PACKAGES.joinToString(", ")}")
        }
        return found
    }

    /**
     * The method that puts the sheet on screen: `(X) -> boolean` on the class holding both a
     * `ComposeView` and an `Activity`.
     *
     * **This anchor is confirmed off the path a tweet share takes.** 1.5.0-probe installed the hook
     * successfully and it never fired once across three shares. Its four call sites are page-level
     * entries (`app.main.l1`, `app.profiles.n0`, `browser.o`) plus one inside `chooser.b` -- the
     * legacy chooser, not the Compose sheet that `com.x.share.impl` / `com.x.dms.components.sharesheet`
     * actually drive. Reachable in the call graph and on the user's path are different properties,
     * and the reachability gate can only prove the first.
     *
     * Kept resolved and hooked because a negative marker is still evidence: if it ever does fire,
     * the host has switched sheet implementations. Sheet-open detection for the live path has to
     * come from the packages the device proved, not from here.
     */
    fun sheetOpen(classLoader: ClassLoader): Method? {
        val hits = mutableListOf<Method>()
        for (cls in candidateClasses(classLoader, HostClasses.CHOOSER_PACKAGE)) {
            val fieldTypes = instanceFields(cls).map { it.type.name }
            if (COMPOSE_VIEW !in fieldTypes) continue
            if (android.app.Activity::class.java.name !in fieldTypes) continue
            cls.declaredMethods.filterTo(hits) { m ->
                !Modifier.isStatic(m.modifiers) &&
                    m.parameterTypes.size == 1 &&
                    m.returnType == Boolean::class.javaPrimitiveType
            }
        }
        if (hits.size != 1) {
            DiagLog.line("sheet open: ${hits.size} candidates in ${HostClasses.CHOOSER_PACKAGE}")
            return null
        }
        return hits[0].also { it.isAccessible = true }
    }

    /**
     * The field holding a tweet, searched up [start]'s superclass chain.
     *
     * Walking the chain matters: a shareable is typed as a base class with no tweet, and the tweet
     * sits on the concrete subclass. Matched by package rather than class name, since the model's own
     * name drifts but its package does not.
     */
    fun tweetFieldIn(start: Class<*>): java.lang.reflect.Field? {
        var cls: Class<*>? = start
        while (cls != null && cls != Any::class.java) {
            val fields = cls.declaredFields.filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.type.name.startsWith(TWEET_MODEL_PACKAGE) &&
                    !it.type.isEnum
            }
            val hit = fields.singleOrNull()
                // If a build adds a second model field, prefer the fat one: the tweet body has far
                // more fields than an id or an enum-like holder.
                ?: fields.maxByOrNull { it.type.declaredFields.size }
            if (hit != null) return hit.also { it.isAccessible = true }
            cls = cls.superclass
        }
        return null
    }

    /** Loadable classes inside [pkg] whose names match R8's short-name scheme. */
    private fun candidateClasses(
        classLoader: ClassLoader,
        pkg: String,
        nested: Boolean = false,
    ): Sequence<Class<*>> = candidatesIn(pkg, nested).mapNotNull { name ->
        runCatching { classLoader.loadClass(name) }.getOrNull()
    }

    /**
     * Obfuscated-name candidates inside a package.
     *
     * R8 renames a class within its package rather than moving it out, so a drifted class is still
     * findable this way. Nested names (`t$g`) are only generated when asked for, since that
     * multiplies the search 26-fold and only the action model needs it.
     */
    private fun candidatesIn(pkg: String, nested: Boolean = false): Sequence<String> = sequence {
        val simple = sequence {
            for (c in 'a'..'z') yield("$c")
            for (c in 'a'..'z') for (d in '0'..'9') yield("$c$d")
        }
        for (s in simple) {
            yield("$pkg.$s")
            if (nested) for (inner in 'a'..'z') yield("$pkg.$s\$$inner")
        }
    }

    /** Instance fields of [cls] and its superclasses, nearest class first. */
    private fun instanceFields(cls: Class<*>): List<java.lang.reflect.Field> {
        val out = mutableListOf<java.lang.reflect.Field>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            out += c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            c = c.superclass
        }
        out.forEach { it.isAccessible = true }
        return out
    }

    /** Packages declaring a method that receives a sheet action. */
    private val DISPATCH_PACKAGES = listOf(
        HostClasses.SHARE_IMPL_PACKAGE,
        HostClasses.SHARESHEET_PACKAGE,
    )

    /** Field count of the share-row model on the verified build. */
    private const val ROW_FIELD_COUNT = 5

    private const val DRAWABLE = "android.graphics.drawable.Drawable"
    private const val PACKAGE_MANAGER = "android.content.pm.PackageManager"
    private const val COMPOSE_VIEW = "androidx.compose.ui.platform.ComposeView"
    private const val TWEET_MODEL_PACKAGE = "com.twitter.model.core."
}
