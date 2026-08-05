package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

/**
 * Adds a download row to X's live share sheet, and handles taps on it.
 *
 * ## Why this targets `com.x.share.impl` and not the tweet action sheet
 *
 * 1.11.0 hooked `com.twitter.tweet.action.legacy.e0`, chosen because cross-referencing found 57
 * classes outside its package calling into the cluster. The device log answered flatly:
 *
 *     sheet controller: 0 candidates in com.twitter.tweet.action.legacy
 *
 * The class is in the APK; it is never instantiated on the user's path. That sheet is a second,
 * unused implementation, and static call sites cannot distinguish "reachable in the call graph"
 * from "on the path the user actually walks" -- a distinction this module had already written down
 * in [HostResolver.sheetOpen]'s comment and then ignored one anchor over.
 *
 * What the same log proved live, every time, is the Compose sheet:
 *
 *     PROBE resolve row=com.x.models.share.a
 *     PROBE resolve provider=com.x.share.impl.c.a
 *     PROBE rows built: 12 row(s), arg=https://x.com/i/status/...
 *     PROBE   list mutable=true
 *
 * So the anchors here are the ones [SharePathProbe] exercised on the device: the row provider that
 * builds the list, and the dispatch points that receive a tap. No new resolver is introduced -- they
 * come from [HostResolver], the same definitions the release gate verifies against a real APK.
 *
 * ## Where the media comes from
 *
 * Not from a tweet. The provider is handed a **status URL**, and every graph walk the 1.11 probe ran
 * from it reported `media extracted: 0 item(s)` -- there is no tweet object on this path, which is
 * what defeated 1.5 through 1.11.
 *
 * [MediaSpy] supplies it instead, by reading the URL the host's own player already resolved. The
 * host cannot play a video without producing a playable URL, so by the time the user opens the share
 * sheet on a video they were watching, the address is in the process.
 *
 * ## How the row is built
 *
 * By cloning an existing row and overwriting its label, via [HostRow]. The host's row type
 * (`com.x.models.share.a`) is a 5-field value class whose constructor argument order is
 * obfuscation-dependent; a clone is guaranteed to be the right type with every field populated.
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
        val provider = HostResolver.rowProvider(classLoader)
        if (provider == null) {
            DiagLog.line("$MARK row-provider MISS -- no download row this session")
            DiagLog.flushNow()
            return
        }

        val rowClass = HostResolver.rowClass(classLoader)
        if (rowClass == null) {
            DiagLog.line("$MARK row-class MISS -- cannot build a row")
            DiagLog.flushNow()
            return
        }

        val actionRoot = HostResolver.actionClass(classLoader, rowClass)
        val dispatch = if (actionRoot == null) {
            emptyList()
        } else {
            HostResolver.dispatchPoints(classLoader, actionRoot)
        }
        if (dispatch.isEmpty()) {
            // Without a tap handler the row would appear and do nothing, which is worse than no row:
            // the user would think the module works and blame the download.
            DiagLog.line("$MARK dispatch MISS -- row suppressed to avoid a dead entry")
            DiagLog.flushNow()
            return
        }

        DiagLog.line(
            "${ProbeMarkers.INJECT_RESOLVE}provider=${provider.declaringClass.name}.${provider.name} "
                + "row=${rowClass.name} dispatch=${dispatch.size}",
        )

        installHook("row-append") { hookRowProvider(provider, rowClass) }
        for (point in dispatch) {
            installHook("tap-${point.method.declaringClass.name}") { hookDispatch(point) }
        }

        DiagLog.flushNow()
        XposedBridge.log("XVC: injector armed on ${provider.declaringClass.name}")
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
     * Appends the download row to the list the sheet renders from.
     *
     * `after`, on the provider's return value: the probe proved that list is a mutable `ArrayList`
     * at this exact point. Appending before it is built would have nothing to append to.
     *
     * The row is only added when [MediaSpy] holds something downloadable, so the entry cannot
     * promise what the tap would fail to deliver.
     */
    private fun hookRowProvider(provider: Method, rowClass: Class<*>) {
        XposedBridge.hookMethod(provider, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val rows = param.result as? java.util.ArrayList<*>
                    if (rows == null) {
                        DiagLog.line("$MARK provider returned ${param.result?.javaClass?.name}")
                        return
                    }

                    val hit = MediaSpy.best()
                    if (hit == null) {
                        // Expected for a text-only tweet, and for a video the user has not played.
                        DiagLog.line(ProbeMarkers.INJECT_NO_MEDIA)
                        return
                    }

                    val template = rows.firstOrNull { it != null && rowClass.isInstance(it) }
                    if (template == null) {
                        DiagLog.line("$MARK no row template (list size=${rows.size})")
                        return
                    }

                    val context = XVideoCatcherModule.appContext
                    if (context == null) {
                        DiagLog.line("$MARK no host context, cannot label the row")
                        return
                    }

                    val row = HostRow.cloneWithLabel(
                        template, ROW_ID, strings.downloadLabel(context),
                    )
                    if (row == null) {
                        DiagLog.line("$MARK row clone failed from ${template.javaClass.name}")
                        return
                    }

                    @Suppress("UNCHECKED_CAST")
                    (rows as java.util.ArrayList<Any>).add(row)
                    DiagLog.line(
                        "${ProbeMarkers.INJECT_ROW_ADDED} (${hit.kind}, list size=${rows.size})",
                    )
                    DiagLog.flushNow()
                }.onFailure {
                    DiagLog.line("$MARK row-append failed: $it")
                }
            }
        })
    }

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
