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
 * ## Why the row *provider* is gone too (1.49)
 *
 * 1.43–1.48 all resolved the row list through a provider method: `(String) -> ArrayList` on the
 * class owning a `PackageManager`, later relaxed to two more tiers. Every tier required the return
 * type to be a `List`. On 12.20.5 that method does not exist in any form, and no relaxation could
 * have found it: the row-building logic moved into a coroutine, so the compiler emitted an anonymous
 * `SuspendLambda` whose entire signature is `invokeSuspend(Object) -> Object`. **Coroutine lowering
 * erases exactly the property all three tiers were anchored on**, which is why the device reported
 * `0 strict / 0 loose / 0 any-arg candidates` — not a bad predicate, an unmatchable one.
 *
 * So the module no longer looks for the code that builds the rows. It looks for the *state object*
 * they end up in, which is a data class and cannot be erased into a lambda.
 *
 * ## What the live sheet looks like
 *
 *  - [rowClass] — the row model: 3 `String`s, one non-String reference (the icon), a `boolean`.
 *  - [actionClass] / [dispatchPoints] — the sealed action carrying a chosen row, and every method
 *    that receives one. Both were proven live on the device (`dispatch=2 point(s)`, and the hooks
 *    fire on tap).
 *  - [stateClass] — the sheet state, **derived from** [dispatchPoints]: the class declaring a
 *    dispatch method also declares the sheet's state transform, `(S) -> S`. `S` is the state.
 *    Nothing here is searched for independently, so this is not a new guess; it is a consequence of
 *    an anchor the device already confirmed.
 *
 * ## Anchoring rules
 *
 * **No resolver reads a host package-name constant.** That rule is the point of 1.49: six releases
 * in a row replaced one hard-coded host coordinate with another. The search space is now
 * [HostDex.classesMatching] over a needle — a word the host chose for the feature, not a package
 * path — and every predicate is built only from properties that survive R8 and coroutine lowering:
 * field and element types of live objects, framework types in signatures, enum constant names, and
 * identity relationships between anchors already verified.
 *
 * The constants in [HostClasses] survive only as log annotation, so a miss can say "found where
 * expected" or "the package moved" without either answer changing what was searched.
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
     * The share-row model: exactly 3 `String` + 1 non-String reference + 1 `boolean`, with
     * data-class methods.
     *
     * Searched across every share-named package the host declares, not one recorded package. On
     * 12.20.5 that space is 250 classes in 28 packages and this predicate matches exactly one of
     * them (`models.share.a`, holding package name, activity name, label, icon and a flag), so
     * widening the space costs no precision and buys survival of a package move.
     */
    fun rowClass(classLoader: ClassLoader): Class<*>? {
        val hits = candidateClasses(classLoader, SHARE_NEEDLE)
            .filter { isRowShape(it) }
            .toList()
        if (hits.size == 1) return hits[0]
        DiagLog.line("row model: ${hits.size} candidates in ${describeSpace(classLoader, SHARE_NEEDLE)}")
        reportMiss(classLoader, HostClasses.SHARE_ROW_PACKAGE, SHARE_NEEDLE)
        return null
    }

    /**
     * The share-row model across the three device-verified host generations:
     *
     *  - 12.13: `String,String,String,Drawable,boolean`
     *  - 12.20.5: `String,String,String,Object,boolean`
     *  - 12.24: `String,String,String,Object,M`, where `M` is exactly
     *    `boolean,String,int`
     *
     * The 12.24 branch deliberately verifies the nested metadata shape instead of accepting any
     * fifth reference field. The two non-String references then have distinct roles: the only field
     * that is not metadata is the icon. The legacy branch rejects metadata-shaped icons for the same
     * reason, so the new shape cannot make an unrelated data class a second candidate.
     */
    internal fun isRowShape(cls: Class<*>): Boolean {
        if (cls.isInterface || cls.isEnum || Modifier.isAbstract(cls.modifiers)) return false
        val fields = instanceFields(cls)
        if (fields.size != ROW_FIELD_COUNT) return false
        if (fields.count { it.type == String::class.java } != 3) return false

        val metadataTypes = fields.mapNotNull { field ->
            field.type.takeIf { isRowMetadataShape(it) }
        }.toSet()
        val metadataFields = fields.filter { it.type in metadataTypes }
        val legacyFlag = fields.singleOrNull { it.type == Boolean::class.javaPrimitiveType }
        val iconFields = fields.filter {
            it.type != String::class.java &&
                it.type != Boolean::class.javaPrimitiveType &&
                it.type !in metadataTypes &&
                !it.type.isPrimitive
        }
        val fieldShapeMatches = when {
            legacyFlag != null -> metadataFields.isEmpty() && iconFields.size == 1
            metadataFields.size == 1 -> iconFields.size == 1
            else -> false
        }
        if (!fieldShapeMatches) return false

        val methods = cls.declaredMethods.map { it.name }.toSet()
        return methods.containsAll(listOf("equals", "hashCode", "toString"))
    }

    /** X 12.24's verified share-row metadata: one boolean, one String and one int. */
    internal fun isRowMetadataShape(cls: Class<*>): Boolean {
        if (cls.isPrimitive || cls.isArray || cls.isInterface || cls.isEnum ||
            Modifier.isAbstract(cls.modifiers)
        ) return false
        val fields = instanceFields(cls)
        return fields.size == ROW_METADATA_FIELD_COUNT &&
            fields.count { it.type == Boolean::class.javaPrimitiveType } == 1 &&
            fields.count { it.type == String::class.java } == 1 &&
            fields.count { it.type == Int::class.javaPrimitiveType } == 1
    }



    /**
     * The tap action carrying a chosen row: a class whose instance fields are exactly
     * `(String, rowClass)`.
     *
     * Derived from [rowClass] rather than searched for independently, which is what keeps it one
     * anchor rather than two. On 12.20.5 this matches exactly one class in the *entire* APK
     * (`sharesheet.s`), so the search space is not doing any of the work — the row type is.
     */
    fun actionClass(classLoader: ClassLoader, rowClass: Class<*>): Class<*>? {
        val hits = candidateClasses(classLoader, SHARE_NEEDLE)
            .filter { cls ->
                val types = instanceFields(cls).map { it.type }
                types.size == 2 && types[0] == String::class.java && types[1] == rowClass
            }
            .toList()
        if (hits.size == 1) return hits[0]
        DiagLog.line("action model: ${hits.size} candidates in ${describeSpace(classLoader, SHARE_NEEDLE)}")
        reportMiss(classLoader, HostClasses.SHARESHEET_PACKAGE, SHARE_NEEDLE)
        return null
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
        for (cls in candidateClasses(classLoader, SHARE_NEEDLE)) {
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
        if (found.isEmpty()) {
            DiagLog.line("FATAL no dispatch (${actionRoot.simpleName})->void found")
            DiagLog.line("      searched: ${describeSpace(classLoader, SHARE_NEEDLE)}")
            reportMiss(classLoader, HostClasses.SHARESHEET_PACKAGE, SHARE_NEEDLE)
        }
        return found
    }

    /**
     * The sheet's state type, read off the classes that already resolved as dispatch points.
     *
     * The criterion is the one property a state transform cannot lose: it takes the state and
     * returns the state, so **the parameter type and the return type are the same class**. R8 cannot
     * rename that relationship away and coroutine lowering cannot flatten it, because it is not a
     * name and not a shape — it is an identity between two positions in one signature.
     *
     * On 12.20.5 this is `sharesheet.g.b(y) -> y`, and it is unique: across all three classes that
     * declare a dispatch method (`sharesheet.g`, `sharesheet.k`, `share.impl.b`) exactly one such
     * method exists. Nothing is searched for here — [points] are already-verified anchors — so this
     * adds no new coordinate to be wrong about, which is the whole reason it is derived rather than
     * looked up.
     *
     * Three clauses beyond same-type-in-same-type-out, each rejecting a shape that is common and is
     * not a sheet state:
     *
     *  - not `equals`, whose `(Object) -> boolean` never matches anyway but which would if a host
     *    ever declared a covariant one;
     *  - `S` is a host type, not a framework or JDK one. `f(String) -> String` is every formatter
     *    ever written, and framework types in a signature are evidence *against* this being the
     *    application's own state model.
     *  - `S` declares at least one `List` field. The state has to hold the rows, so a candidate that
     *    cannot hold a list is not the state this module needs — and this keeps the predicate honest
     *    about what it is really claiming.
     */
    fun stateClass(points: List<DispatchPoint>): Class<*>? {
        val declarers = points.map { it.method.declaringClass }.distinct()
        val hits = declarers.flatMap { cls -> stateTransformsOn(cls) }
            .map { it.returnType }
            .distinct()
        if (hits.size == 1) return hits[0]
        DiagLog.line("sheet state: ${hits.size} candidate type(s) from ${declarers.size} dispatch class(es)")
        reportStateCandidates(declarers)
        return null
    }

    /**
     * Every `(S) -> S` state transform declared on [cls]. Public to the module so a miss can report
     * exactly what it rejected.
     */
    internal fun stateTransformsOn(cls: Class<*>): List<Method> = cls.declaredMethods.filter { m ->
        !Modifier.isStatic(m.modifiers) &&
            m.parameterTypes.size == 1 &&
            m.parameterTypes[0] == m.returnType &&
            m.name != "equals" &&
            isHostType(m.returnType) &&
            holdsAList(m.returnType)
    }

    /**
     * Constructors of the sheet state that can carry a row list.
     *
     * The state is a Kotlin data class: its fields are final and its copy method takes the same
     * arguments as its constructor, so **every** state instance the host ever renders is built here,
     * whichever code path produced it. That is the property the module needs and the reducer method
     * does not have — a reducer only sees the states it is itself asked to transform.
     *
     * Filtered to constructors with a `List` parameter, since a state with no list cannot be
     * carrying the rows and hooking it would only cost work on the UI thread.
     */
    fun stateConstructors(stateClass: Class<*>): List<java.lang.reflect.Constructor<*>> =
        stateClass.declaredConstructors
            .filter { c -> c.parameterTypes.any { List::class.java.isAssignableFrom(it) } }
            .onEach { it.isAccessible = true }

    /** Whether [type] is the host's own model rather than a framework or language type. */
    private fun isHostType(type: Class<*>): Boolean {
        if (type.isPrimitive || type.isArray || type.isEnum) return false
        val name = type.name
        return FRAMEWORK_PREFIXES.none { name.startsWith(it) }
    }

    /** Whether [type] declares a field that can hold a list, i.e. can hold the rows. */
    private fun holdsAList(type: Class<*>): Boolean =
        type.declaredFields.any {
            !Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type)
        }

    /**
     * Dumps every `(S) -> S` method seen on each dispatch class, when the state did not resolve.
     *
     * The 1.45–1.48 logs each proved a guess wrong without indicating the right one, and that cost
     * four round trips through the user's device. So a state miss reports what it *did* see: the
     * same-type-in-same-type-out methods it weighed, and — when there were none — the full one-arg
     * signature list, which is the input the predicate consumes.
     */
    private fun reportStateCandidates(declarers: List<Class<*>>) {
        for (cls in declarers) {
            val transforms = stateTransformsOn(cls)
            DiagLog.line("  dispatch class ${cls.name} (S)->S x${transforms.size}")
            for (m in transforms.take(METHOD_REPORT_LIMIT)) {
                DiagLog.line("    ${m.name}(${simpleTypeName(m.parameterTypes[0])}) -> " +
                    "${simpleTypeName(m.returnType)} [${describeState(m.returnType)}]")
            }
            if (transforms.isNotEmpty()) continue
            // No transform at all: report every one-arg method, since the predicate's inputs are the
            // parameter and return types and only the log can say which clause did the rejecting.
            var shown = 0
            for (m in cls.declaredMethods) {
                if (Modifier.isStatic(m.modifiers) || m.parameterTypes.size != 1) continue
                if (shown >= METHOD_REPORT_LIMIT) {
                    DiagLog.line("    ... more one-arg methods not shown")
                    break
                }
                DiagLog.line("    ${m.name}(${simpleTypeName(m.parameterTypes[0])}) -> " +
                    "${simpleTypeName(m.returnType)}")
                shown++
            }
            if (shown == 0) DiagLog.line("    no one-arg instance method declared")
        }
    }

    /** Why a same-type-in-same-type-out candidate was or was not accepted as the state. */
    private fun describeState(type: Class<*>): String =
        "host=${isHostType(type)} lists=${type.declaredFields.count {
            !Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type)
        }}"


    /**
     * Whether [type] is a host tweet model.
     *
     * The module's single definition, shared by [tweetFieldIn] (which asks about a declared field's
     * type) and [TweetSearch] (which asks about a live object's class). An earlier draft of the
     * search carried its own field-count heuristic, which is how you end up with two disagreeing
     * answers to one question -- the search would accept an object `tweetFieldIn` rejects.
     *
     * Matched by package, not class name: R8 renames `com.twitter.model.core.e` on every release but
     * does not move it out of its package. Enums are excluded because the media-type enum lives in
     * the same package tree and is not a tweet.
     */
    fun isTweetModel(type: Class<*>): Boolean {
        // Structural, deliberately. Four releases of this module died on a hard-coded host
        // coordinate (1.2-1.4 on class names, 1.7 on a package prefix that the device does not
        // have). A class that declares media entities is a tweet model wherever X decides to keep
        // it next.
        if (type.isEnum || type.isPrimitive || type.isArray) return false
        return holdsMediaEntities(type)
    }

    /**
     * Whether [type] declares a field that can hold host media entities.
     *
     * The package-independent half of the predicate. A class carrying media entities -- directly or
     * as a collection of them -- is a tweet model whatever its package is called, which is what lets
     * this survive the kind of package move that silenced the prefix check on 12.13.0-release.0.
     *
     * Only declared fields of the class and its superclasses are considered, one level deep: this
     * answers "is this a tweet model", not "can a tweet be reached from here", and those must stay
     * different questions. Making it recursive would match any object with a tweet somewhere below
     * it, i.e. almost everything.
     */
    private fun holdsMediaEntities(type: Class<*>): Boolean {
        var cls: Class<*>? = type
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (isMediaEntity(f.type)) return true
                // Media usually arrives as a List/Set of entities, whose element type is erased at
                // runtime, so recover it from the generic signature and test that shape instead.
                for (arg in typeArgumentsOf(f)) if (isMediaEntity(arg)) return true
            }
            cls = cls.superclass
        }
        return false
    }

    /**
     * Whether [type] has the shape of a media variant: a URL-ish String plus at least two
     * numbers.
     *
     * Shape rather than package. Every hardcoded host coordinate this module has shipped has
     * expired -- class names in 1.2-1.4, a package prefix in 1.7, and the three-package
     * whitelist this replaces, which the 20260804 device log shows matching nothing on
     * 12.13.0-release.0. What cannot expire is that X's own player needs a URL to fetch and
     * dimensions or a bitrate to choose between variants, so those fields exist under every
     * name the class may take.
     *
     * Both halves are required. A URL alone matches every config and analytics holder in the
     * app; numbers alone match every geometry class.
     */
    private fun isMediaEntity(type: Class<*>): Boolean {
        if (type.isEnum || type.isPrimitive || type.isArray) return false
        if (type.name.startsWith("java.") || type.name.startsWith("kotlin.")) return false

        var cls: Class<*>? = type
        var url = false
        var numbers = 0
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.type == String::class.java && looksLikeUrlField(f.name)) url = true
                if (isNumeric(f.type)) numbers++
            }
            cls = cls.superclass
        }
        return url && numbers >= MEDIA_ENTITY_MIN_NUMBERS
    }

    /**
     * Whether a field name suggests it holds a media URL.
     *
     * Names, unavoidably: a String field is otherwise indistinguishable from any other String.
     * This is safe where a package whitelist was not, because R8 does not rename fields whose
     * values cross a serialisation boundary -- these arrive from X's own JSON API, so the names
     * survive in the release build. The 20260804 log confirms it: obfuscated classes there still
     * expose readable field names.
     */
    private fun looksLikeUrlField(name: String): Boolean {
        val n = name.lowercase()
        return URL_FIELD_HINTS.any { n.contains(it) }
    }

    private fun isNumeric(type: Class<*>): Boolean = type in NUMERIC_TYPES

    /**
     * Element types named on [f]'s generic signature.
     *
     * Reflection erases `List<Entity>` to `List`, so the element type is only recoverable from
     * the signature. Resolved through the field's own class loader, because the host's classes
     * are not on this module's.
     */
    private fun typeArgumentsOf(f: java.lang.reflect.Field): List<Class<*>> {
        val generic = f.genericType
        if (generic !is java.lang.reflect.ParameterizedType) return emptyList()
        val loader = f.declaringClass.classLoader
        return generic.actualTypeArguments.mapNotNull { arg ->
            when (arg) {
                is Class<*> -> arg
                is java.lang.reflect.ParameterizedType -> arg.rawType as? Class<*>
                else -> runCatching {
                    Class.forName(arg.typeName.substringBefore('<'), false, loader)
                }.getOrNull()
            }
        }
    }

    /**
     * The field holding a tweet, searched up [start]'s superclass chain.
     *
     * Walking the chain matters: a shareable is typed as a base class with no tweet, and the tweet
     * sits on the concrete subclass. Matched by the shape of the media it holds, since both the
     * model's name and its package have drifted across host releases.
     */
    fun tweetFieldIn(start: Class<*>): java.lang.reflect.Field? {
        var cls: Class<*>? = start
        while (cls != null && cls != Any::class.java) {
            val fields = cls.declaredFields.filter {
                !Modifier.isStatic(it.modifiers) && isTweetModel(it.type)
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
     * Loadable host classes whose package name contains [needle].
     *
     * This is the module's one search space, and it is deliberately not a package. Six releases in a
     * row were sunk by a recorded package name: 1.7 by a prefix the device did not have, 1.45–1.48 by
     * `com.x.share.impl` and `com.twitter.share.chooser` — the latter declaring no classes at all on
     * 12.20.5. A needle survives the move a constant cannot, because a restructure renames the
     * package but keeps the word: X's own engineers have to find this code too.
     *
     * The generated `a`..`z9` name guesses are gone with it. They only ever worked *inside* a known
     * package, so they were a second consumer of the coordinate being removed, and the 20260828 log
     * shows what they were worth on a wrong one: `0 candidates` in all three packages, meaning no
     * shape predicate ever ran. Dex enumeration is what actually resolves the anchors on the device
     * (`101601 class name(s) enumerated`), so a host where it fails resolves nothing and says so.
     */
    private fun candidateClasses(classLoader: ClassLoader, needle: String): Sequence<Class<*>> =
        HostDex.classesMatching(classLoader, needle).asSequence().mapNotNull { name ->
            runCatching { classLoader.loadClass(name) }.getOrNull()
        }

    /** The search space as one log-readable phrase: how wide it was, so `0 candidates` is legible. */
    private fun describeSpace(classLoader: ClassLoader, needle: String): String {
        val names = HostDex.classesMatching(classLoader, needle)
        val packages = names.mapNotNull { it.substringBeforeLast('.', "").takeIf(String::isNotEmpty) }
            .distinct().size
        return "${names.size} class(es) across $packages package(s) matching '$needle'"
    }

    /**
     * Reports a miss: whether the *expected* package still holds the anchor, what the classes there
     * look like, and which packages the host actually uses for this feature.
     *
     * [expectedPkg] is annotation only — it is not what was searched. Its job is to answer "moved or
     * reshaped?" in one line, which up to 1.44 took a release to establish. The census then names
     * where to look instead, and the shape dump carries the field signatures the predicates consume,
     * so the next fix is read off one device log rather than guessed.
     */
    internal fun reportMiss(classLoader: ClassLoader, expectedPkg: String, needle: String) {
        if (HostDex.classesIn(classLoader, expectedPkg).isNotEmpty()) {
            reportShapes(classLoader, expectedPkg)
        } else {
            DiagLog.line("  package $expectedPkg declares no classes on this host")
        }
        // Printed on both branches, not just the empty one. 1.45 only reported the census when the
        // recorded package was gone, so on 12.20.5 -- where the packages exist but every shape was
        // rejected -- the log named no alternative at all. A restructure can move the row model to a
        // sibling package while leaving the old one populated with unrelated classes, and that case
        // has to be visible in the same log.
        if (censusReported.add(needle)) {
            HostDex.packageCensusFor(classLoader, needle).forEach { (name, n) ->
                DiagLog.line("  candidate package $name ($n classes)")
            }
        }
    }

    /** Needles whose census is already in the log; every resolver shares "share". */
    private val censusReported = mutableSetOf<String>()

    internal fun resetCensusForTest() {
        censusReported.clear()
    }

    /**
     * Dumps the shape of every class in the package the anchor was last seen in.
     *
     * This is the case the 20260828 1.45 log exposed and 1.45 itself could not act on. Two of the
     * three packages printed `0 candidates` *without* a "declares no classes" line, so [HostDex] did
     * find real classes there and it was the shape predicates that rejected all of them:
     * `com.x.share.impl` and `com.x.models.share` still exist on 12.20.5, their contents were
     * restructured.
     *
     * "The package moved" and "the shape changed" became distinguishable in 1.45, but a shape change
     * is still unactionable without knowing what the new shape *is*. So each candidate's field
     * signature — exactly the input the predicates consume — goes into the log. Reading it off one
     * device log is what replaced the fifth guess with the 12.20.5 row shape.
     */
    private fun reportShapes(classLoader: ClassLoader, pkg: String) {
        val names = HostDex.classesIn(classLoader, pkg)
        DiagLog.line("  package $pkg has ${names.size} class(es); shapes follow")
        var shown = 0
        for (name in names) {
            if (shown >= SHAPE_REPORT_LIMIT) {
                DiagLog.line("  ... ${names.size - shown} more class(es) not shown")
                break
            }
            val cls = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
            val fields = runCatching { instanceFields(cls) }.getOrNull() ?: continue
            val sig = fields.joinToString(",") { simpleTypeName(it.type) }.take(SHAPE_SIG_LIMIT)
            DiagLog.line("  shape ${name.substringAfterLast('.')} [$sig]")
            shown++
        }
    }

    /** Short type name for a shape line: `java.lang.String` is noise at 40 lines per package. */
    internal fun simpleTypeName(type: Class<*>): String = when {
        type.isPrimitive -> type.name
        type.isArray -> simpleTypeName(type.componentType!!) + "[]"
        else -> type.name.substringAfterLast('.')
    }

    /** Instance fields of [cls] and its superclasses, nearest class first. */
    private fun instanceFields(cls: Class<*>): List<java.lang.reflect.Field> {
        val out = mutableListOf<java.lang.reflect.Field>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            out += c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            c = c.superclass
        }
        // Shape resolution reads only Field.type and modifiers; opening every field is unnecessary
        // and fails on JDK 17 module-owned classes (for example java.lang.Object). Callers that read
        // values make their selected host fields accessible at the point of use.
        return out
    }

    /** Field count of the share-row model on the verified builds. */
    private const val ROW_FIELD_COUNT = 5

    /** Field count of X 12.24's nested share-row metadata object. */
    private const val ROW_METADATA_FIELD_COUNT = 3

    /**
     * Classes whose shape is dumped per package on a miss.
     *
     * Bounded because `com.x.share.impl` can hold dozens of classes and the diagnostic must not push
     * the lines it exists to contextualise out of a 512-line queue.
     */
    private const val SHAPE_REPORT_LIMIT = 40

    /** Cap for one shape signature, so a wide class cannot produce an unreadable line. */
    private const val SHAPE_SIG_LIMIT = 200

    /** Candidate methods dumped per holder class on a row-provider miss. */
    private const val METHOD_REPORT_LIMIT = 12

    /**
     * The word every share-sheet anchor is searched under.
     *
     * A feature word, not a coordinate. On 12.20.5 it spans 28 packages and 250 classes, which
     * covers `com.x.models.share`, `com.x.share.impl` and `com.x.dms.components.sharesheet` at once —
     * the three packages 1.48 named separately and got wrong separately.
     */
    private const val SHARE_NEEDLE = "share"

    /**
     * Package prefixes that mark a type as framework rather than host model.
     *
     * Used to reject `(String) -> String` and friends when looking for the sheet state. These are
     * safe to hard-code in a way host package names are not: they are Android, Kotlin and JDK
     * namespaces, fixed by their own vendors, and R8 cannot rename what it does not own.
     */
    private val FRAMEWORK_PREFIXES = listOf(
        "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.", "dalvik.", "sun.",
    )
    /**
     * Numeric field count a media entity must reach, alongside its URL.
     *
     * Two, because a video variant carries width and height, or a bitrate and one dimension.
     * One would admit every String+int pair in the app.
     */
    private const val MEDIA_ENTITY_MIN_NUMBERS = 2

    /** Substrings that mark a String field as holding a media URL. */
    private val URL_FIELD_HINTS = listOf("url", "uri", "src", "link")

    /** Field types counted as a media dimension or bitrate, boxed and unboxed. */
    private val NUMERIC_TYPES = setOf<Class<*>>(
        Int::class.javaPrimitiveType!!, Int::class.javaObjectType,
        Long::class.javaPrimitiveType!!, Long::class.javaObjectType,
        Float::class.javaPrimitiveType!!, Float::class.javaObjectType,
        Double::class.javaPrimitiveType!!, Double::class.javaObjectType,
    )
}
