package com.jiesa.xvideocatcher.hook

import android.app.Application
import android.content.Context
import com.jiesa.xvideocatcher.BuildConfig
import com.jiesa.xvideocatcher.DiagLog
import com.jiesa.xvideocatcher.DiagSink
import com.jiesa.xvideocatcher.HostLog
import com.jiesa.xvideocatcher.ModuleSettings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Module entry. LSPosed instantiates this from META-INF/xposed/java_init.list and calls
 * [onPackageReady] once the host class loader exists.
 */
class XVideoCatcherModule : XposedModule() {

    companion object {
        @Volatile
        lateinit var framework: XposedInterface
            private set

        @Volatile
        var appContext: Context? = null
            private set
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        framework = this
        ModuleIcon.modulePath = moduleApplicationInfo.sourceDir
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return
        if (param.packageName != HostClasses.HOST_PACKAGE) return
        val classLoader = param.classLoader ?: return
        runCatching {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            HookBridge.hook(attach, after = HookBridge.After { call ->
                val application = call.thisObject ?: return@After
                val context = call.args[0] as Context
                appContext = context
                install(classLoader, context, application)
            })
        }.onFailure {
            HostLog.log("install hook failed: $it")
        }
    }

    private fun install(classLoader: ClassLoader, context: Context, application: Any) {
        val hostVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()

        DiagLog.setSessionTag(hostVersion ?: "unknown")
        DiagLog.bindEnabledSource { ModuleSettings.readDiagEnabledFromHost() }
        DiagLog.bindContext(context)
        DiagLog.line("=== module attached ===")
        DiagLog.line("diag enabled=${DiagLog.isEnabled()}")
        DiagLog.line("api=libxposed-102")
        DiagLog.line("dataDir=${context.applicationInfo.dataDir}")
        DiagLog.line("extDir=${android.os.Environment.getExternalStorageDirectory()?.absolutePath}")
        DiagLog.line("log fallback: ${DiagSink.appExternalPath(context)}")
        DiagLog.flushNow()

        HostActivity.track(application)
        MediaSpy.install(classLoader)

        DiagLog.line("module ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        DiagLog.line("host $hostVersion, anchors read from ${HostClasses.VERIFIED_HOST_VERSION}")
        if (hostVersion != null && hostVersion != HostClasses.VERIFIED_HOST_VERSION) {
            DiagLog.line("NOTE host version differs from the build this module was verified on")
        }

        val strings = ModuleStrings()
        ShareSheetInjector(classLoader, HostDownloader(strings), strings).install()
        if (DiagLog.isEnabled()) {
            SharePathProbe(classLoader).install()
        }

        DiagLog.line("log path: ${DiagLog.path()}")
        DiagLog.flushNow()
    }
}
