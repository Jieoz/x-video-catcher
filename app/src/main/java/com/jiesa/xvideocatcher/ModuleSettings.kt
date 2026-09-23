package com.jiesa.xvideocatcher

import android.content.Context
import android.content.SharedPreferences
import com.jiesa.xvideocatcher.hook.XVideoCatcherModule

/**
 * Settings written by [SettingsActivity] and read inside X.
 *
 * The module app stores an ordinary private preference, then copies it into libxposed remote
 * preferences. The host reads that copy. There is no world-readable XML, which is the file
 * LSPosed 2.2 warns about and 2.3 removes.
 *
 * Default: diagnostic logging off. An unreadable remote value stays off.
 */
object ModuleSettings {

    const val PREF_NAME = "xvc_settings"
    const val KEY_DIAG_ENABLED = "diag_enabled"
    private const val KEY_SCHEMA = "settings_schema"
    private const val CURRENT_SCHEMA = 1

    /** Module-app writer. Tests replace this; production uses the framework published at load. */
    internal var remoteWriter: ((SharedPreferences) -> Unit)? = { local ->
        val remote = XVideoCatcherModule.framework.getRemotePreferences(PREF_NAME)
        remote.edit()
            .putInt(KEY_SCHEMA, local.getInt(KEY_SCHEMA, 0))
            .putBoolean(KEY_DIAG_ENABLED, local.getBoolean(KEY_DIAG_ENABLED, false))
            .commit()
    }

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isDiagEnabled(context: Context): Boolean {
        val p = prefs(context)
        if (p.getInt(KEY_SCHEMA, 0) < CURRENT_SCHEMA) {
            p.edit()
                .putInt(KEY_SCHEMA, CURRENT_SCHEMA)
                .putBoolean(KEY_DIAG_ENABLED, false)
                .commit()
            publish(p)
            return false
        }
        return p.getBoolean(KEY_DIAG_ENABLED, false)
    }

    fun setDiagEnabled(context: Context, enabled: Boolean) {
        val p = prefs(context)
        p.edit()
            .putInt(KEY_SCHEMA, CURRENT_SCHEMA)
            .putBoolean(KEY_DIAG_ENABLED, enabled)
            .commit()
        publish(p)
    }

    private fun publish(local: SharedPreferences) {
        runCatching { remoteWriter?.invoke(local) }
            .onFailure { HostLog.log("ModuleSettings: remote publish failed: $it") }
    }

    internal fun publishForTest(local: SharedPreferences) = publish(local)

    /** Host-side read. Default off when the framework or the remote group is unavailable. */
    fun readDiagEnabledFromHost(): Boolean {
        return try {
            val remote = XVideoCatcherModule.framework.getRemotePreferences(PREF_NAME)
            if (remote.getInt(KEY_SCHEMA, 0) < CURRENT_SCHEMA) return false
            remote.getBoolean(KEY_DIAG_ENABLED, false)
        } catch (t: Throwable) {
            HostLog.log("ModuleSettings: remote read failed, defaulting to OFF: $t")
            false
        }
    }
}
