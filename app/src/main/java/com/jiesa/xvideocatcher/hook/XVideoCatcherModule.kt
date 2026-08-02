package com.jiesa.xvideocatcher.hook

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Module entry point: adds a download entry to X's share sheet.
 *
 * The whole module is this one hook plus what it reaches. There is no activity, no service, no
 * background process, and nothing to launch — the module's APK exists only to be loaded into X by
 * LSPosed. Opening the app does nothing by design.
 *
 * Ordering matters here. Hooks are installed once an application context exists, not at package
 * load: [HostShapes] resolves host fields by loading host classes, and doing that before the host
 * classloader is fully set up gets a partially initialised view of the app.
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
                        val context = param.args[0] as Context
                        appContext = context
                        install(lpparam.classLoader, context)
                    }.onFailure {
                        // Never let a module failure surface as a host crash. A missing entry is
                        // recoverable by the user; X dying on launch is not.
                        XposedBridge.log("XVC: install failed: $it")
                    }
                }
            },
        )
    }

    private fun install(classLoader: ClassLoader, context: Context) {
        val strings = ModuleStrings()
        val downloader = HostDownloader(strings)

        ShareSheetInjector(
            classLoader = classLoader,
            moduleResources = strings,
            onDownload = { ctx, tweet -> downloader.download(ctx, tweet) },
        ).install()

        val hostVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        XposedBridge.log(
            "XVC: installed in host $hostVersion (anchors from ${HostClasses.VERIFIED_HOST_VERSION})"
        )
    }
}
