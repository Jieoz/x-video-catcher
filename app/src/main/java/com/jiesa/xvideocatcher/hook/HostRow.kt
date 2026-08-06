package com.jiesa.xvideocatcher.hook

import android.content.Context
import com.jiesa.xvideocatcher.DiagLog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Builds a share-sheet row by copying one the host already made.
 *
 * ## Device history that shapes this file
 *
 * | Ver | Strategy | Device result |
 * |---|---|---|
 * | 1.13 | clone needed int id / non-final fields | `row clone failed` |
 * | 1.14 | package = module id | `row added` but never shown |
 * | 1.15 | full WhatsApp clone, label only | `row added` then process crash |
 * | 1.16 | installed package + fake activity | `row added`, no crash, still no button |
 *
 * 1.16 proved the remaining filter: the sheet (or a step after the provider) requires a
 * **real, resolvable** `(package, activity)` — inventing `…Download` is dropped the same way as
 * inventing a package. Compose also keys on that pair, so it must be unique in the list.
 *
 * ## Live strategy (1.18) — see ShareSheetInjector: REPLACE a visible row, do not append.
 *
 * Historical 1.17 notes retained below for the free-ResolveInfo helper still used in tests.
 *
 * ## Free-ResolveInfo path (1.17, append — device proved UI still drops it)
 *
 * Keep the data-class constructor copy, but take **package, activity, and icon** from a
 * [ResolveInfo] that:
 *
 * 1. is returned by [PackageManager] for a normal `ACTION_SEND` query (so it is installed and
 *    exported), and
 * 2. is **not** already present in the sheet's current row list (so the Compose key is unique and
 *    we do not crash like 1.15).
 *
 * The visible label is still ours. Tap claiming is still by [labelOf]; [ShareSheetInjector]
 * swallows the host launch so that app is never actually opened.
 */
internal object HostRow {

    /** Legacy 1.14 constant; not written into live rows. */
    const val MODULE_PACKAGE = "com.jiesa.xvideocatcher"

    /** Legacy 1.16 constant; not written into live rows (unresolvable activity). */
    const val MODULE_ACTIVITY = "com.jiesa.xvideocatcher.Download"

    /**
     * Build a row for the live Compose sheet.
     *
     * @param existingRows the provider's list **before** insertion — used to pick a free identity
     * @param context host context for [PackageManager]
     */
    fun cloneForSheet(
        template: Any,
        label: String,
        existingRows: List<*>,
        context: Context,
    ): Any? {
        val occupied = occupiedKeys(existingRows)
        val identity = freeShareTarget(context.packageManager, occupied)
            ?: run {
                DiagLog.line("INJECT no free ResolveInfo (occupied=${occupied.size})")
                return null
            }
        return constructCopy(
            template = template,
            label = label,
            packageName = identity.packageName,
            activityName = identity.activityName,
            icon = identity.icon,
        )
    }

    /**
     * Build a row that **keeps** [template]'s package / activity / icon and only rewrites the
     * label. Used by the 1.18 replace path: the sheet already accepted this identity, so the UI
     * will render it; inventing a 13th identity (1.14–1.17) was always filtered after `row added`.
     */
    fun relabelOnly(template: Any, label: String): Any? = constructCopy(
        template = template,
        label = label,
        packageName = null,
        activityName = null,
        icon = null,
    )

    /**
     * Test / fallback entry: label-only rewrite on a mutable bean, or full construct with the
     * template's own package/activity when no PM identity is supplied.
     *
     * Prefer [cloneForSheet] on the device path.
     */
    fun cloneWithLabel(template: Any, id: Int, label: String): Any? {
        constructCopy(
            template = template,
            label = label,
            packageName = null,
            activityName = null,
            icon = null,
        )?.let { return it }
        return mutableCopy(template, id, label)
    }

    fun labelOf(row: Any): String? {
        val fields = instanceFields(row.javaClass)
        val f = labelFieldOf(row, fields) ?: return null
        return runCatching { f.get(row) as? String }.getOrNull()
    }

    /** package → activity pairs already on the sheet. */
    internal fun occupiedKeys(rows: List<*>): Set<Pair<String, String>> {
        val out = mutableSetOf<Pair<String, String>>()
        for (row in rows) {
            if (row == null) continue
            val fields = instanceFields(row.javaClass)
            val labelField = labelFieldOf(row, fields)
            val dotted = dottedStringFields(row, fields, labelField)
            if (dotted.size >= 2) {
                val pkg = runCatching { dotted[0].get(row) as? String }.getOrNull() ?: continue
                val act = runCatching { dotted[1].get(row) as? String }.getOrNull() ?: continue
                out += pkg to act
            }
        }
        return out
    }

    /**
     * First [ResolveInfo] for a share intent whose component is not in [occupied].
     *
     * Queries text/plain and video star MIME types — X shares status URLs as text, and some targets only
     * appear on one MIME. Order is PackageManager's default ranking; we only care about freeness.
     */
    internal fun freeShareTarget(
        pm: PackageManager,
        occupied: Set<Pair<String, String>>,
    ): ShareIdentity? {
        val candidates = mutableListOf<ShareIdentity>()
        val seen = mutableSetOf<Pair<String, String>>()
        for (mime in SHARE_MIMES) {
            val intent = Intent(Intent.ACTION_SEND).setType(mime)
            val matches = runCatching {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }.getOrElse { emptyList() }
            for (ri in matches) {
                val pkg = ri.activityInfo?.packageName ?: continue
                val act = ri.activityInfo?.name ?: continue
                val key = pkg to act
                if (key in seen) continue
                seen += key
                val icon = runCatching { ri.loadIcon(pm) }.getOrNull()
                    ?: runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                    ?: continue
                candidates += ShareIdentity(pkg, act, icon)
            }
        }
        return pickFree(candidates, occupied)
    }

    data class ShareIdentity(
        val packageName: String,
        val activityName: String,
        val icon: Drawable,
    )

    /** First candidate whose (package, activity) is not in [occupied]. */
    internal fun pickFree(
        candidates: List<ShareIdentity>,
        occupied: Set<Pair<String, String>>,
    ): ShareIdentity? {
        val seen = mutableSetOf<Pair<String, String>>()
        for (c in candidates) {
            val key = c.packageName to c.activityName
            if (key in occupied || key in seen) continue
            seen += key
            return c
        }
        return null
    }

    /** Test/device helper: build a row with an explicit free identity. */
    internal fun constructWithIdentity(
        template: Any,
        label: String,
        identity: ShareIdentity,
    ): Any? = constructCopy(
        template = template,
        label = label,
        packageName = identity.packageName,
        activityName = identity.activityName,
        icon = identity.icon,
    )

    /**
     * @param packageName when non-null, written into the package field (live path)
     * @param activityName when non-null, written into the activity field (live path)
     * @param icon when non-null, written into the Drawable field (live path)
     */
    private fun constructCopy(
        template: Any,
        label: String,
        packageName: String?,
        activityName: String?,
        icon: Drawable?,
    ): Any? {
        val cls = template.javaClass
        val fields = instanceFields(cls)
        if (fields.isEmpty()) return null
        if (fields.any { !Modifier.isFinal(it.modifiers) }) return null

        val labelField = labelFieldOf(template, fields) ?: return null
        val packageField = packageFieldOf(template, fields, labelField)
        val activityField = activityFieldOf(template, fields, labelField)
        val iconField = fields.firstOrNull { it.type.name == DRAWABLE || Drawable::class.java.isAssignableFrom(it.type) }

        val ctor = matchingConstructor(cls, fields) ?: return null
        val args = Array(fields.size) { i ->
            val f = fields[i]
            when {
                f == labelField -> label
                packageName != null && f == packageField -> packageName
                activityName != null && f == activityField -> activityName
                icon != null && f == iconField -> icon
                else -> runCatching { f.get(template) }.getOrNull()
            }
        }
        for (i in args.indices) {
            val need = ctor.parameterTypes[i]
            if (args[i] == null && need.isPrimitive) return null
        }
        return runCatching {
            ctor.isAccessible = true
            ctor.newInstance(*args)
        }.getOrNull()
    }

    private fun matchingConstructor(cls: Class<*>, fields: List<Field>): Constructor<*>? {
        val wanted = fields.map { it.type }
        return cls.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.toList() == wanted
        }
    }

    private fun packageFieldOf(template: Any, fields: List<Field>, labelField: Field?): Field? =
        dottedStringFields(template, fields, labelField).getOrNull(0)

    private fun activityFieldOf(template: Any, fields: List<Field>, labelField: Field?): Field? =
        dottedStringFields(template, fields, labelField).getOrNull(1)
            ?: dottedStringFields(template, fields, labelField).getOrNull(0)

    private fun dottedStringFields(
        template: Any,
        fields: List<Field>,
        labelField: Field?,
    ): List<Field> =
        fields.filter { it.type == String::class.java && it != labelField }
            .filter { f ->
                val v = runCatching { f.get(template) as? String }.getOrNull() ?: return@filter false
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

    private val SHARE_MIMES = listOf("text/plain", "video/*", "*/*")
    private const val DRAWABLE = "android.graphics.drawable.Drawable"
}
