package com.jiesa.xvideocatcher

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XSharedPreferences

/**
 * Module settings: written by [SettingsActivity] in the module's own process, read from
 * the host process (X) via [XSharedPreferences].
 *
 * This is the standard LSPosed inter-process pattern: the module app writes a
 * `MODE_WORLD_READABLE` SharedPreferences file, and the host process reads it through
 * `XSharedPreferences`, which LSPosed routes around SELinux to deliver. No shared-storage
 * file, no MediaStore, no storage permission — none of the channels that scoped storage
 * silently broke in earlier builds.
 *
 * Default: diagnostic logging **off**.
 */
object ModuleSettings {

    const val PREF_NAME = "xvc_settings"
    const val KEY_DIAG_ENABLED = "diag_enabled"
    private const val KEY_SCHEMA = "settings_schema"
    private const val CURRENT_SCHEMA = 1

    /**
     * Module-app side: `MODE_WORLD_READABLE` so LSPosed can route the file to the host
     * process via `XSharedPreferences`. LSPosed intercepts this call (when
     * `xposedsharedprefs` meta-data is set) to make it work on Android 10+ where the
     * system would otherwise throw `SecurityException`.
     *
     * If LSPosed is not active (e.g. the module app is opened on a device without LSPosed),
     * the fallback is `MODE_PRIVATE` so the settings UI still works.
     */
    fun prefs(context: Context): SharedPreferences =
        try {
            context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_WORLD_READABLE)
        } catch (e: SecurityException) {
            context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }

    fun isDiagEnabled(context: Context): Boolean {
        val p = prefs(context)
        // Builds before 1.44 forced logging on and may have left a stale `true`.
        // Treat that legacy value as unset; the user must opt in once on the new schema.
        if (p.getInt(KEY_SCHEMA, 0) < CURRENT_SCHEMA) {
            p.edit()
                .putInt(KEY_SCHEMA, CURRENT_SCHEMA)
                .putBoolean(KEY_DIAG_ENABLED, false)
                .commit()
            return false
        }
        return p.getBoolean(KEY_DIAG_ENABLED, false)
    }

    fun setDiagEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putInt(KEY_SCHEMA, CURRENT_SCHEMA)
            .putBoolean(KEY_DIAG_ENABLED, enabled)
            .commit()
    }

    /**
     * Host-process side: read via [XSharedPreferences].
     *
     * **Default off**: if XSharedPreferences is not readable for any reason (LSPosed version
     * mismatch, module just installed, file not synced), return false. Logging is opt-in, so an
     * unreadable preference must not turn it on.
     *
     * Catches [Throwable] not [Exception]: `XSharedPreferences` is a `compileOnly`
     * dependency injected by LSPosed at runtime. If LSPosed fails to inject it,
     * `new XSharedPreferences(...)` throws `NoClassDefFoundError` (an `Error`),
     * which `runCatching` does not catch. That would crash the host.
     *
     * ## Why this is re-readable rather than read once
     *
     * The value lives in a file owned by the module's process, and X's process survives for hours.
     * A once-at-attach read (every build up to 1.53) meant flipping the switch off changed nothing
     * until the host was force-stopped — the user's report was simply "关闭日志功能好像没有效果". So the
     * host instance is cached but [XSharedPreferences.reload] is called on every read: it re-parses
     * only when the file's mtime moved, which makes a repeat read a `stat` in the common case.
     */
    fun readDiagEnabledFromHost(): Boolean {
        return try {
            val xsp = hostPrefs ?: XSharedPreferences(HOST_MODULE_PACKAGE, PREF_NAME).also {
                hostPrefs = it
            }
            if (!xsp.file.canRead()) {
                HostLog.log("ModuleSettings: XSharedPreferences file not readable, defaulting to OFF")
                return false
            }
            xsp.reload()
            if (xsp.getInt(KEY_SCHEMA, 0) < CURRENT_SCHEMA) {
                HostLog.log("ModuleSettings: legacy settings ignored, diag=false")
                return false
            }
            xsp.getBoolean(KEY_DIAG_ENABLED, false)
        } catch (t: Throwable) {
            HostLog.log("ModuleSettings: XSharedPreferences read failed, defaulting to OFF: $t")
            hostPrefs = null
            false
        }
    }

    private const val HOST_MODULE_PACKAGE = "com.jiesa.xvideocatcher"

    /**
     * Cached host-side handle. Held because constructing it re-resolves the module's data dir, while
     * `reload()` on an existing instance is an mtime check — and this is now read repeatedly.
     */
    @Volatile
    private var hostPrefs: XSharedPreferences? = null
}
