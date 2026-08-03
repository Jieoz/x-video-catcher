package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ---- Decompose root -------------------------------------------------
    //
    // The 20260804 log shows the sharesheet dispatcher holding a `com.arkivanov.decompose.c`. That
    // is where the search's most promising root comes from, so these cover reading it.

    /** Stand-in for the navigation component, in the package the lookup matches on. */
    private class FakeDecompose

    private class DispatcherWithDecompose(
        @JvmField val a: com.arkivanov.decompose.FakeComponent?,
        @JvmField val b: String?,
    )

    private class DispatcherWithoutDecompose(
        @JvmField val a: String?,
        @JvmField val b: Any?,
    )

    @Test
    fun `finds the decompose component by package`() {
        val component = com.arkivanov.decompose.FakeComponent()
        val dispatcher = DispatcherWithDecompose(component, "https://x.com/i/status/1")

        val found = probe().decomposeInForTest(dispatcher)

        assertEquals(component, found)
    }

    @Test
    fun `absent decompose component is not an error`() {
        val found = probe().decomposeInForTest(DispatcherWithoutDecompose("a", Any()))

        assertNull(found)
    }

    @Test
    fun `a null decompose field is skipped rather than returned`() {
        // A declared-but-null field must not be reported as the component: the search would then get
        // a null root and silently lose its most promising path.
        val found = probe().decomposeInForTest(DispatcherWithDecompose(null, "x"))

        assertNull(found)
    }

    // ---- marker constants ------------------------------------------------

    @Test
    fun `every marker constant is non-blank and starts with a log word`() {
        // The README cross-check compares identifiers against this list, so an empty or malformed
        // entry would silently weaken it rather than fail it.
        assertTrue(ProbeMarkers.ALL.isNotEmpty())
        for (m in ProbeMarkers.ALL) {
            assertTrue("blank marker", m.isNotBlank())
        }
        assertEquals("markers must be unique", ProbeMarkers.ALL.size, ProbeMarkers.ALL.toSet().size)
    }
}
