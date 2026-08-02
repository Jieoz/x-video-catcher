package com.jiesa.xvideocatcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Queue behaviour, which is where the original version was broken.
 *
 * The failure being guarded against: records produced before a host `Context` existed were dropped,
 * silently discarding exactly the attach-time evidence that proves the module loaded. Since
 * `handleLoadPackage` runs before the host `Application` is created, those are the *first* records of
 * every session — so "queue until bound, then drain" is the contract, not an optimisation.
 *
 * Persistence is captured through the writer seam, so these assert what the queue hands to the sink
 * and in what order. [DiagSinkTest] covers the bytes that reach disk.
 */
class DiagLogTest {

    private val written = mutableListOf<String>()

    @Before
    fun setUp() {
        DiagLog.resetForTest()
        written.clear()
        DiagLog.writer = { lines -> written.addAll(lines); true }
    }

    @After
    fun tearDown() {
        DiagLog.resetForTest()
    }

    @Test
    fun `records logged before the sink is available survive until it is`() {
        DiagLog.setSessionTag("test")
        DiagLog.line("queued-before-context")
        DiagLog.flushNow()

        // Not bound yet: nothing may have been persisted, and nothing may have been lost.
        assertTrue("wrote before a destination existed", written.isEmpty())

        DiagLog.bindForTest()
        DiagLog.flushNow()

        assertTrue(
            "attach-time record was dropped instead of queued",
            written.any { it.contains("queued-before-context") },
        )
    }

    @Test
    fun `a failing writer keeps records queued for retry`() {
        DiagLog.bindForTest()
        DiagLog.writer = { false }
        DiagLog.line("must-survive")
        DiagLog.flushNow()

        // Let writes succeed: the record must still be there. Discarding on failure is what threw
        // away the attach-time evidence in the original bug.
        DiagLog.writer = { lines -> written.addAll(lines); true }
        DiagLog.flushNow()

        assertTrue(
            "record was discarded after a failed write",
            written.any { it.contains("must-survive") },
        )
    }

    @Test
    fun `a successful write does not re-emit already written records`() {
        DiagLog.bindForTest()
        DiagLog.line("once")
        DiagLog.flushNow()
        DiagLog.flushNow()

        assertEquals(1, written.count { it.contains("once") })
    }

    @Test
    fun `records are persisted in order`() {
        DiagLog.bindForTest()
        DiagLog.line("first")
        DiagLog.line("second")
        DiagLog.line("third")
        DiagLog.flushNow()

        assertEquals(3, written.size)
        assertTrue(written[0].contains("first"))
        assertTrue(written[1].contains("second"))
        assertTrue(written[2].contains("third"))
    }

    @Test
    fun `lines carry a timestamp and the session tag`() {
        DiagLog.setSessionTag("12.13.0")
        DiagLog.bindForTest()
        DiagLog.line("hello")
        DiagLog.flushNow()

        val line = written.last { it.contains("hello") }
        assertTrue("missing session tag: $line", line.contains("[12.13.0]"))
        assertTrue(
            "missing timestamp: $line",
            Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} """).containsMatchIn(line),
        )
    }

    @Test
    fun `the queue is bounded and drops oldest under flood`() {
        DiagLog.setSessionTag("t")
        // Not bound: everything accumulates, so the bound is what is being tested.
        repeat(600) { DiagLog.line("record-$it") }

        DiagLog.bindForTest()
        DiagLog.flushNow()

        // Bound is 512. The newest records are the ones that explain current state, so the oldest
        // are the ones that must have been evicted.
        assertEquals(512, written.size)
        assertTrue("oldest record survived past the bound", written.none { it.contains("record-0 ") })
        assertTrue("newest record was evicted", written.any { it.contains("record-599") })
    }

    @Test
    fun `advertised path is the sink path`() {
        assertTrue(DiagLog.path().startsWith("Download/${DiagSink.DIR_NAME}/"))
    }
}
