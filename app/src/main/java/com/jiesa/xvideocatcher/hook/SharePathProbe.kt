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

        // Each hook is installed independently. In 1.5.0-probe these were three bare calls, and one
        // unhookable dispatch point threw straight out of install(), skipping every later hook plus
        // the flush and the XposedBridge summary below. The result was the failure mode this build
        // exists to eliminate: partial instrumentation that reads as total silence. A hook that
        // cannot be installed is a fact to report, not a reason to abandon the others.
        installHook("sheet-open") { open?.let { hookSheetOpen(it) } }
        installHook("row-provider") { provider?.let { hookRowProvider(it) } }
        dispatches.forEach { point ->
            installHook("dispatch ${point.method.declaringClass.name}.${point.method.name}") {
                hookDispatch(point)
            }
        }

        DiagLog.flushNow()
        XposedBridge.log(
            "XVC probe: open=${open != null} provider=${provider != null} " +
                "row=${row != null} action=${action != null} dispatch=${dispatches.size}"
        )
    }

    /**
     * Installs one hook, containing its failure to that hook.
     *
     * The name is logged on failure so an uninstallable hook is attributable to a specific anchor.
     * Without it a missing marker has two indistinguishable causes -- the hook was never installed,
     * or it was installed and the code path never ran -- which is exactly the ambiguity that made
     * 1.2 through 1.4 undiagnosable.
     */
    internal fun installHook(name: String, block: () -> Unit) {
        runCatching(block).onFailure {
            DiagLog.line("PROBE hook FAILED $name: $it")
        }
    }

    /**
     * Records that the legacy chooser opened, if it ever does.
     *
     * Kept for its negative value. 1.5.0-probe installed this hook successfully and it did not fire
     * once across three shares, which is what identified `chooser.j.J0` as belonging to the old
     * chooser rather than the Compose sheet. If this line ever appears, the host has switched sheet
     * implementations and the live anchors need rechecking -- so its absence is now the expected
     * result and its presence is the signal.
     *
     * Tweet lookup goes through the same [findTweetFrom] the live hooks use. It previously had its
     * own near-identical implementation, which is one code path too many for one question: the
     * variant on the dead path could drift from the one that actually reports.
     */
    private fun hookSheetOpen(method: java.lang.reflect.Method) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    DiagLog.line("PROBE sheet opened via ${method.declaringClass.name}.${method.name}")
                    findTweetFrom("sheet-open", param.args.getOrNull(0))
                    DiagLog.flushNow()
                }.onFailure { DiagLog.line("ERROR probe sheet-open failed: $it") }
            }
        })
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
                    // Tweet reachability is asked here, not at sheet-open. 1.5.0-probe attached it
                    // to the sheet-open hook, which is off the live path and never fired -- so the
                    // one question the probe existed to answer came back blank. This hook is
                    // device-proven to run, and its receiver is the provider that built the rows.
                    // The arg was only ever the share URL string, so the receiver is where a tweet
                    // reference can plausibly live.
                    findTweetFrom("rows-provider", param.thisObject)
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
                    // Second place a tweet reference could hang: the dispatcher itself. Asked at
                    // both live hooks because either receiver would be enough for the real build,
                    // and one round trip per candidate is the cost this probe exists to avoid.
                    findTweetFrom("dispatch", param.thisObject)
                    DiagLog.flushNow()
                }.onFailure { DiagLog.line("ERROR probe dispatch failed: $it") }
            }
        })
    }

    /**
     * Looks for a reachable tweet model on [holder], then one level into its fields.
     *
     * Called from the two hooks the device proved live. One level deep because the direct lookup is
     * what 1.5.0 would have done and the answer needs to survive the tweet sitting behind a
     * ViewModel or state wrapper -- which is the normal shape on a Compose screen. Deeper than that
     * is not worth guessing at from here; the field dump tells us where to look next instead.
     *
     * Runs the production [TweetMedia] extractor on whatever it finds, deliberately. A probe-local
     * reimplementation could report media the shipping path cannot actually reach.
     */
    private fun findTweetFrom(where: String, holder: Any?) {
        if (holder == null) {
            DiagLog.line("PROBE   $where receiver=null")
            return
        }
        DiagLog.line("PROBE   $where receiver=${holder.javaClass.name}")

        HostResolver.tweetFieldIn(holder.javaClass)?.let { f ->
            reportTweet("$where.${f.name}", runCatching { f.get(holder) }.getOrNull())
            return
        }

        // One level down: check each field's own type for a tweet model.
        for (f in holder.javaClass.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            val v = runCatching { f.get(holder) }.getOrNull() ?: continue
            HostResolver.tweetFieldIn(v.javaClass)?.let { inner ->
                reportTweet(
                    "$where.${f.name}.${inner.name}",
                    runCatching { inner.get(v) }.getOrNull(),
                )
                return
            }
        }

        // No tweet found: dump the shape so the next step is decidable without another device trip.
        DiagLog.line("PROBE   $where no tweet model within 1 level; fields follow")
        dumpFields(holder)
    }

    /** Reports a located tweet and what the production extractor makes of it. */
    private fun reportTweet(path: String, tweet: Any?) {
        if (tweet == null) {
            DiagLog.line("PROBE   tweet at $path = null")
            return
        }
        DiagLog.line("PROBE   TWEET FOUND at $path: ${tweet.javaClass.name}")
        val media = runCatching { TweetMedia.extract(tweet) }
            .onFailure { DiagLog.line("PROBE   media extract threw: $it") }
            .getOrNull() ?: return
        DiagLog.line("PROBE   media extracted: ${media.size} item(s)")
        media.take(MAX_ROWS_LOGGED).forEach {
            // Host-and-path only: the query string on a video rendition carries a signed token, and
            // this file is one the user forwards on.
            DiagLog.line("PROBE   media ${it.spec.kind} ${it.url.substringBefore('?')}")
        }
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
