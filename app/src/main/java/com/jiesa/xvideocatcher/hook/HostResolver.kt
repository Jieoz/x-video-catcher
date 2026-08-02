package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Resolves the host's action-sheet plumbing by structure, verifying every candidate before use.
 *
 * ## Why the controller path was deleted
 *
 * Versions 1.2 and 1.3 hooked a *controller* (`com.twitter.tweet.action.legacy.e0`) and its
 * `show(FragmentManager)` method. Device logs proved both hooks installed and then nothing fired
 * when the share panel opened: that controller drives the **tweet action sheet**, a different
 * surface from the share panel. Chasing a third controller name would have repeated the mistake,
 * so the anchor moved to the place every sheet must pass through.
 *
 * ## The choke point
 *
 * A sheet is rendered by a ViewHolder method `void bind(sheetModel, clickContract)`, verified on
 * 12.13.0-release.0 as `n0(actionsheet.h, dialog.o)`. It receives the item list holder *and* the
 * tap callback, which is everything an injection needs, and it cannot be bypassed: no bind, no
 * visible sheet.
 *
 * Two classes declare it, and **both must be hooked**. `com.twitter.app.share.ui.d` (the share
 * panel) extends the base ViewHolder and overrides the method with **no `invoke-super`** — read off
 * the bytecode with a disassembler, not inferred — so a base-class-only hook is exactly the bug
 * 1.3.0 shipped.
 *
 * ## Anchoring rules
 *
 * No obfuscated name is trusted. `com.twitter.app.common.dialog.BaseDialogFragment` is the one
 * name used verbatim, and only because the host instantiates it by name so R8 must keep it. From
 * there:
 *
 *  - the **click contract** is the fragment's interface with the contract shape (5 methods),
 *    measured unique across all 16 dex files;
 *  - the **bind points** are methods `void(X, contract)` where `X` holds a `List`;
 *  - the **sheet model** is `X` above — its `List` field is where an entry is appended;
 *  - the **tweet link** is the constructor receiving both a sheet model and a `com.twitter.share.*`
 *    object; that object carries the tweet, and taking both in one constructor associates panel
 *    with tweet exactly, with no timing guesswork.
 *
 * Package names survive R8 in this app, so short obfuscated class names are searched within their
 * recorded package (`a`..`z`, `a0`..`z9`) and accepted only on shape.
 */
internal object HostResolver {

    /** A verified render path: the bind method plus the sheet-model type it accepts. */
    internal data class BindPoint(
        val method: Method,
        val sheetModel: Class<*>,
    )

    /**
     * Every class that binds a sheet, as [BindPoint]s.
     *
     * Returns *all* matches rather than a unique one. An override that does not call `super`
     * renders its own sheet, so each declaring class is a separate entry point; hooking one and
     * assuming coverage is what made the share panel inert.
     */
    fun bindPoints(classLoader: ClassLoader): List<BindPoint> {
        val contract = clickContract(classLoader) ?: return emptyList()
        val found = mutableListOf<BindPoint>()
        val seen = mutableSetOf<String>()

        for (pkg in BIND_PACKAGES) {
            for (name in candidatesIn(pkg)) {
                val cls = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
                val methods = cls.declaredMethods.filter { m ->
                    m.returnType == Void.TYPE &&
                        !Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 2 &&
                        m.parameterTypes[1] == contract &&
                        listFieldOf(m.parameterTypes[0]) != null
                }
                for (m in methods) {
                    if (seen.add("${cls.name}.${m.name}")) {
                        m.isAccessible = true
                        found.add(BindPoint(m, m.parameterTypes[0]))
                    }
                }
            }
        }

        if (found.isEmpty()) {
            DiagLog.line("FATAL no bind method void(sheetModel, ${contract.simpleName}) found")
            DiagLog.line("      searched: ${BIND_PACKAGES.joinToString(", ")}")
        }
        return found
    }

    /**
     * Constructors that associate a sheet model with the object carrying its tweet.
     *
     * Verified unique on 12.13.0-release.0: `menu.share.full.providers.l` takes
     * `(share.api.e, actionsheet.h, …)`. The declared parameter type is the shareable *base*, which
     * has no tweet field — the tweet lives on the subclass actually passed at runtime — so the
     * tweet is read off the instance later, by [tweetFieldIn], rather than checked here.
     */
    fun sheetLinks(classLoader: ClassLoader, sheetModel: Class<*>): List<Constructor<*>> {
        val found = mutableListOf<Constructor<*>>()
        for (pkg in LINK_PACKAGES) {
            for (name in candidatesIn(pkg)) {
                val cls = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
                for (c in cls.declaredConstructors) {
                    val p = c.parameterTypes
                    if (p.size < 2) continue
                    if (!p.any { it == sheetModel }) continue
                    if (!p.any { it.name.startsWith(SHARE_PACKAGE) }) continue
                    c.isAccessible = true
                    found.add(c)
                }
            }
        }
        if (found.isEmpty()) {
            DiagLog.line("sheet link: no ctor takes (${sheetModel.simpleName}, ${SHARE_PACKAGE}*)")
        }
        return found
    }

    /**
     * The item-model class, verified by its `(int drawableRes, int actionId, String title)`
     * constructor — the shape the module builds an entry with.
     */
    fun actionSheetItem(classLoader: ClassLoader): Class<*>? {
        val recorded = HostClasses.ACTION_SHEET_ITEM
        for (name in sequenceOf(recorded) + candidatesIn(packageOf(recorded))) {
            val cls = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
            if (entryConstructor(cls) != null) {
                if (name != recorded) DiagLog.line("item model drifted: resolved $name by ctor shape")
                return cls
            }
        }
        DiagLog.line("FATAL no (int,int,String) ctor in ${packageOf(recorded)}")
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

    /** The sheet model's item list: its only `java.util.List` instance field. */
    fun listFieldOf(cls: Class<*>) =
        cls.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && it.type == List::class.java }
            .singleOrNull()
            ?.also { it.isAccessible = true }

    /**
     * The field holding a tweet, searched up [start]'s superclass chain.
     *
     * Walking the chain matters here: the shareable passed to a sheet link is typed as a base class
     * with no tweet, and the tweet sits on the concrete subclass. Matched by package rather than
     * class name, since the model's own name drifts but its package does not.
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

    /**
     * The method a sheet tap is dispatched through: the click contract's `void(int)`, where the int
     * is the action id.
     *
     * Read off the *interface*, never off the implementing class. `BaseDialogFragment` declares two
     * `void(int)` methods on the verified build (`u` and `R0`), so "the only void(int)" would refuse
     * on every build. R8 must rename an interface method together with its implementations, so the
     * name found on the interface is the right name on the fragment whatever this build calls it.
     */
    fun clickDispatch(classLoader: ClassLoader): Method? {
        val cls = runCatching { classLoader.loadClass(DIALOG_FRAGMENT) }.getOrNull()
        if (cls == null) {
            DiagLog.line("click dispatch: $DIALOG_FRAGMENT not found")
            return null
        }
        val contract = clickContract(classLoader)
        if (contract == null) {
            DiagLog.line("click dispatch: no click-contract interface on ${cls.name}")
            return null
        }
        val name = contract.declaredMethods.firstOrNull { m ->
            m.returnType == Void.TYPE &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType
        }?.name
        if (name == null) {
            DiagLog.line("click dispatch: ${contract.name} has no void(int)")
            return null
        }
        val m = runCatching { cls.getDeclaredMethod(name, Int::class.javaPrimitiveType) }.getOrNull()
        if (m == null) {
            DiagLog.line("click dispatch: ${cls.name} does not declare $name(int)")
            return null
        }
        return m.also { it.isAccessible = true }
    }

    /**
     * The dialog's click-callback interface, identified by shape.
     *
     * Shape on the verified build: no fields, exactly 5 methods — one `void()`, one `void(boolean)`,
     * one `void(int)`, and two no-arg methods returning the same non-void type. Measured unique
     * across all 16 dex files, so the interface's obfuscated name is never needed.
     */
    fun clickContract(classLoader: ClassLoader): Class<*>? {
        val fragment = runCatching { classLoader.loadClass(DIALOG_FRAGMENT) }.getOrNull()
        if (fragment == null) {
            DiagLog.line("click contract: $DIALOG_FRAGMENT not found")
            return null
        }
        return clickContractOf(fragment)
    }

    /** Shape match for the click contract, split out so it can be asserted on directly. */
    internal fun clickContractOf(fragment: Class<*>): Class<*>? {
        val candidates = fragment.interfaces.filter { i ->
            if (i.declaredFields.isNotEmpty()) return@filter false
            val ms = i.declaredMethods
            if (ms.size != CONTRACT_METHOD_COUNT) return@filter false

            val voidInt = ms.count {
                it.returnType == Void.TYPE && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            val voidBool = ms.count {
                it.returnType == Void.TYPE && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
            val voidNoArg = ms.count { it.returnType == Void.TYPE && it.parameterTypes.isEmpty() }
            val noArgReturns = ms
                .filter { it.parameterTypes.isEmpty() && it.returnType != Void.TYPE }
                .map { it.returnType }
            val twoSameReturns = noArgReturns.size == 2 && noArgReturns.distinct().size == 1

            voidInt == 1 && voidBool == 1 && voidNoArg == 1 && twoSameReturns
        }
        if (candidates.size != 1) {
            DiagLog.line("click contract: ${candidates.size} matching interface(s) on ${fragment.name}")
            return null
        }
        return candidates[0]
    }

    /**
     * Obfuscated-name candidates inside a package.
     *
     * R8 renames a class within its package rather than moving it out, so a drifted class is still
     * findable this way. Two-character names come first because every recorded name has that shape.
     */
    private fun candidatesIn(pkg: String): Sequence<String> = sequence {
        for (c in 'a'..'z') for (d in '0'..'9') yield("$pkg.$c$d")
        for (c in 'a'..'z') yield("$pkg.$c")
    }

    private fun packageOf(className: String) = className.substringBeforeLast('.')

    private val DIALOG_FRAGMENT get() = HostClasses.DIALOG_FRAGMENT

    /** Packages declaring a bind method: the base ViewHolder's, and the share panel's override. */
    private val BIND_PACKAGES = listOf(
        "com.twitter.ui.dialog.actionsheet",
        "com.twitter.app.share.ui",
        "com.twitter.subsystems.nudges.engagements",
    )

    /** Packages where a sheet model is handed its tweet-carrying shareable. */
    private val LINK_PACKAGES = listOf(
        "com.twitter.menu.share.full.providers",
        "com.twitter.menu.share.half",
    )

    private const val SHARE_PACKAGE = "com.twitter.share."

    /** Method count of the click-contract interface on the verified build. */
    private const val CONTRACT_METHOD_COUNT = 5
    private const val TWEET_MODEL_PACKAGE = "com.twitter.model.core."
}
