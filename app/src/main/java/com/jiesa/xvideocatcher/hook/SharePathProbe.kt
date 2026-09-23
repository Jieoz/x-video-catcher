package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import com.jiesa.xvideocatcher.HostLog
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
        val action = row?.let { HostResolver.actionClass(classLoader, it) }
        val dispatches = action?.superclass
            ?.let { HostResolver.dispatchPoints(classLoader, it) }
            ?: emptyList()
        // The state type is DERIVED from the dispatch points, which the device already proved live,
        // rather than searched for independently. The `(S) -> S` transform is used only to identify
        // S; it is deliberately NOT hooked. On 12.20.5 that transform (`g.b`) normalises a String
        // field and never touches the row list, so hooking it would observe nothing -- the same
        // shape-over-reachability error that cost 1.2-1.4.
        val state = HostResolver.stateClass(dispatches)
        val constructors = state?.let { HostResolver.stateConstructors(it) } ?: emptyList()

        // One resolution summary, so a miss is attributable to a specific anchor rather than to
        // "the probe did nothing".
        DiagLog.line("${ProbeMarkers.RESOLVE} row=${row?.name ?: "MISS"}")
        DiagLog.line("${ProbeMarkers.RESOLVE} state=${state?.name ?: "MISS"}")
        DiagLog.line("${ProbeMarkers.RESOLVE} state-ctor=${constructors.size}")
        DiagLog.line("${ProbeMarkers.RESOLVE} action=${action?.name ?: "MISS"}")
        DiagLog.line("${ProbeMarkers.RESOLVE} dispatch=${dispatches.size} point(s)")
        dispatches.forEach {
            DiagLog.line("PROBE   dispatch ${it.method.declaringClass.name}.${it.method.name}")
        }

        // Each hook is installed independently. In 1.5.0-probe these were three bare calls, and one
        // unhookable dispatch point threw straight out of install(), skipping every later hook plus
        // the flush and the XposedBridge summary below. The result was the failure mode this build
        // exists to eliminate: partial instrumentation that reads as total silence. A hook that
        // cannot be installed is a fact to report, not a reason to abandon the others.
        if (row != null) {
            constructors.forEachIndexed { n, ctor ->
                installHook("state-ctor-$n") { hookStateConstructor(ctor, row) }
            }
        }
        dispatches.forEach { point ->
            installHook("dispatch ${point.method.declaringClass.name}.${point.method.name}") {
                hookDispatch(point)
            }
        }

        DiagLog.flushNow()
        HostLog.log("probe: state=${state != null} ctor=${constructors.size} " +
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
            DiagLog.line("${ProbeMarkers.HOOK_FAILED} $name: $it")
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
        HookBridge.hook(method, before = HookBridge.Before { call ->
            runCatching {
                DiagLog.line("${ProbeMarkers.SHEET_OPENED} ${method.declaringClass.name}.${method.name}")
                findTweetFrom("sheet-open", call.args.getOrNull(0))
                DiagLog.flushNow()
            }.onFailure { DiagLog.line("${ProbeMarkers.PROBE_ERROR} sheet-open failed: $it") }
        })
    }

    /**
     * The Decompose component held by [target], if any.
     *
     * Matched by package, like every other host lookup here: `com.arkivanov.decompose` is a
     * third-party library, so its package is stable across X releases even though R8 shortens the
     * class names inside it.
     */
    private fun decomposeIn(target: Any): Any? {
        for (f in target.javaClass.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue
            if (!f.type.name.startsWith(DECOMPOSE_PACKAGE)) continue
            f.isAccessible = true
            runCatching { f.get(target) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Test seam for [decomposeIn], which is private because nothing outside should call it. */
    internal fun decomposeInForTest(target: Any): Any? = decomposeIn(target)

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
     * Records the sheet state as it is constructed, and which argument carries the rows.
     *
     * Hooked `before` on the **constructor**, not on a reducer. The state is a Kotlin data class, so
     * its `copy` compiles to a static method ending in a constructor call: every state instance the
     * host renders passes through here whichever code path produced it. A reducer only sees the
     * states it is itself handed, and on 12.20.5 the reducer is invoked from the component
     * constructor and from the action handler -- never with a state that has rows in it.
     *
     * The row argument is identified by [SheetRows.locate], i.e. by what the list actually holds at
     * runtime. This state declares three separate `List` fields and reflection erases all three to
     * bare `java.util.List`, so no signature can tell them apart; the element type can, and that
     * property survives R8, coroutine lowering, and field reordering.
     *
     * Early states carry no rows yet (the row list arrives from a coroutine). Those are reported and
     * left alone rather than guessed at -- a null slot is the normal case, not a miss.
     */
    private fun hookStateConstructor(ctor: java.lang.reflect.Constructor<*>, rowClass: Class<*>) {
        HookBridge.hook(ctor, before = HookBridge.Before { call ->
            runCatching {
                val slot = SheetRows.locate(call.args, rowClass)
                if (slot == null) {
                    DiagLog.line("${ProbeMarkers.ROWS_BUILT} no row-bearing arg (pre-load state)")
                    return@Before
                }
                DiagLog.line(
                    "${ProbeMarkers.ROWS_BUILT} ${slot.rows.size} row(s) " +
                        "at arg[${slot.argIndex}], status=${SheetRows.statusIdIn(call.args) ?: "none"}"
                )
                slot.rows.take(MAX_ROWS_LOGGED).forEach { r ->
                    DiagLog.line("PROBE   row ${describeRow(r)}")
                }
                if (slot.rows.size > MAX_ROWS_LOGGED) {
                    DiagLog.line("PROBE   ... ${slot.rows.size - MAX_ROWS_LOGGED} more")
                }
                val writable = SheetRows.canSubstitute(ctor.parameterTypes, slot)
                DiagLog.line("${ProbeMarkers.LIST_MUTABLE}$writable")
                DiagLog.flushNow()
            }.onFailure { DiagLog.line("${ProbeMarkers.PROBE_ERROR} state-ctor failed: $it") }
        })
    }

    /** Records a dispatched tap and which row it carried. */
    private fun hookDispatch(point: HostResolver.DispatchPoint) {
        HookBridge.hook(point.method, before = HookBridge.Before { call ->
            runCatching {
                val action = call.args.getOrNull(0) ?: return@Before
                DiagLog.line(
                    "${ProbeMarkers.ACTION} ${action.javaClass.name} " +
                        "at ${point.method.declaringClass.name}.${point.method.name}"
                )
                describeAction(action)?.let { DiagLog.line("PROBE   $it") }
                findTweetFrom("dispatch", call.thisObject)
                DiagLog.flushNow()
            }.onFailure { DiagLog.line("${ProbeMarkers.PROBE_ERROR} dispatch failed: $it") }
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
     *
     * ## The search itself is off the UI thread (1.53)
     *
     * Roots are collected on the hook's own thread -- they are field reads on objects the hook
     * already holds -- and everything from [TweetSearch.find] onwards runs on a background thread.
     *
     * The walk is not cheap and it is not expected to hit. On the 20260829 log it ran 4 times per
     * share for 1,600-1,700 node visits each and reported `media extracted: 0 item(s)` every time,
     * because the live share path carries a status URL rather than a tweet (1.6.0 established that)
     * and [MediaSpy] supplies the media instead. Two of those runs land between the tap and the
     * download starting: ~520 ms of the 2.6 s the user waits, spent on a question whose answer has
     * not changed in three releases.
     *
     * Deleting it would be the cheaper edit and the wrong one -- the reason it is still installed is
     * that a host redesign which *starts* carrying a tweet must show up in the log rather than
     * silently making the status path the only thing that works. Moving it off the interaction
     * thread keeps that report and stops charging the user for it.
     */
    private fun findTweetFrom(where: String, holder: Any?) {
        if (holder == null) {
            DiagLog.line("PROBE   $where ${ProbeMarkers.RECEIVER}null")
            return
        }
        DiagLog.line("PROBE   $where ${ProbeMarkers.RECEIVER}${holder.javaClass.name}")

        // Roots in order of expected yield. The receiver first because it is cheapest and a direct
        // hit is the ideal outcome; the resumed activity second because the tweet detail screen is
        // where the tweet demonstrably is when the sheet is opened from it -- 1.6.0 proved the sheet
        // itself does not carry one. Both share one visit budget, and an exhausted budget is
        // reported rather than looking like a clean miss.
        // Roots, ordered by how directly they are expected to reach the tweet. They share one visit
        // budget and the search stops once it has enough candidates, so a later root costs nothing
        // when an earlier one hits.
        val roots = mutableListOf<Pair<String, Any?>>("$where-receiver" to holder)

        // The 20260804 log shows every sharesheet dispatcher holding a `com.arkivanov.decompose.c`.
        // Decompose is X's navigation library, and the sheet is a child component of the screen that
        // opened it -- so its component tree leads back to the tweet detail screen. Reachable
        // straight from an object the hook already has, which the activity is not.
        decomposeIn(holder)?.let { roots.add("decompose" to it) }

        HostActivity.current()?.let { roots.add("activity:${it.javaClass.simpleName}" to it) }

        // Which roots, concretely. The count alone hid whether HostActivity captured the tweet
        // detail screen or something else entirely -- on 20260804 that was unknowable from the log.
        for ((name, value) in roots) {
            DiagLog.line(
                "${ProbeMarkers.ROOT} $name = ${value?.javaClass?.name ?: "null"}",
            )
        }

        offThread("xvc-tweet-search") { searchAndReport(where, holder, roots) }
    }

    /**
     * The expensive half of [findTweetFrom]: walk, report, and sweep on a miss.
     *
     * Separated so the walk has no way back onto the caller's thread, and so a test can drive it
     * directly without a thread in the way.
     */
    internal fun searchAndReport(
        where: String,
        holder: Any,
        roots: List<Pair<String, Any?>>,
    ) {
        val outcome = TweetSearch.find(roots)

        if (outcome.candidates.isEmpty()) {
            DiagLog.line(
                "PROBE   $where ${ProbeMarkers.NO_CANDIDATE} " +
                    "(visits=${outcome.visits} exhausted=${outcome.exhausted} " +
                    "roots=${roots.size})",
            )
            // Which packages the walk actually saw. When the predicate recognises nothing, this is
            // the only thing separating "wrong anchor" from "tweet not reachable".
            outcome.census.forEach { (pkg, n) ->
                DiagLog.line("${ProbeMarkers.CENSUS} $pkg=$n")
            }
            // Refused subtrees. An empty report here with a full census means the prune is not
            // matching what the device actually holds.
            outcome.pruned.forEach { (pkg, n) ->
                DiagLog.line("${ProbeMarkers.PRUNED} $pkg=$n")
            }
            dumpFields(holder)
            deepSweep(where, roots)
            DiagLog.flushNow()
            return
        }

        DiagLog.line(
            "PROBE   $where ${outcome.candidates.size} ${ProbeMarkers.CANDIDATES} " +
                "visits=${outcome.visits} exhausted=${outcome.exhausted}",
        )
        for ((n, c) in outcome.candidates.withIndex()) {
            // The path is the payload: it is what lets the shipping build reach the tweet directly
            // instead of searching on every tap.
            DiagLog.line("${ProbeMarkers.CANDIDATE_PATH}$n] depth=${c.depth} ${c.value.javaClass.name} @ ${c.path}")
            reportTweet("$where[$n]", c.value)
        }
        DiagLog.flushNow()
    }

    /** Reports a located tweet and what the production extractor makes of it. */
    private fun reportTweet(path: String, tweet: Any?) {
        if (tweet == null) {
            DiagLog.line("PROBE   tweet at $path = null")
            return
        }
        DiagLog.line("${ProbeMarkers.TWEET_FOUND} $path: ${tweet.javaClass.name}")
        val media = runCatching { TweetMedia.extract(tweet) }
            .onFailure { DiagLog.line("PROBE   media extract threw: $it") }
            .getOrNull() ?: return
        DiagLog.line("${ProbeMarkers.MEDIA_EXTRACTED} ${media.size} item(s)")
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
        /**
         * Visit budget for the background sweep.
         *
         * 300k, ~75x the UI budget. Sized to exceed the reachable graph rather than to fit a time
         * limit: the answer is only worth having if a miss means absence, and a miss under a budget
         * that ran out means nothing.
         */
        private const val SWEEP_VISITS = 300_000

        /**
         * Hop limit for the background sweep.
         *
         * 25 against the UI path's 6. The tweet is expected within a handful of hops of the screen,
         * but the sweep starts from a share-sheet component and may have to climb the navigation tree
         * before descending, so the route can be much longer than the direct one.
         */
        private const val SWEEP_DEPTH = 25

        /** Enough to identify the list without flooding a user's log with every installed app. */
        const val MAX_ROWS_LOGGED = 12
    }

    /** Navigation library the share sheet is a child component of. Third-party, so stable. */
    private val DECOMPOSE_PACKAGE = "com.arkivanov.decompose"

    /**
     * Re-runs the search on a background thread with a budget a share tap cannot afford.
     *
     * The point is to make `exhausted` mean something. Every device log so far ends with
     * `exhausted=true`, and this module's own rule says that permits no conclusion about whether
     * the tweet is reachable -- so three releases have been unable to tell "the walk stopped early"
     * from "the tweet is not there". This sweep ends that: it either reports a path, or reports
     * `exhausted=false`, which is the only evidence that would justify abandoning graph search.
     *
     * Off the UI thread because it is deliberately too expensive for one: X's own frame budget is
     * untouched, and a slow answer is fine for a diagnostic. Roots are captured before the thread
     * starts, so they are the objects the share actually dispatched from.
     *
     * Failures are swallowed and logged. A reflective walk over a live graph on a background thread
     * can hit a host object mid-mutation, and crashing someone's X client to satisfy a probe is not
     * a trade worth making.
     */
    private fun deepSweep(where: String, roots: List<Pair<String, Any?>>) {
        offThread("xvc-deep-sweep") {
            val outcome = TweetSearch.find(roots, SWEEP_VISITS, SWEEP_DEPTH)
            if (outcome.candidates.isEmpty()) {
                DiagLog.line(
                    "${ProbeMarkers.SWEEP} $where ${ProbeMarkers.SWEEP_ABSENT} " +
                        "(visits=${outcome.visits} exhausted=${outcome.exhausted} " +
                        "depth<=$SWEEP_DEPTH)",
                )
                // An exhausted sweep means even this budget was not enough, and the verdict is
                // still unknown. Saying so explicitly, because the surrounding line reads like
                // an absence proof and on 20260804 that misreading cost a release.
                if (outcome.exhausted) {
                    DiagLog.line(
                        "${ProbeMarkers.SWEEP} $where budget hit, absence NOT proven",
                    )
                }
                return@offThread
            }
            DiagLog.line(
                "${ProbeMarkers.SWEEP} $where ${ProbeMarkers.SWEEP_FOUND} " +
                    "(${outcome.candidates.size} candidate(s) visits=${outcome.visits})",
            )
            // The path is the deliverable: it is the route a targeted lookup would take, which
            // is what replaces searching once this answers.
            for ((n, c) in outcome.candidates.withIndex()) {
                DiagLog.line(
                    "${ProbeMarkers.SWEEP}   [$n] depth=${c.depth} ${c.value.javaClass.name}",
                )
                DiagLog.line("${ProbeMarkers.SWEEP}       path=${c.path}")
            }
        }
    }

    /**
     * Runs [body] on a low-priority daemon thread, swallowing and logging any throw.
     *
     * One helper for both graph walks, because they need identical treatment for identical reasons:
     * neither may delay a host interaction, and neither may crash X. A reflective walk over a live
     * graph can hit a host object mid-mutation, and crashing someone's X client to satisfy a
     * diagnostic is not a trade worth making.
     *
     * `MIN_PRIORITY` so this never wins a scheduling contest against X's rendering, and daemon so a
     * walk in progress cannot hold up process teardown.
     */
    private fun offThread(name: String, body: () -> Unit) {
        Thread {
            try {
                body()
            } catch (t: Throwable) {
                DiagLog.line("${ProbeMarkers.PROBE_ERROR} $name failed: ${t.javaClass.simpleName}")
            }
            DiagLog.flushNow()
        }.apply {
            this.name = name
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }.start()
    }

}
