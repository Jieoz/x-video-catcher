package com.jiesa.xvideocatcher.hook

import android.app.Application
import android.content.Context
import com.jiesa.xvideocatcher.BuildConfig
import com.jiesa.xvideocatcher.DiagLog
import com.jiesa.xvideocatcher.HostLog
import com.jiesa.xvideocatcher.DiagSink
import com.jiesa.xvideocatcher.ModuleSettings
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
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
class XVideoCatcherModule : IXposedHookLoadPackage, IXposedHookZygoteInit {

    /**
     * Publishes the module APK path for [ModuleIcon].
     *
     * This is the only place the path is available: Xposed hands it to the zygote callback and nothing
     * in the host process can derive it afterwards. It is also why the module now implements a second
     * interface — 1.51 implemented only [IXposedHookLoadPackage], so there was no way to read our own
     * resources and the injected row's icon lookup went through `PackageManager`, which package
     * visibility blocks. See [ModuleIcon] for the full reasoning.
     *
     * Runs in the zygote, before any host code: keep it to assignment only, no logging (DiagLog has no
     * host context yet) and nothing that could throw.
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        ModuleIcon.modulePath = startupParam.modulePath
    }

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
                    try {
                        // thisObject is the Application; args[0] is its *base* context
                        // (a ContextImpl). Only the Application declares
                        // registerActivityLifecycleCallbacks, so these must stay distinct.
                        val application = param.thisObject
                        val context = param.args[0] as Context
                        appContext = context
                        install(lpparam.classLoader, context, application)
                    } catch (t: Throwable) {
                        // Never let a module failure surface as a host crash. A missing entry is
                        // recoverable by the user; X dying on launch is not.
                        // Catches Throwable not Exception: compileOnly Xposed classes throw
                        // NoClassDefFoundError (an Error) if LSPosed fails to inject them.
                        HostLog.log("install failed: $t")
                    }
                }
            },
        )
    }

    private fun install(classLoader: ClassLoader, context: Context, application: Any) {
        val hostVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()

        // Bind the diag switch to the user's stored preference instead of sampling it once. X's
        // process outlives the settings screen by hours, so a value read here and cached would make
        // turning the switch *off* have no visible effect until a force-stop — which is exactly what
        // 1.53 and earlier did. See DiagLog.bindEnabledSource.
        DiagLog.setSessionTag(hostVersion ?: "unknown")
        DiagLog.bindEnabledSource { ModuleSettings.readDiagEnabledFromHost() }
        DiagLog.bindContext(context)
        DiagLog.line("=== module attached ===")
        DiagLog.line("diag enabled=${DiagLog.isEnabled()}")
        DiagLog.line("dataDir=${context.applicationInfo.dataDir}")
        DiagLog.line("extDir=${android.os.Environment.getExternalStorageDirectory()?.absolutePath}")
        // Both retrieval locations, named at attach time. If Download/ turns out to be
        // permission-blocked in this host, this is the path that still has the file.
        DiagLog.line("log fallback: ${DiagSink.appExternalPath(context)}")
        DiagLog.flushNow()

        HostLog.log("DEBUG: module attached, diag=${DiagLog.isEnabled()}, dataDir=${context.applicationInfo.dataDir}")
        // Start foreground tracking before any share hook can fire. The tweet detail screen resumes
        // long before the sheet opens, so a tracker installed at share time would have missed the
        // event that identifies it.
        HostActivity.track(application)

        // Capture media URLs from the host's own player before anything else. This is what makes
        // the download possible at all: 1.5-1.11 tried to reach media through the tweet object and
        // the live share path never carries one -- it carries a status URL. The player, by
        // definition, has already resolved a playable URL, so the module reads it there.
        //
        // Installed first because playback can start before any share sheet is opened; a capture
        // armed at share time would miss the video the user is looking at.
        MediaSpy.install(classLoader)

        DiagLog.line("module ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        DiagLog.line("host $hostVersion, anchors read from ${HostClasses.VERIFIED_HOST_VERSION}")
        if (hostVersion != null && hostVersion != HostClasses.VERIFIED_HOST_VERSION) {
            // Not an error - shape lookups are designed to absorb renames - but it is the first
            // thing to know when the entry is missing.
            DiagLog.line("NOTE host version differs from the build this module was verified on")
        }

        // The download row. Injects into the tweet action sheet, whose controller *holds* the tweet
        // -- which is what replaced the graph search of 1.5-1.10. Its anchor was cleared for
        // reachability with a disassembler before any code was written against it, the check that
        // 1.2-1.4 lacked.
        val strings = ModuleStrings()
        ShareSheetInjector(classLoader, HostDownloader(strings), strings).install()

        // The Compose share sheet probe. Gated on diag: it is a diagnostic, and its cost is not
        // small. On the 20260829 device log it produced 57% of all lines and 46,768 reflective node
        // visits across 352 graph searches -- every one of which extracted 0 media items, because
        // MediaSpy already supplies the URL and the graph search is a leftover from 1.5-1.10. That
        // work landed on the sheet's own construction path, 13 times per open, which is the lag the
        // user could see. With diag off the hooks are never installed at all, so the cost is zero
        // rather than merely unlogged: DiagLog.line() returning early still leaves the reflection.
        if (DiagLog.isEnabled()) {
            SharePathProbe(classLoader).install()
        } else {
            HostLog.log("probe: not installed (diag off)")
        }

        // Flush now so the file exists, and proves attachment, before the user touches anything.
        // Without this the log only appears after the first share sheet, and an absent file is
        // indistinguishable from a module that never loaded.
        DiagLog.line("log path: ${DiagLog.path()}")
        DiagLog.flushNow()

        HostLog.log("installed in host $hostVersion (anchors from ${HostClasses.VERIFIED_HOST_VERSION})"
        )
    }
}
