package com.jiesa.xvideocatcher

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Write side of the diagnostic switch, used only by the module's own settings UI process.
 *
 * ## Why this is separate from the hook read
 *
 * libxposed (API 102) exposes **two different interfaces that are not interchangeable**:
 *
 *  - **Read side (inside X):** the module is loaded into the host, so
 *    [XVideoCatcherModule.framework] is a real [io.github.libxposed.api.XposedInterface] and its
 *    `getRemotePreferences` is **read-only** — exactly what the hook needs to sample the switch
 *    once at startup ([ModuleSettings.readDiagEnabledFromHost]).
 *  - **Write side (this settings app):** the module package is not in its own scope, so the hook is
 *    never loaded here and [XVideoCatcherModule.framework] is an uninitialised `lateinit`. Touching
 *    it throws. The UI must reach the framework through [XposedService] instead — delivered by the
 *    `XposedProvider` merged in from the libxposed-service AAR via
 *    [XposedServiceHelper.registerListener]. Only [XposedService.getRemotePreferences] is writable,
 *    and the value it commits is what X later reads.
 *
 * Writing the switch through the hook interface from this process — what every build before this
 * one did — silently failed: the field is null here, and even where present the interface is
 * read-only. That is why the switch never turned on.
 *
 * Everything fails closed: no service means the switch reads OFF and a write reports failure.
 */
object ModuleRuntime {

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var listenerRegistered = false

    /**
     * Begin listening for the Xposed service. Idempotent; call once from the settings UI. The
     * service binds asynchronously a moment after the process starts (only when the module is
     * activated in LSPosed), so the UI enables its controls once [serviceReady] is true.
     */
    @Synchronized
    fun startServiceBinding() {
        if (listenerRegistered) return
        listenerRegistered = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(bound: XposedService) {
                service = bound
            }

            override fun onServiceDied(dead: XposedService) {
                if (service === dead) service = null
            }
        })
    }

    /** True when the writable Xposed service is connected in this (module app) process. */
    fun serviceReady(): Boolean = service != null

    /** Read the switch for UI display through the writable service's remote group. Default off. */
    fun readSwitch(group: String, key: String): Boolean = try {
        service?.getRemotePreferences(group)?.getBoolean(key, false) ?: false
    } catch (t: Throwable) {
        false
    }

    /** Write the switch through the Xposed service. True only when committed to the remote group. */
    fun writeSwitch(group: String, key: String, value: Boolean): Boolean = try {
        val bound = service
        if (bound == null) {
            false
        } else {
            bound.getRemotePreferences(group).edit().putBoolean(key, value).commit()
        }
    } catch (t: Throwable) {
        false
    }
}
