package com.jiesa.xvideocatcher

import android.util.Log
import com.jiesa.xvideocatcher.hook.XVideoCatcherModule
import io.github.libxposed.api.XposedInterface

/**
 * Host-side log that does not depend on a compileOnly framework class being present in unit tests.
 *
 * libxposed is injected only when LSPosed loads the module. Touching it unconditionally from a JVM
 * test throws NoClassDefFoundError, which is an Error and escapes runCatching. android.util.Log is
 * always attempted first so a missing framework still leaves a line.
 */
internal object HostLog {

    private const val TAG = "XVC"

    fun log(message: String) {
        runCatching { Log.i(TAG, message) }
        runCatching {
            XVideoCatcherModule.framework.log(XposedInterface.PRIORITY_DEFAULT, TAG, message)
        }
    }
}
