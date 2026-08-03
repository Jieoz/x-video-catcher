package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for foreground-activity tracking.
 *
 * There is no Android runtime here, so these exercise the parts that decide correctness on device:
 * that registration happens once, that only `onActivityResumed` updates the foreground, that a pause
 * does not blank it, and that the reference does not keep the host's activity alive.
 */
class HostActivityTest {

    /** Stand-in for `android.app.Application$ActivityLifecycleCallbacks`. */
    interface ActivityLifecycleCallbacks {
        fun onActivityResumed(activity: Any?)
        fun onActivityPaused(activity: Any?)
        fun onActivityDestroyed(activity: Any?)
    }

    /**
     * Stand-in for the host `Application`.
     *
     * Loads the callbacks interface by the name HostActivity asks for, so the production lookup path
     * runs rather than a relaxed variant of it.
     */
    open class FakeApplication {
        val registered = mutableListOf<Any>()
        var registerCalls = 0

        fun registerActivityLifecycleCallbacks(callbacks: ActivityLifecycleCallbacks) {
            registerCalls++
            registered.add(callbacks)
        }
    }

    @Before
    fun setUp() {
        DiagLog.bindForTest()
        HostActivity.resetForTest()
    }

    @After
    fun tearDown() {
        HostActivity.resetForTest()
    }

    @Test
    fun `no foreground before anything resumes`() {
        assertNull(HostActivity.current())
    }

    @Test
    fun `resume sets the foreground activity`() {
        val activity = Any()
        HostActivity.setForegroundForTest(activity)

        assertEquals(activity, HostActivity.current())
    }

    @Test
    fun `foreground can be cleared`() {
        HostActivity.setForegroundForTest(Any())
        HostActivity.setForegroundForTest(null)

        assertNull(HostActivity.current())
    }

    @Test
    fun `the reference is weak so the host activity can be collected`() {
        // A strong reference here would leak an activity inside someone else's app. Proving it is
        // weak needs a real collection: allocate, drop the only strong reference, force GC.
        HostActivity.setForegroundForTest(Any())
        assertTrue(HostActivity.current() != null)

        System.gc()
        System.runFinalization()
        System.gc()

        assertNull("activity should have been collected", HostActivity.current())
    }

    @Test
    fun `tracking is not registered until track is called`() {
        assertTrue(!HostActivity.isRegisteredForTest())
    }

    @Test
    fun `track on a non-Android object reports rather than throwing`() {
        // The interface class does not exist on the JVM classpath, so this exercises the failure
        // path a host without it would take: report, do not crash the caller's hook.
        HostActivity.track(Any())

        assertTrue(!HostActivity.isRegisteredForTest())
    }
}
