package com.jiesa.xvideocatcher.hook

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Builds a share-sheet row by cloning one the host already made.
 *
 * ## Why cloning instead of constructing
 *
 * The row model (`com.twitter.ui.dialog.actionsheet.b` on 12.13.0-release.0) takes 11 constructor
 * arguments: two ints, four `String`s, two boxed `Integer`s, a boolean and more. Their order is an
 * R8 artefact and it will not survive a host update. Calling that constructor means encoding a
 * guess about argument order that fails silently -- a row with the label in the icon slot renders
 * as a blank entry, not as an error.
 *
 * Cloning removes the guess. An existing row is definitionally the right type with every field the
 * host expects already populated; only two things need to change, and both are identified by their
 * *type and role* rather than their position:
 *
 *  - the id, which is the int field the tap callback reports;
 *  - the visible label, which is the longest human-readable String on the row.
 *
 * Everything else -- icon, colour, flags -- is inherited from the template, so the injected row
 * looks like a native one for free, on any host build, without this file knowing what those fields
 * are called.
 *
 * ## Why the label is picked by length
 *
 * A row carries several Strings: a label, plus analytics scribe names and ids. The label is the one
 * shown to the user, and it is reliably the longest of them on a real row, while scribe keys are
 * short and dotted. Choosing by *shape of the value* rather than by field name survives the rename
 * that a field-name lookup would not.
 */
internal object HostRow {

    /**
     * A copy of [template] with its id set to [id] and its label to [label], or null if the row
     * cannot be cloned.
     *
     * Uses [Object.clone] when the host row supports it and falls back to a field-by-field copy
     * through a no-arg allocation otherwise. Both produce an object of the template's exact class,
     * which is what the sheet's adapter requires.
     */
    fun cloneWithLabel(template: Any, id: Int, label: String): Any? {
        val copy = shallowCopy(template) ?: return null

        val fields = instanceFields(copy.javaClass)

        // The id: the int field the click callback reports, which is the first int on the row model
        // on every build measured.
        //
        // One explicit check, deliberately. An earlier version had `if (ints.isEmpty()) return null`
        // *and* a runCatching around `ints.first()`, which both produce null for an id-less row --
        // and ablation proved the consequence: deleting the explicit guard left the suite green,
        // because the second mechanism silently covered for it. Two paths to one outcome means
        // neither can be tested, so there is now exactly one.
        val idField = fields.firstOrNull { it.type == Int::class.javaPrimitiveType }
            ?: return null
        try {
            idField.setInt(copy, id)
        } catch (e: ReflectiveOperationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }

        // The label: longest String on the template, replaced in place.
        val labelField = labelFieldOf(template, fields) ?: return null
        runCatching { labelField.set(copy, label) }.onFailure { return null }

        return copy
    }

    /**
     * The field holding the user-visible label.
     *
     * Read off the *template*, because the decision is about which field holds real text, and the
     * copy's values are identical at this point. Non-null, non-blank, longest wins; values that
     * look like scribe keys (dotted, no spaces) are excluded even when long, since analytics names
     * such as `tweet.actionsheet.download_click` can outrun a short label like "Save".
     */
    private fun labelFieldOf(template: Any, fields: List<Field>): Field? =
        fields.filter { it.type == String::class.java }
            .mapNotNull { f ->
                val v = runCatching { f.get(template) as? String }.getOrNull()
                if (v.isNullOrBlank() || looksLikeScribeKey(v)) null else f to v
            }
            .maxByOrNull { it.second.length }
            ?.first

    /** True for analytics identifiers: dotted or underscored tokens with no whitespace. */
    private fun looksLikeScribeKey(v: String): Boolean =
        !v.contains(' ') && (v.contains('.') || v.contains('_'))

    /**
     * A field-for-field copy of [source] with the same runtime class.
     *
     * `clone()` is tried first and is what succeeds on a data class. When the host row does not
     * expose it, the fallback allocates through the no-arg constructor if there is one; if neither
     * works the caller degrades to "no download row", which is the correct failure.
     */
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

    /** Instance fields of [cls] and its superclasses, nearest first, all made accessible. */
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
