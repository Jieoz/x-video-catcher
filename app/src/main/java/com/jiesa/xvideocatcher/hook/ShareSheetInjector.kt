package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import com.jiesa.xvideocatcher.HostLog
import com.jiesa.xvideocatcher.ModuleSettings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor

/**
 * Adds a download row to X's live share sheet, and handles taps on it.
 *
 * ## Why the row provider is gone (1.49)
 *
 * 1.43 through 1.48 hooked a *method that returns the row list*: `(String) -> ArrayList` on the class
 * owning a `PackageManager`, progressively relaxed to three tiers. Four consecutive releases injected
 * nothing, and the device log said why in a way no relaxation could have fixed:
 *
 *     row provider: 0 strict / 0 loose / 0 any-arg candidates in com.x.share.impl
 *
 * On 12.20.5 the row-building logic is a `suspend` lambda. The compiler lowered it to an anonymous
 * `SuspendLambda` whose whole signature is `invokeSuspend(Object) -> Object`, and every tier required
 * the return type to be a `List`. **Coroutine lowering erases exactly the property the resolver was
 * anchored on**, so the search could not have succeeded on any host where this code path is a
 * coroutine, whatever the parameter predicate said.
 *
 * So the module stopped looking for the code that builds the rows and started looking for the object
 * they land in. The sheet state is a Kotlin data class; a data class cannot be flattened into a
 * lambda.
 *
 * ## Where the row is injected
 *
 * Into the sheet **state's constructor**. The state is immutable with a compiled `copy`, so every
 * instance the host renders is built there no matter which code path produced it — on 12.20.5 four
 * different writers each read the state flow, copy it and write it back, and the rows arrive on the
 * first of them. Hooking the component's reducer method instead would see only the states that
 * method is handed, which on this host never include a row-bearing one. [SheetRows] carries the
 * reasoning and the surgery; this class is the wiring.
 *
 * The state type itself is not searched for. It is [HostResolver.stateClass], derived from the
 * dispatch points the device already proved live — the class that receives a tap is the class that
 * declares the sheet's `(S) -> S` state transform, and `S` is the state.
 *
 * ## Where the media comes from
 *
 * Not from a tweet. Every graph walk the 1.11 probe ran from this path reported
 * `media extracted: 0 item(s)` -- there is no tweet object on it, which is what defeated 1.5 through
 * 1.11. The status id comes off the share URL the state carries, and [MediaSpy] supplies the rest by
 * reading the URL the host's own player already resolved.
 *
 * ## How the row is built
 *
 * By **appending** a new row built from a visible one, via [HostRow.appendRowForSheet]: the module's
 * label and launcher icon on a `(package, activity)` identity borrowed from an installed app that is
 * not already on the sheet. 1.18–1.49 instead relabelled a host row in place, which worked but cost
 * the user that row (X's Telegram entry vanished). Appending failed in 1.14–1.17 because those hooked
 * the row *provider* and mutated a list the host had already finished with; since 1.49 the hook is on
 * the state **constructor**, so the longer list is the host's own input. See [SheetRows.substitute].
 *
 * ## Failure policy
 *
 * Every hook body is wrapped. A failure drops the row rather than propagating: a missing download
 * entry is recoverable, X crashing in the user's hands is not.
 */
internal class ShareSheetInjector(
    private val classLoader: ClassLoader,
    private val downloader: HostDownloader,
    private val strings: ModuleStrings,
) {

    fun install() {
        // Strict derivation order: every anchor after the first is a consequence of one already
        // verified, so a miss names the exact link that broke instead of "the sheet moved".
        //   row model -> action carrying a row -> sealed action root -> dispatch points -> state
        val rowClass = HostResolver.rowClass(classLoader)
        if (rowClass == null) {
            DiagLog.line("$MARK row-class MISS -- cannot build a row")
            DiagLog.flushNow()
            return
        }

        // actionClass is the *concrete* row-carrying subtype. Dispatch methods take the *sealed
        // parent*, not the subtype -- Kotlin/JVM erases the sealed hierarchy to the superclass
        // parameter. The 1.12 device log proved it: the injector searched `(g)->void` and found
        // nothing while the probe on the same process reported `dispatch=2 point(s)`.
        val action = HostResolver.actionClass(classLoader, rowClass)
        val actionRoot = action?.superclass
        if (action == null || actionRoot == null || actionRoot == Any::class.java) {
            DiagLog.line(
                "$MARK action-root MISS -- action=${action?.name ?: "null"} " +
                    "super=${action?.superclass?.name ?: "null"}",
            )
            DiagLog.flushNow()
            return
        }
        val dispatch = HostResolver.dispatchPoints(classLoader, actionRoot)
        if (dispatch.isEmpty()) {
            // Without a tap handler the row would appear and do nothing, which is worse than no row:
            // the user would think the module works and blame the download.
            DiagLog.line(
                "$MARK dispatch MISS -- row suppressed " +
                    "(action=${action.name}, root=${actionRoot.name})",
            )
            DiagLog.flushNow()
            return
        }

        val stateClass = HostResolver.stateClass(dispatch)
        if (stateClass == null) {
            DiagLog.line("$MARK state-class MISS -- no download row this session")
            DiagLog.flushNow()
            return
        }
        val constructors = HostResolver.stateConstructors(stateClass)
        if (constructors.isEmpty()) {
            DiagLog.line("$MARK state ${stateClass.name} declares no List-carrying constructor")
            DiagLog.flushNow()
            return
        }

        DiagLog.line(
            "${ProbeMarkers.INJECT_RESOLVE}state=${stateClass.name} ctor=${constructors.size} " +
                "row=${rowClass.name} dispatch=${dispatch.size}",
        )

        for (ctor in constructors) {
            installHook("state-${ctor.parameterTypes.size}arg") { hookStateBuild(ctor, rowClass) }
        }
        for (point in dispatch) {
            installHook("tap-${point.method.declaringClass.name}") { hookDispatch(point) }
        }

        DiagLog.flushNow()
        HostLog.log("injector armed on ${stateClass.name}")
    }

    private fun installHook(name: String, block: () -> Unit) {
        runCatching(block).onFailure {
            // Naming the hook matters: without it, a missing row has two indistinguishable causes
            // -- never installed, or installed and never reached. That ambiguity is what made
            // 1.2-1.4 undiagnosable.
            DiagLog.line("${ProbeMarkers.INJECT_HOOK_FAILED} $name: $it")
        }
    }

    /**
     * Puts the download row into the sheet state as it is being constructed.
     *
     * `before`, on the constructor's arguments, rather than `after` on the built object. The state's
     * fields are final and the host's list may be a persistent one, so the argument is the last point
     * at which the row list can be changed without writing to a final field or mutating a list that
     * an earlier state may still be holding. [SheetRows.substitute] carries that reasoning.
     *
     * Runs on every state build, which on this sheet is once per keystroke in the search field, so it
     * is written to be cheap and idempotent: it returns immediately when no argument holds rows —
     * the normal case before the row-loading coroutine finishes — and again when a row already
     * carries the module's label.
     */
    private fun hookStateBuild(ctor: Constructor<*>, rowClass: Class<*>) {
        XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching { injectInto(param.args, ctor.parameterTypes, rowClass) }
                    .onFailure { DiagLog.line("$MARK row-inject failed: $it") }
            }
        })
    }

    /**
     * The body of the state hook, as a plain function over the constructor's arguments.
     *
     * Split out so the injection decision is exercisable on the JVM. Six releases shipped injection
     * logic that could only be observed on the user's device, and each one cost a round trip; this
     * one is asserted against a 12.20.5-shaped fixture before it ever reaches a phone.
     */
    internal fun injectInto(args: Array<Any?>, parameterTypes: Array<Class<*>>, rowClass: Class<*>) {
        val slot = SheetRows.locate(args, rowClass) ?: return

        val context = XVideoCatcherModule.appContext
        if (context == null) {
            DiagLog.line("$MARK no host context, cannot label the row")
            return
        }
        val wanted = strings.downloadLabel(context)
        // Already ours from an earlier build of this same sheet. Not a miss, so not logged: the
        // state is rebuilt on every keystroke and a line per keystroke would bury the real ones.
        if (SheetRows.alreadyCarries(slot.rows, wanted)) return

        // Bind the media *before* offering the row, so the entry cannot promise what a tap would
        // fail to deliver.
        val hitKind = bindMedia(args) ?: return

        val template = slot.rows[slot.rowIndex] ?: return
        // 1.50: append a new row instead of relabelling a host one. See SheetRows.substitute for why
        // appending renders now (constructor argument) when it did not in 1.14-1.17 (row provider).
        val row = HostRow.appendRowForSheet(template, wanted, slot.rows, context)
        if (row == null) {
            val finals = template.javaClass.declaredFields.count {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    java.lang.reflect.Modifier.isFinal(it.modifiers)
            }
            DiagLog.line(
                "$MARK row build failed from ${template.javaClass.name} " +
                    "(finalFields=$finals label=$wanted)",
            )
            return
        }

        if (!SheetRows.substitute(args, parameterTypes, slot, row, insertAt = 0)) {
            DiagLog.line(
                "$MARK substitute refused: arg ${slot.argIndex} is " +
                    "${parameterTypes.getOrNull(slot.argIndex)?.name} holding " +
                    "${slot.rows.javaClass.name}; row skipped",
            )
            return
        }
        DiagLog.line(
            "${ProbeMarkers.INJECT_ROW_ADDED} (APPEND arg=${slot.argIndex} at=0, " +
                "$hitKind, list size=${slot.rows.size}->${slot.rows.size + 1}, ${describe(row)})",
        )
        DiagLog.flushNow()
    }

    /**
     * Binds what a tap on the row would download, and names the evidence it was bound from.
     *
     * Returns null when nothing is downloadable, which suppresses the row.
     */
    private fun bindMedia(args: Array<Any?>): String? {
        val statusId = SheetRows.statusIdIn(args)
        if (statusId != null) {
            // 1.24: freeze the STATUS, not MediaSpy.best. Device 1.23 showed FREEZE binding
            // neighbouring timeline photos/videos while HARVEST returned photos=0 -- capture
            // recency cannot name "this" status.
            DownloaderState.freezeStatus(statusId, MediaSpy.focusedPhotoKey())
            DiagLog.line("$MARK FREEZE status=$statusId photoKey=${DownloaderState.frozenPhotoKey}")
            // With a status id the row is offered unconditionally: resolution happens on tap via
            // StatusMedia.
            return "STATUS"
        }
        // No status URL on the state: last-resort capture freeze so a row can still appear on odd
        // hosts. Logged so a regression is greppable.
        CaptureHarvest.recordPhotosFrom(*args)
        val live = MediaSpy.best(null)
        if (live.isEmpty()) {
            DiagLog.line(ProbeMarkers.INJECT_NO_MEDIA)
            return null
        }
        DownloaderState.freeze(live)
        DiagLog.line(
            "$MARK FREEZE capture-fallback n=${live.size} kind=${live.first().kind} " +
                live.first().url.take(80),
        )
        return DownloaderState.targetHits(null).firstOrNull()?.kind?.name ?: run {
            DiagLog.line(ProbeMarkers.INJECT_NO_MEDIA)
            null
        }
    }

    /** A built row as `package/activity | label`, for the one line that says injection happened. */
    private fun describe(row: Any): String = runCatching {
        val dotted = row.javaClass.declaredFields
            .asSequence()
            .filter { it.type == String::class.java }
            .mapNotNull { f ->
                f.isAccessible = true
                f.get(row) as? String
            }
            .filter { it.contains('.') && !it.contains(' ') }
            .toList()
        "${dotted.getOrNull(0)}/${dotted.getOrNull(1)?.substringAfterLast('.')} | ${HostRow.labelOf(row)}"
    }.getOrElse { "?" }

    /**
     * Claims a tap on the injected row.
     *
     * Every dispatch point is hooked, not just one: the probe found two
     * (`com.x.share.impl.b.h` and `com.x.dms.components.sharesheet.j.h`), and an implementation that
     * does not delegate to the other is its own entry point. Hooking one would work until the user
     * opened the sheet from the other screen.
     *
     * Identification is by label rather than by id: the host's action carries the row it was built
     * from, and the clone's label is the field this module set. Comparing the label to the one it
     * wrote is what makes a foreign row impossible to claim by accident.
     */
    private fun hookDispatch(point: HostResolver.DispatchPoint) {
        XposedBridge.hookMethod(point.method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val action = param.args.getOrNull(0) ?: return
                    val context = XVideoCatcherModule.appContext ?: return
                    if (!isOurs(action, context)) return

                    DiagLog.line(ProbeMarkers.INJECT_TAP)
                    // Harvest only feeds MediaSpy, which `downloadCaptured` consults only when no
                    // status id was frozen. With one bound this walked ~400 nodes over the live
                    // sheet graph to fill a cache nothing would read: every share on the 20260829
                    // log logged `HARVEST photos=0` and then resolved via the status anyway. Asking
                    // the same question the downloader is about to ask keeps the walk for the odd
                    // hosts that need it and takes it off the taps that do not.
                    if (DownloaderState.activeTweetId.isNullOrEmpty()) {
                        CaptureHarvest.recordPhotosFrom(param.thisObject, *param.args)
                    }
                    // Swallow the host's handling: it has no branch for a row it did not build.
                    param.result = null
                    downloader.downloadCaptured(context)
                }.onFailure {
                    DiagLog.line("$MARK tap failed: $it")
                }
                DiagLog.flushNow()
            }
        })
    }

    /**
     * Whether a dispatched action carries the injected row.
     *
     * Compares against the label this module wrote, found on any row-typed field reachable from the
     * action. A host row can never match: the label is the module's own localised string.
     */
    private fun isOurs(action: Any, context: android.content.Context): Boolean {
        val wanted = strings.downloadLabel(context)
        return runCatching {
            action.javaClass.declaredFields.any { f ->
                f.isAccessible = true
                val v = f.get(action) ?: return@any false
                HostRow.labelOf(v) == wanted
            }
        }.getOrDefault(false)
    }

    /**
     * Whether [list] actually accepts writes.
     *
     * `Collections.unmodifiableList` and `List.of` both present as `MutableList` after erasure and
     * throw `UnsupportedOperationException` only when written to. Probing costs one add/remove of a
     * value already in the list, which leaves it byte-identical, and is far cheaper than an exception
     * escaping into the host's UI thread.
     */
    private fun isWritable(list: MutableList<*>): Boolean {
        if (list is java.util.ArrayList<*>) return true
        @Suppress("UNCHECKED_CAST")
        val probe = list as MutableList<Any?>
        return runCatching {
            probe.add(null)
            probe.removeAt(probe.size - 1)
            true
        }.getOrElse { false }
    }

    private companion object {
        /** Log prefix, so injector lines are greppable apart from probe lines. */
        const val MARK = "INJECT"

        /**
         * Id written into the cloned row.
         *
         * Large and arbitrary to avoid colliding with the host's own ids, which are small ordinals.
         * Tap identification is by label rather than by this value -- the live action does not carry
         * an int id -- but a distinct id keeps the clone from impersonating a host row anywhere the
         * host compares them.
         */
        const val ROW_ID = 0x58564331  // "XVC1"
    }
}
