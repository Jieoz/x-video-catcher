package com.jiesa.xvideocatcher

import com.jiesa.xvideocatcher.hook.XVideoCatcherModule

/**
 * The diagnostic switch as seen from inside X (the read side).
 *
 * The switch lives in libxposed remote preferences. The module's settings app writes it through
 * the Xposed **service** ([ModuleRuntime]); X reads it here through the read-only hook interface.
 * There is no world-readable XML — the file LSPosed 2.2 warns about and 2.3 removes — and no
 * host private-directory read.
 *
 * Default: diagnostic logging off. An unreadable remote value stays off.
 */
object ModuleSettings {

    const val PREF_NAME = "xvc_settings"
    const val KEY_DIAG_ENABLED = "diag_enabled"

    /**
     * Host-side read, sampled once when X starts (see XVideoCatcherModule.install). Uses the
     * read-only hook interface. Default off when the framework or the remote group is unavailable.
     */
    fun readDiagEnabledFromHost(): Boolean = try {
        XVideoCatcherModule.framework
            .getRemotePreferences(PREF_NAME)
            .getBoolean(KEY_DIAG_ENABLED, false)
    } catch (t: Throwable) {
        HostLog.log("ModuleSettings: remote read failed, defaulting to OFF: $t")
        false
    }
}
