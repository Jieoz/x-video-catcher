package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Adds a download row to X's tweet action sheet, and handles taps on it.
 *
 * ## Why this replaces searching for the tweet
 *
 * Versions 1.5-1.10 hooked the Compose share sheet and then went looking for the tweet by walking
 * the object graph, because nothing on that path hands one over. Every device log ended
 * `exhausted=true`, which proves nothing either way.
 *
 * The tweet action sheet is a different code path and it does not need searching: the controller
 * that builds the sheet *holds* the tweet. `com.twitter.tweet.action.legacy.e0` has the row list in
 * field `a` and the tweet in field `b`, and `e0.h(FragmentManager)` is the method that renders the
 * list. So at the moment `h` is entered, both the rows and the tweet are in hand -- no graph walk,
 * no budget, no ambiguity.
 *
 * ## Why e0 is trusted where 1.2-1.4's anchors were not
 *
 * Those three releases hooked classes with **zero call sites in the shipped APK**: they resolved,
 * they hooked, they never fired, and shape checks could not tell because dead code has the right
 * shape. `e0` was cleared differently, by cross-referencing the release APK:
 *
 *  - `e0.h` has 3 direct call sites (`legacy.d0` x2, `legacy.o1` x1).
 *  - Direct callers were not accepted as sufficient -- all three are in the same package, and a
 *    cluster of dead classes calling each other looks exactly like this. Walking callers upward
 *    found **57 classes outside** `tweet.action.legacy` entering the cluster, including
 *    `com.twitter.timeline.g`, `com.twitter.tweetdetail.q1` and `com.twitter.app.gallery.j1`.
 *
 * So the sheet is reached from the timeline, the tweet detail screen and the gallery: the three
 * places a user actually opens it from.
 *
 * ## How the row is built
 *
 * The host's own row model is `com.twitter.ui.dialog.actionsheet.b`, whose field `a` is the int id
 * that comes back on tap. Rather than construct one reflectively -- the constructor takes 11
 * arguments and their order is obfuscation-dependent -- the module **clones an existing row** and
 * overwrites its id and label. A clone is guaranteed to be the right type, with every field the
 * host expects populated; there is no constructor signature to get wrong.
 *
 * ## How a tap gets back
 *
 * Taps arrive at `com.twitter.app.common.dialog.o.u(int)`, implemented by `BaseDialogFragment`
 * (21 call sites). The module hooks `u`, claims the call when the id is [ROW_ID], and lets every
 * other id through untouched.
 *
 * ## Failure policy
 *
 * Every hook body is wrapped, and a failure removes the row rather than propagating: a missing
 * download entry is recoverable, X crashing in the user's hands is not.
 */
internal class ShareSheetInjector(
    private val classLoader: ClassLoader,
    private val downloader: HostDownloader,
    private val strings: ModuleStrings,
) {

    fun install() {
        val controller = HostResolver.sheetController(classLoader)
        if (controller == null) {
            DiagLog.line("$MARK controller MISS -- no download row this session")
            DiagLog.flushNow()
            return
        }
        val show = HostResolver.sheetShowMethod(controller)
        if (show == null) {
            DiagLog.line("$MARK show-method MISS on ${controller.name}")
            DiagLog.flushNow()
            return
        }

        DiagLog.line("${ProbeMarkers.INJECT_RESOLVE}${controller.name} show=${show.name}")

        installHook("sheet-show") { hookShow(show) }
        installHook("row-click") { hookClick() }

        DiagLog.flushNow()
        XposedBridge.log("XVC: injector armed on ${controller.name}.${show.name}")
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
     * Appends the download row as the sheet is about to be shown.
     *
     * `before` rather than `after`: `h` copies the list into a builder and calls `toArray`, so a row
     * added afterwards would never reach the rendered sheet.
     */
    private fun hookShow(show: Method) {
        XposedBridge.hookMethod(show, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val controller = param.thisObject ?: return
                    val tweet = HostResolver.tweetFieldIn(controller.javaClass)
                        ?.get(controller)
                    if (tweet == null) {
                        DiagLog.line("$MARK sheet opened, tweet field empty")
                        return
                    }

                    // Resolve media before touching the UI. A row is only worth adding if there is
                    // something behind it, and this is the same extractor the download uses, so the
                    // row cannot promise what the tap would fail to deliver.
                    val media = TweetMedia.extract(tweet)
                    if (media.isEmpty()) {
                        DiagLog.line(ProbeMarkers.INJECT_NO_MEDIA)
                        return
                    }

                    val rows = rowListOf(controller)
                    if (rows == null) {
                        DiagLog.line("$MARK row list not found on ${controller.javaClass.name}")
                        return
                    }
                    val template = rows.firstOrNull {
                        it != null && it.javaClass.name.startsWith(ROW_PACKAGE)
                    }
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
                    (rows as MutableList<Any>).add(row)
                    // The tweet, not the extracted media: [HostDownloader] extracts from the tweet
                    // itself, and keeping one extraction path means the row can never promise
                    // something the download resolves differently.
                    pending = tweet
                    DiagLog.line(
                        "${ProbeMarkers.INJECT_ROW_ADDED} (${media.size} item(s), "
                            + "list size=${rows.size})",
                    )
                    DiagLog.flushNow()
                }.onFailure {
                    DiagLog.line("$MARK sheet-show failed: $it")
                }
            }
        })
    }

    /** The row list held by the controller: its only `List` field. */
    private fun rowListOf(controller: Any): MutableList<*>? {
        val f = HostShapes.uniqueFieldOfType(controller.javaClass, List::class.java)
            ?: return null
        return runCatching { f.get(controller) as? MutableList<*> }.getOrNull()
    }

    /**
     * Claims a tap on the injected row.
     *
     * Hooked on the interface's implementor rather than the interface: `u` is declared on
     * `dialog.o` but dispatched virtually, and hooking an interface method installs nothing.
     */
    private fun hookClick() {
        val fragment = classLoader.loadClass(HostClasses.DIALOG_FRAGMENT)
        val u = fragment.declaredMethods.firstOrNull { m ->
            m.name == CLICK_METHOD &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: throw NoSuchMethodException("$CLICK_METHOD(int) on ${fragment.name}")

        XposedBridge.hookMethod(u, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val id = param.args.getOrNull(0) as? Int ?: return
                if (id != ROW_ID) return
                runCatching {
                    val tweet = pending
                    // Consume before doing the work: a double tap must not download twice, and the
                    // sheet is dismissed either way.
                    pending = null
                    val context = XVideoCatcherModule.appContext
                    if (tweet == null || context == null) {
                        DiagLog.line("$MARK tap with nothing pending")
                        return
                    }
                    DiagLog.line(ProbeMarkers.INJECT_TAP)
                    // Swallow the host's own handling of an id it does not know. Without this the
                    // host would fall through to its default branch for an unrecognised id.
                    param.result = null
                    downloader.download(context, tweet)
                }.onFailure {
                    DiagLog.line("$MARK tap failed: $it")
                    param.result = null
                }
                DiagLog.flushNow()
            }
        })
    }

    private companion object {
        /** Log prefix, so injector lines are greppable apart from probe lines. */
        const val MARK = "INJECT"

        /**
         * Id for the injected row.
         *
         * Large and arbitrary to avoid colliding with the host's own ids, which are small ordinals.
         * A collision would route the host's action to this module or vice versa.
         */
        const val ROW_ID = 0x58564331  // "XVC1"

        const val ROW_PACKAGE = "com.twitter.ui.dialog.actionsheet"

        /** The item-click callback on `dialog.o`: `u(int)`, 21 call sites in 12.13.0-release.0. */
        const val CLICK_METHOD = "u"

        /**
         * The tweet whose sheet is currently open, waiting for a tap.
         *
         * One slot, not a map keyed by row id: only one sheet is on screen at a time, and it is
         * refreshed on every open. Volatile because the sheet is built on the UI thread and the
         * value is read again on the tap.
         */
        @Volatile
        private var pending: Any? = null
    }
}
