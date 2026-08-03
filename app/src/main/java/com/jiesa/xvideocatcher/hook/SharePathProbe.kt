package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Modifier

/**
 * Observes X's live share sheet and records what it does. Adds nothing to the UI.
 *
 * ## Why a log-only build exists at all
 *
 * Three releases hooked the action-sheet family. Each one resolved its anchors, installed its hooks,
 * logged success — and did nothing, because those anchors have zero call sites in the shipped app.
 * Static shape checks could not catch that: dead code has the right shape. This build's only job is
 * to prove, from the device, that the anchors are on the path the user's tap actually takes, before
 * any row-injection code is written against them.
 *
 * ## What is recorded, and why each line is unconditional
 *
 * The previous diagnosis was ambiguous because "the hook never fired" and "the log never landed"
 * produced identical evidence: an absence. Every hook here logs on entry, before any condition, so
 * the log distinguishes:
 *
 *  - `PROBE sheet opened`   — the panel reached this module. Absent ⇒ wrong anchor, full stop.
 *  - `PROBE rows built`     — the row list was produced, with its size and the packages in it.
 *  - `PROBE action`         — a tap was dispatched, naming which row was chosen.
 *
 * ## Cost
 *
 * These are one-per-interaction paths — opening a sheet, tapping a row — so the logging is not on
 * any hot path. Row contents are read reflectively once per open, capped at [MAX_ROWS_LOGGED], and
 * the reads are field reads on objects already in memory. Nothing here allocates per frame, which
 * matters because this runs inside someone else's foreground app on whatever hardware they have.
 *
 * ## Failure policy
 *
 * Every hook body is wrapped. A throw inside a host callback surfaces to the user as X crashing,
 * which is worse than a missing diagnostic line.
 */
internal class SharePathProbe(private val classLoader: ClassLoader) {

    fun install() {
        val row = HostResolver.rowClass(classLoader)
        val provider = HostResolver.rowProvider(classLoader)
        val open = HostResolver.sheetOpen(classLoader)
        val action = row?.let { HostResolver.actionClass(classLoader, it) }
        val dispatches = action?.superclass
            ?.let { HostResolver.dispatchPoints(classLoader, it) }
            ?: emptyList()

        // One resolution summary, so a miss is attributable to a specific anchor rather than to
        // "the probe did nothing".
        DiagLog.line("PROBE resolve row=${row?.name ?: "MISS"}")
        DiagLog.line("PROBE resolve provider=${provider?.let { "${it.declaringClass.name}.${it.name}" } ?: "MISS"}")
        DiagLog.line("PROBE resolve open=${open?.let { "${it.declaringClass.name}.${it.name}" } ?: "MISS"}")
        DiagLog.line("PROBE resolve action=${action?.name ?: "MISS"}")
        DiagLog.line("PROBE resolve dispatch=${dispatches.size} point(s)")
        dispatches.forEach {
            DiagLog.line("PROBE   dispatch ${it.method.declaringClass.name}.${it.method.name}")
        }

        open?.let { hookSheetOpen(it) }
        provider?.let { hookRowProvider(it) }
        dispatches.forEach { hookDispatch(it) }

        DiagLog.flushNow()
        XposedBridge.log(
            "XVC probe: open=${open != null} provider=${provider != null} " +
                "row=${row != null} action=${action != null} dispatch=${dispatches.size}"
        )
    }

    /**
     * Records that the panel opened, and whether the shared tweet is reachable from here.
     *
     * Two unknowns are settled by this one hook. The first is reachability of the anchor itself. The
     * second is where the tweet comes from in the Compose sheet: the old design captured it from a
     * constructor pairing sheet and shareable, which no longer exists on this path. The sheet-open
     * argument is the share subject, so if the tweet hangs off it then the real build needs no
     * separate association step and no timing guesswork at all.
     */
    private fun hookSheetOpen(method: java.lang.reflect.Method) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    DiagLog.line("PROBE sheet opened via ${method.declaringClass.name}.${method.name}")
                    val subject = param.args.getOrNull(0)
                    DiagLog.line("PROBE   subject=${subject?.javaClass?.name ?: "null"}")
                    if (subject != null) describeSubject(subject)
                    DiagLog.flushNow()
                }.onFailure { DiagLog.line("ERROR probe sheet-open failed: $it") }
            }
        })
    }

    /**
     * Reports whether a tweet, and downloadable media, can be reached from the share subject.
     *
     * Runs the *production* extractor rather than a probe-local reimplementation: the question is
     * whether the shipping code path works from this object, and a separate implementation here
     * would answer a different question and could pass while the real one fails.
     */
    private fun describeSubject(subject: Any) {
        val field = HostResolver.tweetFieldIn(subject.javaClass)
        if (field == null) {
            // Not fatal: the tweet may sit one level deeper. The field dump below is what makes the
            // next step decidable without another round trip to the device.
            DiagLog.line("PROBE   no tweet-model field on the subject; fields follow")
            dumpFields(subject)
            return
        }
        val tweet = runCatching { field.get(subject) }.getOrNull()
        DiagLog.line("PROBE   tweet field ${field.name}: ${tweet?.javaClass?.name ?: "null"}")
        if (tweet == null) return
        val media = runCatching { TweetMedia.extract(tweet) }
            .onFailure { DiagLog.line("PROBE   media extract threw: $it") }
            .getOrNull() ?: return
        DiagLog.line("PROBE   media extracted: ${media.size} item(s)")
        media.take(MAX_ROWS_LOGGED).forEach {
            // Host URLs are logged host-and-path only. The full query string on a video rendition
            // carries a signed token, and this file is one the user forwards to someone else.
            DiagLog.line("PROBE   media ${it.spec.kind} ${it.url.substringBefore('?')}")
        }
    }

    /** One line per instance field, for deciding the next step when a lookup misses. */
    private fun dumpFields(target: Any) {
        for (f in target.javaClass.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            val v = runCatching { f.get(target) }.getOrNull()
            DiagLog.line("PROBE     ${f.name}: ${f.type.name} = ${v?.javaClass?.name ?: "null"}")
        }
    }

    /**
     * Records the row list the sheet renders from.
     *
     * Hooked `after`: the return value is the point of interest, and reading it proves the list is
     * both reachable and mutable at exactly the moment a row would be appended in the real build.
     */
    private fun hookRowProvider(method: java.lang.reflect.Method) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val rows = param.result as? java.util.ArrayList<*>
                    if (rows == null) {
                        DiagLog.line("PROBE rows built: result=${param.result?.javaClass?.name ?: "null"}")
                        return
                    }
                    DiagLog.line("PROBE rows built: ${rows.size} row(s), arg=${param.args.getOrNull(0)}")
                    rows.take(MAX_ROWS_LOGGED).forEach { r ->
                        DiagLog.line("PROBE   row ${describeRow(r)}")
                    }
                    if (rows.size > MAX_ROWS_LOGGED) {
                        DiagLog.line("PROBE   ... ${rows.size - MAX_ROWS_LOGGED} more")
                    }
                    // Prove the list accepts a write here, without leaving anything in the UI:
                    // append a row-typed clone of the first entry, then remove it. If this throws,
                    // the real build cannot inject and needs a different insertion point.
                    val mutable = probeMutability(rows)
                    DiagLog.line("PROBE   list mutable=$mutable")
                    DiagLog.flushNow()
                }.onFailure { DiagLog.line("ERROR probe rows failed: $it") }
            }
        })
    }

    /**
     * Checks the returned list tolerates an append, leaving it exactly as found.
     *
     * Uses the first element itself rather than a constructed instance: constructing a host row is
     * the real build's job, and doing it here would test the module's constructor guess instead of
     * the list's mutability. Removes by index so an `equals`-based remove cannot delete a genuine
     * duplicate row.
     */
    @Suppress("UNCHECKED_CAST")
    private fun probeMutability(rows: java.util.ArrayList<*>): Boolean = runCatching {
        val first = rows.firstOrNull() ?: return false
        val list = rows as java.util.ArrayList<Any>
        list.add(first)
        list.removeAt(list.size - 1)
        true
    }.getOrDefault(false)

    /** Records a dispatched tap and which row it carried. */
    private fun hookDispatch(point: HostResolver.DispatchPoint) {
        XposedBridge.hookMethod(point.method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val action = param.args.getOrNull(0) ?: return
                    DiagLog.line(
                        "PROBE action ${action.javaClass.name} " +
                            "at ${point.method.declaringClass.name}.${point.method.name}"
                    )
                    describeAction(action)?.let { DiagLog.line("PROBE   $it") }
                    DiagLog.flushNow()
                }.onFailure { DiagLog.line("ERROR probe dispatch failed: $it") }
            }
        })
    }

    /** A row as `package/activity "label"`, read by shape: the String fields in declaration order. */
    private fun describeRow(row: Any?): String {
        if (row == null) return "null"
        val strings = row.javaClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .mapNotNull { f ->
                f.isAccessible = true
                runCatching { f.get(row) as? String }.getOrNull()
            }
        return if (strings.isEmpty()) row.javaClass.name else strings.joinToString(" | ")
    }

    /** An action's payload: its String fields plus any nested row. */
    private fun describeAction(action: Any): String? = runCatching {
        val parts = mutableListOf<String>()
        for (f in action.javaClass.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            val v = runCatching { f.get(action) }.getOrNull() ?: continue
            parts += when {
                v is String -> "\"$v\""
                v.javaClass.name.startsWith(HostClasses.SHARE_ROW_PACKAGE) -> "row(${describeRow(v)})"
                else -> v.javaClass.simpleName
            }
        }
        parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }.getOrNull()

    private companion object {
        /** Enough to identify the list without flooding a user's log with every installed app. */
        const val MAX_ROWS_LOGGED = 12
    }
}
