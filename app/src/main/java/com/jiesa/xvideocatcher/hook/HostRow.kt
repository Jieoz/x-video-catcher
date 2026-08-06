package com.jiesa.xvideocatcher.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Builds a share-sheet row by copying one the host already made.
 *
 * ## Why not invent a constructor call from scratch
 *
 * The live row model on 12.13.0-release.0 is `com.x.models.share.a`: three `String`s (package,
 * activity, label), a `Drawable`, and a `boolean`. Field *order* is an R8 artefact; guessing it
 * produces a blank row rather than an error. Copying a real row keeps every field the host expects,
 * including the icon the sheet already rendered.
 *
 * ## Why the 1.13 clone path failed on device
 *
 * `INJECT row clone failed from com.x.models.share.a` (three times in the 1.13 log). That class is a
 * Kotlin data class: **every field is `final`**, there is **no `int` id**, and `Object.clone` is not
 * exposed. The previous implementation required a writable int id and either `clone()` or a no-arg
 * constructor plus non-final field writes — all three absences are permanent on this model, so the
 * download row was never appended even after dispatch resolution was fixed.
 *
 * ## How a final data-class row is copied
 *
 * Reflect the single all-args constructor whose parameter types match the instance fields, feed it
 * the template's values, and override **only the user-visible label** (longest non-scribe String).
 *
 * Package and activity stay as on the template so the host's "is this a real share target?" filter
 * still accepts the row. Tap claiming uses [labelOf], not package, and [ShareSheetInjector] swallows
 * the host launch when the label matches — so a residual WhatsApp/Telegram package on the model
 * does not open that app.
 */
internal object HostRow {

    /**
     * Package written into the cloned row.
     *
     * Must not be an installed app the host could successfully launch. The module's own
     * applicationId is ideal: present in the APK, never a share target the user has.
     */
    const val MODULE_PACKAGE = "com.jiesa.xvideocatcher"

    /**
     * A copy of [template] labelled [label], or null if it cannot be built.
     *
     * [id] is retained for call-site compatibility with older sheet models that carried an int
     * primary key; the live Compose row has none, and [id] is ignored on that path.
     */
    fun cloneWithLabel(template: Any, id: Int, label: String): Any? {
        // Live path first: final data-class rows are what the device actually builds.
        constructCopy(template, label)?.let { return it }

        // Fallback for fixtures / any non-final host model still reachable in tests.
        return mutableCopy(template, id, label)
    }

    /**
     * The user-visible label on a row-like object, or null when it has none.
     *
     * Public because tap identification needs it: the live share action carries the row, and the
     * only reliable way to recognise the injected row is that its label is the string this module
     * wrote.
     */
    fun labelOf(row: Any): String? {
        val fields = instanceFields(row.javaClass)
        val f = labelFieldOf(row, fields) ?: return null
        return runCatching { f.get(row) as? String }.getOrNull()
    }

    /**
     * Allocates via the data-class constructor. Returns null when the type is not an all-final
     * value with a matching ctor — caller falls through to [mutableCopy].
     */
    private fun constructCopy(template: Any, label: String): Any? {
        val cls = template.javaClass
        val fields = instanceFields(cls)
        if (fields.isEmpty()) return null
        if (fields.any { !Modifier.isFinal(it.modifiers) }) return null

        val labelField = labelFieldOf(template, fields) ?: return null
        // 1.14 rewrote the package String to MODULE_PACKAGE. Device log then showed
        // `INJECT row added … list size=13` three times with zero visible row and zero
        // INJECT_TAP: the Compose sheet almost certainly drops targets whose package is not
        // an installed, resolvable share handler. Keep package + activity + icon from the
        // template so the row survives that filter; identification on tap is still by label.

        val ctor = matchingConstructor(cls, fields) ?: return null
        val args = Array(fields.size) { i ->
            val f = fields[i]
            when {
                f == labelField -> label
                else -> runCatching { f.get(template) }.getOrNull()
            }
        }
        // Constructor parameters must all be present; a null for a primitive boolean would NPE.
        for (i in args.indices) {
            val need = ctor.parameterTypes[i]
            if (args[i] == null && need.isPrimitive) return null
        }
        return runCatching {
            ctor.isAccessible = true
            ctor.newInstance(*args)
        }.getOrNull()
    }

    /**
     * Constructor whose parameter types equal the instance-field types **in declaration order**.
     *
     * Data-class primary constructors are generated that way. Searching by multiset alone would
     * accept a synthetic reordering that puts the label into the package slot.
     */
    private fun matchingConstructor(cls: Class<*>, fields: List<Field>): Constructor<*>? {
        val wanted = fields.map { it.type }
        return cls.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.toList() == wanted
        }
    }

    /**
     * Field holding the launch package, if any: a dotted String that is not the label.
     *
     * Package names always contain `.` and never a space; labels on this sheet are short display
     * names (`WhatsApp`, `Telegram`, `发送给朋友`) without dots. That is enough to separate them
     * without depending on the obfuscated field name.
     */
    private fun packageFieldOf(template: Any, fields: List<Field>, labelField: Field): Field? =
        fields.filter { it.type == String::class.java && it != labelField }
            .firstOrNull { f ->
                val v = runCatching { f.get(template) as? String }.getOrNull() ?: return@firstOrNull false
                v.contains('.') && !v.contains(' ')
            }

    private fun mutableCopy(template: Any, id: Int, label: String): Any? {
        val copy = shallowCopy(template) ?: return null
        val fields = instanceFields(copy.javaClass)

        val idField = fields.firstOrNull { it.type == Int::class.javaPrimitiveType }
        if (idField != null) {
            try {
                idField.setInt(copy, id)
            } catch (_: ReflectiveOperationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        }

        val labelField = labelFieldOf(template, fields) ?: return null
        runCatching { labelField.set(copy, label) }.onFailure { return null }

        // Do NOT rewrite package-like Strings here. On the old actionsheet bean the long dotted
        // fields are scribe keys, not launch packages; overwriting them was a unit-test failure and
        // would have corrupted analytics if that model were still live. Package rewriting belongs
        // only on the final data-class path ([constructCopy]), where the three Strings are
        // package / activity / label.
        return copy
    }

    private fun labelFieldOf(template: Any, fields: List<Field>): Field? =
        fields.filter { it.type == String::class.java }
            .mapNotNull { f ->
                val v = runCatching { f.get(template) as? String }.getOrNull()
                if (v.isNullOrBlank() || looksLikeScribeKey(v)) null else f to v
            }
            .maxByOrNull { it.second.length }
            ?.first

    /** Analytics identifiers: dotted or underscored tokens with no whitespace. */
    private fun looksLikeScribeKey(v: String): Boolean =
        !v.contains(' ') && (v.contains('.') || v.contains('_'))

    private fun shallowCopy(source: Any): Any? {
        runCatching {
            val clone = source.javaClass.getMethod("clone")
            clone.isAccessible = true
            return clone.invoke(source)
        }
        val fresh = runCatching {
            val ctor = source.javaClass.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance()
        }.getOrNull() ?: return null
        for (f in instanceFields(source.javaClass)) {
            if (Modifier.isFinal(f.modifiers)) continue
            runCatching { f.set(fresh, f.get(source)) }
        }
        return fresh
    }

    private fun instanceFields(cls: Class<*>): List<Field> {
        val out = mutableListOf<Field>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            out += c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            c = c.superclass
        }
        out.forEach { it.isAccessible = true }
        return out
    }
}
