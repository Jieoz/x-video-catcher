package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import java.lang.ref.WeakReference

/**
 * Tracks which of the host's activities is in the foreground.
 *
 * ## Why the module needs this
 *
 * The 1.6.0-probe device log settled where the tweet is *not*: the Compose share sheet's row
 * provider and dispatchers hold a `Context`, coroutine plumbing and the share URL string. No tweet,
 * one level down included. But the sheet is opened from a tweet detail screen, and that screen still
 * holds the tweet it is displaying — so the foreground activity is the search root most likely to
 * reach it.
 *
 * ## Why lifecycle callbacks rather than ActivityThread
 *
 * `ActivityThread.currentActivityThread().mActivities` is the usual trick and it is the wrong choice
 * here. It is a private field on a hidden class, restricted on newer platforms, and it would add a
 * second host-internal shape for this module to keep matching across X releases — the exact
 * maintenance cost that has already broken this module three times.
 * `Application.registerActivityLifecycleCallbacks` is public, stable API and the host hands us an
 * `Application` at attach time anyway.
 *
 * References are weak: an activity kept alive by this object would be a leak in someone else's app,
 * and a stale foreground reference is worse than none — it would send the search walking a destroyed
 * screen's graph.
 */
internal object HostActivity {

    private var foreground: WeakReference<Any>? = null
    private var registered = false

    /**
     * Starts tracking on [application], via reflection so the module compiles and unit-tests without
     * the Android framework on the classpath.
     *
     * Idempotent: [install] runs once per process, but a host that re-attaches must not end up with
     * two callbacks appending to one field.
     */
    fun track(application: Any) {
        if (registered) return
        val appClass = application.javaClass

        // Reject the wrong argument explicitly. 1.7.0-probe was handed a ContextImpl here and the
        // only symptom was a NoSuchMethodException from the lookup below, which reads like a
        // platform restriction rather than a caller bug. Name the class instead.
        if (!isApplication(appClass)) {
            DiagLog.line("PROBE activity tracking needs an Application, got ${appClass.name}")
            return
        }

        val callbacksInterface = appClass.classLoader
            ?.loadClass("android.app.Application\$ActivityLifecycleCallbacks")
            ?: return

        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            callbacksInterface.classLoader,
            arrayOf(callbacksInterface),
        ) { _, method, args ->
            // onActivityResumed is the only event that defines "foreground". onActivityPaused
            // deliberately does not clear it: between a detail screen pausing and the sheet's own
            // activity resuming there is a window where clearing would leave no root at all.
            if (method.name == "onActivityResumed") {
                args?.getOrNull(0)?.let { foreground = WeakReference(it) }
            }
            null
        }

        runCatching {
            appClass.getMethod("registerActivityLifecycleCallbacks", callbacksInterface)
                .invoke(application, proxy)
            registered = true
        }.onFailure { DiagLog.line("PROBE activity tracking unavailable: $it") }
    }

    /** Whether [cls] is android.app.Application or a subclass, walking without a framework dep. */
    private fun isApplication(cls: Class<*>): Boolean {
        var c: Class<*>? = cls
        while (c != null) {
            if (c.name == "android.app.Application") return true
            c = c.superclass
        }
        return false
    }

    /** The foreground activity, or null if none has resumed yet or it has been collected. */
    fun current(): Any? = foreground?.get()

    /** Test seam: unit tests need to set and clear state without an Android runtime. */
    internal fun setForegroundForTest(activity: Any?) {
        foreground = activity?.let { WeakReference<Any>(it) }
    }

    /** Test seam: lets a test start from the uninstalled state. */
    internal fun resetForTest() {
        foreground = null
        registered = false
    }

    /** Whether tracking is installed. Exposed so a test can prove [track] is idempotent. */
    internal fun isRegisteredForTest(): Boolean = registered
}
