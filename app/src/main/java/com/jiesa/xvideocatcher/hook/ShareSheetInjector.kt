package com.jiesa.xvideocatcher.hook

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.jiesa.xvideocatcher.DiagLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * Adds a download entry to X's action sheets, in X's process.
 *
 * ## Where this hooks, and why not where it used to
 *
 * Versions 1.2 and 1.3 hooked a tweet-action *controller* and its `show(FragmentManager)`. Device
 * logs showed both hooks installing and then never firing when the share panel opened: that
 * controller drives a different surface. Hooking the bind method instead — `void bind(sheetModel,
 * clickContract)` on every ViewHolder that declares it — is unbypassable, because a sheet that is
 * never bound is never on screen.
 *
 * Both declaring classes are hooked. The share panel's ViewHolder overrides the bind method with no
 * `super` call, so hooking only the base class is precisely the 1.3.0 bug.
 *
 * ## How the entry appears
 *
 * The sheet model owns the list the adapter is about to read. Appending one host-typed item to it
 * during bind means no view inflation and no window of our own: the entry looks native because it
 * *is* native, and only its contents come from us.
 *
 * ## How the tap is caught
 *
 * The injected item carries a sentinel action id, and taps arrive as `clickContract.void(int)` with
 * that id — a value the module controls, rather than a listener class whose name drifts every build.
 *
 * ## Failure policy
 *
 * Every hook body is wrapped. A throw inside a host UI callback surfaces to the user as X crashing,
 * which is worse than a missing download entry. Each distinct cause names itself in the diagnostic
 * log, because "no entry appeared" otherwise collapses five different failures into one symptom.
 */
internal class ShareSheetInjector(
    private val classLoader: ClassLoader,
    private val moduleResources: ModuleStrings,
    private val onDownload: (Context, Any) -> Unit,
) {

    /**
     * Action id carried by the injected entry.
     *
     * Chosen far outside the host's own id space, which its resource compiler generates in low
     * ranges. A collision would mean X's handler runs for our entry.
     */
    private val sentinelActionId = 0x5EED_0001

    /**
     * Tweet for each sheet model, populated when the host builds the pair.
     *
     * The tap arrives on the dialog fragment, where neither the sheet nor the tweet is reachable, so
     * the association has to be recorded when it is created. Keyed weakly on the sheet model: the
     * host owns its lifetime, and strong keys here would pin dismissed sheets — and their tweets —
     * for the life of the process. Synchronized because sheet construction is not guaranteed to
     * share a thread with the tap.
     */
    private val tweetBySheet = java.util.Collections.synchronizedMap(WeakHashMap<Any, Any>())

    /** Most recently bound sheet model, so a tap can be attributed to a sheet. */
    @Volatile
    private var boundSheetRef: WeakReference<Any>? = null

    /** Context from the most recent bind, used for toasts when a download fails. */
    @Volatile
    private var lastContextRef: WeakReference<Context>? = null

    fun install() {
        val bindPoints = HostResolver.bindPoints(classLoader)
        if (bindPoints.isEmpty()) {
            DiagLog.flushNow()
            XposedBridge.log("XVC: no sheet bind point found; entry not installed")
            return
        }
        for (bp in bindPoints) {
            DiagLog.line(
                "hooked bind ${bp.method.declaringClass.name}.${bp.method.name}" +
                    "(${bp.sheetModel.simpleName}, contract)"
            )
        }

        // Every bind point shares one sheet-model type, so the tweet link is resolved once.
        hookSheetLinks(bindPoints.first().sheetModel)
        bindPoints.forEach { hookBind(it) }
        hookClickDispatch()
    }

    /**
     * Records the tweet for a sheet at the moment the host pairs them.
     *
     * Taking both in one constructor is what makes this exact: there is no guessing about which
     * tweet a panel belongs to and no dependence on call ordering.
     */
    private fun hookSheetLinks(sheetModel: Class<*>) {
        val links = HostResolver.sheetLinks(classLoader, sheetModel)
        if (links.isEmpty()) {
            DiagLog.line("WARN no sheet->tweet link found; taps will report no tweet")
            return
        }
        for (ctor in links) {
            DiagLog.line("hooked sheet link ${ctor.declaringClass.name}.<init>")
            XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val sheet = param.args.firstOrNull { it != null && sheetModel.isInstance(it) }
                            ?: return
                        val shareable = param.args.firstOrNull {
                            it != null && it.javaClass.name.startsWith(SHARE_PACKAGE)
                        } ?: return
                        val field = HostResolver.tweetFieldIn(shareable.javaClass)
                        if (field == null) {
                            DiagLog.line("sheet link: no tweet field on ${shareable.javaClass.name}")
                            return
                        }
                        val tweet = field.get(shareable)
                        if (tweet == null) {
                            DiagLog.line("sheet link: tweet field was null")
                            return
                        }
                        tweetBySheet[sheet] = tweet
                    }.onFailure { DiagLog.line("ERROR sheet link failed: $it") }
                }
            })
        }
    }

    /** Appends the download entry to the item list as the sheet is bound. */
    private fun hookBind(bp: HostResolver.BindPoint) {
        XposedBridge.hookMethod(bp.method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val sheet = param.args.getOrNull(0) ?: return
                // Recorded before the append, so a tap still reports *why* when injection failed
                // instead of looking like the sheet was never seen.
                boundSheetRef = WeakReference(sheet)
                runCatching { rememberContext(param.thisObject) }
                runCatching { appendEntry(sheet, bp.sheetModel) }
                    .onFailure {
                        DiagLog.line("ERROR append failed: $it")
                        XposedBridge.log("XVC: append failed: $it")
                    }
            }
        })
    }

    /**
     * Appends the entry, naming the step that failed when it does not appear.
     *
     * Runs once per sheet open, not on any hot path, so the logging costs nothing measurable.
     */
    private fun appendEntry(sheet: Any, sheetModel: Class<*>) {
        val tweet = tweetBySheet[sheet]
        if (tweet == null) {
            DiagLog.line("sheet bound: no tweet recorded for ${sheet.javaClass.name}")
            return
        }

        val media = TweetMedia.extract(tweet)
        if (media.isEmpty()) {
            // Expected on a text-only post. Logged anyway: "video post shows no entry" and "text
            // post shows no entry" are the same symptom and need telling apart.
            DiagLog.line("sheet bound: no downloadable media found in tweet")
            return
        }

        val itemsField = HostResolver.listFieldOf(sheetModel)
        if (itemsField == null) {
            DiagLog.line("ENTRY SKIPPED: item list field not found on ${sheetModel.name}")
            return
        }
        val current = itemsField.get(sheet) as? List<*>
        if (current == null) {
            DiagLog.line("ENTRY SKIPPED: item list held ${itemsField.get(sheet)?.javaClass?.name}")
            return
        }
        if (current.any { it != null && isOurEntry(it) }) {
            // Not a failure: some screens rebuild the sheet, others rebind an existing one.
            return
        }

        val entry = buildEntry(sheet)
        if (entry == null) {
            DiagLog.line("ENTRY SKIPPED: could not construct a host item (${HostClasses.ACTION_SHEET_ITEM})")
            return
        }
        itemsField.set(sheet, current.filterNotNull() + entry)
        DiagLog.line("entry added to sheet (${media.size} media, ${current.size} host items)")
    }

    /**
     * Builds an item of the host's own item type.
     *
     * The host renders rows by reading fields off these objects, so an entry has to *be* one of
     * them; a look-alike of our own class would be cast-failed or skipped. The `(drawableRes,
     * actionId, title)` constructor is the minimal shape producing a complete row and is present in
     * every build examined.
     */
    private fun buildEntry(sheet: Any): Any? {
        val itemClass = HostResolver.actionSheetItem(classLoader) ?: return null
        val ctor = HostResolver.entryConstructor(itemClass) ?: return null

        val title = moduleResources.downloadLabel(contextFor(sheet))
        // Icon 0 = no icon. Referencing one of X's drawables would look tidier but ties the entry
        // to a resource id regenerated on every host build.
        return runCatching { ctor.newInstance(0, sentinelActionId, title) }.getOrNull()
    }

    /**
     * Watches tap dispatch for the sentinel id.
     *
     * The int argument *is* the action id, so recognising our entry costs one comparison on a
     * host-wide path — this runs for every sheet tap in the app, including on low-end hardware.
     */
    private fun hookClickDispatch() {
        val dispatch = HostResolver.clickDispatch(classLoader)
        if (dispatch == null) {
            DiagLog.line("FATAL no click dispatch void(int) found; clicks inert")
            DiagLog.flushNow()
            XposedBridge.log("XVC: no click dispatch found; clicks inert")
            return
        }
        DiagLog.line("hooked click dispatch ${dispatch.declaringClass.name}.${dispatch.name}(int)")

        XposedBridge.hookMethod(dispatch, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val actionId = param.args.getOrNull(0) as? Int ?: return
                if (actionId != sentinelActionId) return
                DiagLog.line("download entry tapped")
                runCatching {
                    val sheet = boundSheetRef?.get()
                    if (sheet == null) {
                        DiagLog.line("ERROR tapped but no sheet was recorded")
                        return
                    }
                    val tweet = tweetBySheet[sheet]
                    if (tweet == null) {
                        DiagLog.line("ERROR tapped but no tweet recorded for the sheet")
                        return
                    }
                    onDownload(contextFor(sheet), tweet)
                    // Consume it: letting the host continue with an action id it does not know can
                    // put its own dispatch into a default branch.
                    param.result = null
                }.onFailure {
                    DiagLog.line("ERROR download dispatch failed: $it")
                    XposedBridge.log("XVC: download dispatch failed: $it")
                    runCatching {
                        val ctx = lastContextRef?.get() ?: return@runCatching
                        Toast.makeText(
                            ctx,
                            moduleResources.failureLabel(ctx),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        })
    }

    /** True when [candidate] is the entry this module injected, identified by the sentinel id. */
    private fun isOurEntry(candidate: Any): Boolean {
        val cls = candidate.javaClass
        if (!cls.name.startsWith(ACTION_SHEET_PACKAGE)) return false
        for (f in cls.declaredFields) {
            if (Modifier.isStatic(f.modifiers) || f.type != Int::class.javaPrimitiveType) continue
            f.isAccessible = true
            if (runCatching { f.getInt(candidate) }.getOrNull() == sentinelActionId) return true
        }
        return false
    }

    /** Caches a Context off the bound ViewHolder, for the failure toast. */
    private fun rememberContext(holder: Any?) {
        if (holder == null) return
        for (f in holder.javaClass.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            val v = runCatching { f.get(holder) }.getOrNull()
            val ctx = when (v) {
                is Activity -> v
                is Context -> v
                is android.view.View -> v.context
                else -> null
            }
            if (ctx != null) {
                lastContextRef = WeakReference(ctx)
                return
            }
        }
    }

    /** A usable context: the one seen at bind time, else the module's app context. */
    private fun contextFor(sheet: Any): Context {
        lastContextRef?.get()?.let { return it }
        for (f in sheet.javaClass.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            val v = runCatching { f.get(sheet) }.getOrNull()
            if (v is Activity) return v
            if (v is Context) return v
        }
        return XVideoCatcherModule.appContext ?: error("no host context available")
    }

    private companion object {
        const val SHARE_PACKAGE = "com.twitter.share."
        const val ACTION_SHEET_PACKAGE = "com.twitter.ui.dialog.actionsheet"
    }
}
