package com.jiesa.xvideocatcher.hook

import android.app.Application
import android.content.Context
import com.jiesa.xvideocatcher.BuildConfig
import com.jiesa.xvideocatcher.DiagLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Module entry point: adds a download entry to X's share sheet.
 *
 * The whole module is this one hook plus what it reaches. There is no activity, no service, no
 * background process, and nothing to launch - the module's APK exists only to be loaded into X by
 * LSPosed. Opening the app does nothing by design.
 *
 * Ordering matters here. Hooks are installed once an application context exists, not at package
 * load: [HostShapes] resolves host fields by loading host classes, and doing that before the host
 * classloader is fully set up gets a partially initialised view of the app. The same ordering is
 * what makes the diagnostic log possible at all - there is no Context before this point.
 */
class XVideoCatcherModule : IXposedHookLoadPackage {

    companion object {
        /**
         * Host application context, for the rare path that needs a context but is not handed one.
         * Held statically because a module has no lifecycle of its own to hang it on; it points at
         * the host Application, which outlives everything here.
         */
        @Volatile
        var appContext: Context? = null
            private set
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != HostClasses.HOST_PACKAGE) return
        // X runs several processes (main, notifications, etc.). The share sheet only exists in the
        // main one, and installing hooks in the others burns startup time for nothing.
        if (lpparam.processName != HostClasses.HOST_PACKAGE) return

        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        // thisObject is the Application; args[0] is its *base* context
                        // (a ContextImpl). Only the Application declares
                        // registerActivityLifecycleCallbacks, so these must stay distinct.
                        val application = param.thisObject
                        val context = param.args[0] as Context
                        appContext = context
                        install(lpparam.classLoader, context, application)
                    }.onFailure {
                        // Never let a module failure surface as a host crash. A missing entry is
                        // recoverable by the user; X dying on launch is not.
                        DiagLog.line("FATAL install failed: $it")
                        DiagLog.flushNow()
                        XposedBridge.log("XVC: install failed: $it")
                    }
                }
            },
        )
    }

    private fun install(classLoader: ClassLoader, context: Context, application: Any) {
        val hostVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()

        // Bind the log before installing hooks, so a failure inside installation is itself logged.
        DiagLog.setSessionTag(hostVersion ?: "unknown")
        DiagLog.bindContext(context)
        DiagLog.line("=== module attached ===")
        // Start foreground tracking before any share hook can fire. The tweet detail screen resumes
        // long before the sheet opens, so a tracker installed at share time would have missed the
        // event that identifies it.
        HostActivity.track(application)

        DiagLog.line("module ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        DiagLog.line("host $hostVersion, anchors read from ${HostClasses.VERIFIED_HOST_VERSION}")
        if (hostVersion != null && hostVersion != HostClasses.VERIFIED_HOST_VERSION) {
            // Not an error - shape lookups are designed to absorb renames - but it is the first
            // thing to know when the entry is missing.
            DiagLog.line("NOTE host version differs from the build this module was verified on")
        }

        // Diagnostic build: observes the share sheet and adds nothing to it. Versions 1.2-1.4 each
        // injected against an anchor that turned out to have zero call sites in the shipped app, so
        // the reachability of these anchors is confirmed from a device before injection returns.
        SharePathProbe(classLoader).install()

        // Flush now so the file exists, and proves attachment, before the user touches anything.
        // Without this the log only appears after the first share sheet, and an absent file is
        // indistinguishable from a module that never loaded.
        DiagLog.line("log path: ${DiagLog.path()}")
        DiagLog.flushNow()

        XposedBridge.log(
            "XVC: installed in host $hostVersion (anchors from ${HostClasses.VERIFIED_HOST_VERSION})"
        )
    }
}
