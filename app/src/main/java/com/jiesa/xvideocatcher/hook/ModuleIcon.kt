package com.jiesa.xvideocatcher.hook

import android.content.res.AssetManager
import android.graphics.drawable.Drawable
import com.jiesa.xvideocatcher.DiagLog
import com.jiesa.xvideocatcher.R

/**
 * The module's own icon, loaded from the module APK rather than through [PackageManager].
 *
 * ## Why this is not just `getApplicationIcon`
 *
 * 1.51 built the injected row's icon with `pm.getApplicationIcon("com.jiesa.xvideocatcher")` and the
 * 20260829 device log shows it failing every single time:
 *
 * ```
 * INJECT module icon unavailable; using borrowed identity icon
 * INJECT row added (... com.android.bluetooth/BluetoothOppLauncherActivity | 下载媒体)
 * ```
 *
 * four attempts, four failures, so the row fell back to the borrowed identity's icon — which is why
 * the user saw a Bluetooth glyph on a row labelled 下载媒体. Replacing the manifest's framework
 * `ic_menu_save` with a real vector in 1.51 was necessary but not sufficient: the lookup never got as
 * far as reading it.
 *
 * The call runs inside `com.twitter.android`, and since API 30 a package is invisible to another
 * package unless it is declared in `<queries>` or matches an intent filter the caller queries. The
 * module declares no launcher activity and no `ACTION_SEND` filter — deliberately, because a module
 * with its own entry point is the thing the user rejected — so from the host's view
 * `com.jiesa.xvideocatcher` does not exist and `getApplicationIcon` throws `NameNotFoundException`.
 * Adding `<queries>` would not help either: that is a *host* manifest declaration and the host is not
 * ours to edit.
 *
 * ## What works instead
 *
 * The module APK is already open in the host process — Xposed loaded our classes from it. Its path
 * arrives in `initZygote` and [XModuleResources] reads resources straight out of it, no
 * `PackageManager` and no package visibility involved. This is the mechanism module resources are
 * meant to use, and it cannot be broken by a ROM's theme layer either, unlike the framework
 * drawable ids.
 */
internal object ModuleIcon {

    /**
     * Absolute path of the module APK, published by onModuleLoaded.
     *
     * Volatile rather than lateinit: it is written on the zygote thread and read on whichever thread
     * builds a sheet row, and a missing value has to degrade rather than throw.
     */
    @Volatile
    var modulePath: String? = null

    /**
     * Cached result, including the failure case.
     *
     * The sheet's state constructor runs 13 times per open on the reporting device, and inflating a
     * vector on each of those would put the cost this class exists to remove straight back. `Drawable`
     * instances are immutable as used here — the row only ever hands it to Compose to draw — so one
     * instance can be shared.
     */
    @Volatile
    private var cached: Result<Drawable>? = null

    /**
     * The module icon, or null when it genuinely cannot be loaded.
     *
     * No `Context` parameter: the module APK is read directly and the vector carries its own size, so
     * nothing here needs the host's resources. That also keeps the failure mode narrow — the only way
     * this returns null is a missing [modulePath] or a genuinely unreadable APK.
     */
    fun load(): Drawable? {
        cached?.let { return it.getOrNull() }
        val result = runCatching { loadFromModuleApk() }
        cached = result
        result.exceptionOrNull()?.let {
            // Logged once, not once per row build: the cache holds the failure too.
            DiagLog.line("INJECT module icon load failed: $it")
        }
        return result.getOrNull()
    }

    private fun loadFromModuleApk(): Drawable {
        val path = modulePath
            ?: error("modulePath not set; onModuleLoaded did not run")
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val add = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
        add.isAccessible = true
        val cookie = add.invoke(assets, path) as Int
        if (cookie == 0) error("addAssetPath returned 0 for $path")
        val res = android.content.res.Resources(assets, null, null)
        @Suppress("DEPRECATION")
        return res.getDrawable(R.drawable.ic_module)
            ?: error("ic_module resolved to null")
    }

    /** Test seam: forget the cached answer so a later [load] retries. */
    internal fun resetForTest() {
        cached = null
    }
}
