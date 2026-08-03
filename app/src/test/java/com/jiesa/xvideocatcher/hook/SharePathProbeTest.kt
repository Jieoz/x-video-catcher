package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hook installation must be per-hook, not all-or-nothing.
 *
 * The 1.5.0-probe field failure was not the unhookable method by itself -- it was that one throw
 * during install took every later hook, the flush, and the summary line with it. Partial
 * instrumentation then presented on the device as total silence, which is the exact ambiguity this
 * probe exists to remove.
 *
 * [SharePathProbe.installHook] holds that containment and is tested directly. Its body touches no
 * Xposed API, so it runs on the JVM without a host; the real `XposedBridge.hookMethod` call sits in
 * the lambda passed to it, which is what these tests substitute.
 */
class SharePathProbeTest {

    private val lines = mutableListOf<String>()

    @Before
    fun captureLog() {
        DiagLog.resetForTest()
        lines.clear()
        // bindForTest is required: until a destination is bound DiagLog queues rather than writing,
        // which is its documented contract. Without this the writer seam never sees anything.
        DiagLog.bindForTest()
        DiagLog.writer = { batch -> lines.addAll(batch); true }
    }

    @After
    fun releaseLog() {
        DiagLog.resetForTest()
    }

    private fun probe() = SharePathProbe(javaClass.classLoader!!)

    @Test
    fun `a failing hook does not stop later hooks`() {
        val p = probe()
        val installed = mutableListOf<String>()

        p.installHook("first") { installed += "first" }
        p.installHook("second") {
            throw IllegalArgumentException("Cannot hook abstract methods")
        }
        p.installHook("third") { installed += "third" }

        assertEquals(
            "a throw during install must not skip the hooks after it",
            listOf("first", "third"),
            installed,
        )
    }

    @Test
    fun `a failing hook is named in the log`() {
        val p = probe()
        p.installHook("dispatch com.x.dms.components.sharesheet.r.h") {
            throw IllegalArgumentException("Cannot hook abstract methods")
        }
        DiagLog.flushNow()

        val failure = lines.singleOrNull { it.contains("hook FAILED") }
        assertTrue("the failure must be logged, got $lines", failure != null)
        assertTrue(
            "the log must name which hook failed, so a missing marker is attributable: $failure",
            failure!!.contains("dispatch com.x.dms.components.sharesheet.r.h"),
        )
        assertTrue(
            "the cause must be recorded, not just the fact of failure: $failure",
            failure.contains("Cannot hook abstract methods"),
        )
    }

    @Test
    fun `a successful hook logs nothing`() {
        val p = probe()
        p.installHook("quiet") { }
        DiagLog.flushNow()

        assertTrue(
            "install noise on the success path buries the markers that matter: $lines",
            lines.none { it.contains("hook FAILED") },
        )
    }
}
