package com.jiesa.xvideocatcher

/**
 * Writes a line to logcat via whichever channels are actually present.
 *
 * Calling [de.robv.android.xposed.XposedBridge.log] directly is not safe from arbitrary code in
 * this module. The Xposed API is `compileOnly`: it exists when the module is loaded into a host by
 * LSPosed, and it does **not** exist in a JVM unit test, where touching it raises
 * `NoClassDefFoundError`. That is an `Error`, so `runCatching` at the call site does not stop it
 * either — it is not an `Exception`.
 *
 * That distinction is not academic. It is the failure mode this whole file exists to prevent: a
 * diagnostic line added to a rarely-taken branch compiles fine, passes review, and then throws from
 * inside a hook on device — where the module swallows it to avoid killing the host, and the symptom
 * is not a stack trace but a *missing feature*. A debug build shipped that way lost the injected
 * download row entirely, and the log meant to explain it was the thing that broke it.
 *
 * So every host-side log goes through here, catching [Throwable] rather than [Exception], and
 * writing to `android.util.Log` as well so the line survives when the bridge is absent.
 */
internal object HostLog {

    private const val TAG = "XVC"

    fun log(message: String) {
        // Independent, both best-effort: one being unavailable must not suppress the other.
        runCatching { android.util.Log.i(TAG, message) }
        runCatching { de.robv.android.xposed.XposedBridge.log("$TAG $message") }
    }
}
