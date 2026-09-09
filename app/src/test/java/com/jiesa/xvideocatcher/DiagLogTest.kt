package com.jiesa.xvideocatcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Production default is off; tests exercise the queue, so enable explicitly.
        DiagLog.setEnabled(true)
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

    @Test
    fun `concurrent drains must not write the same record twice`() {
        // Real defect, observed on device in 1.6.0-probe: xvc-diag-20260804.txt contains 55 extra
        // lines, including `PROBE rows built` three times at an identical millisecond timestamp.
        //
        // flushNow() and the drain thread each snapshot the queue and then write OUTSIDE the lock,
        // so both can hold the same batch at once, write it, and only then remove it. The log stops
        // being a faithful record of what the hook did, which is the only thing it exists for.
        DiagLog.setSessionTag("t")
        DiagLog.bindForTest()

        val entered = java.util.concurrent.CountDownLatch(2)
        val release = java.util.concurrent.CountDownLatch(1)
        val writes = java.util.Collections.synchronizedList(mutableListOf<String>())

        // Barrier writer: forces both drains to be in flight simultaneously. Without this the first
        // writer usually finishes before the second snapshots, and an unsynchronised implementation
        // passes anyway.
        DiagLog.writer = { lines ->
            writes.addAll(lines)
            entered.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            true
        }

        DiagLog.line("only-once")

        val a = Thread { DiagLog.flushNow() }
        val b = Thread { DiagLog.flushNow() }
        a.start()
        b.start()

        // If drains are serialised, only one writer can be inside the seam; the second must wait for
        // the first to finish, so this times out and the barrier never traps two at once.
        val bothInside = entered.await(2, java.util.concurrent.TimeUnit.SECONDS)
        release.countDown()
        a.join(5_000)
        b.join(5_000)

        assertFalse(
            "two drains wrote concurrently: the same batch can be persisted twice",
            bothInside,
        )
        assertEquals(
            "record was written ${writes.count { it.contains("only-once") }} times, expected once",
            1,
            writes.count { it.contains("only-once") },
        )
    }

    /**
     * The 1.53 field defect: "关闭日志功能好像没有效果，日志依旧生成".
     *
     * The switch was sampled once when the module attached and cached for the life of X's process,
     * so flipping it off in the module app changed nothing until the host was force-stopped. These
     * assert the switch is re-read from its authority while the process keeps running.
     */
    @Test
    fun `turning the switch off while the host runs stops new records`() {
        DiagLog.setSessionTag("t")
        DiagLog.bindForTest()

        var userSetting = true
        var now = 10_000L
        DiagLog.clock = { now }
        DiagLog.bindEnabledSource { userSetting }

        DiagLog.line("while-on")
        DiagLog.flushNow()
        assertTrue("record dropped while the switch was on", written.any { it.contains("while-on") })

        // User flips the switch off in the module app. The host is not restarted.
        userSetting = false
        now += 1_500  // past the resample interval

        DiagLog.line("after-off")
        DiagLog.flushNow()
        assertTrue(
            "log kept growing after the switch was turned off",
            written.none { it.contains("after-off") },
        )
    }

    @Test
    fun `turning the switch on while the host runs starts recording`() {
        DiagLog.setSessionTag("t")
        DiagLog.bindForTest()

        var userSetting = false
        var now = 10_000L
        DiagLog.clock = { now }
        DiagLog.bindEnabledSource { userSetting }

        DiagLog.line("while-off")
        DiagLog.flushNow()
        assertTrue(written.none { it.contains("while-off") })

        userSetting = true
        now += 1_500

        DiagLog.line("after-on")
        DiagLog.flushNow()
        assertTrue(
            "switch turned on but nothing was recorded",
            written.any { it.contains("after-on") },
        )
    }

    @Test
    fun `the authority is not consulted once per record`() {
        DiagLog.setSessionTag("t")
        DiagLog.bindForTest()

        var reads = 0
        var now = 10_000L
        DiagLog.clock = { now }
        DiagLog.bindEnabledSource { reads++; true }

        val afterBind = reads
        repeat(200) { DiagLog.line("record-$it") }

        assertEquals(
            "the preference file was read per log record, which puts a stat on every hook path",
            afterBind,
            reads,
        )

        now += 1_500
        DiagLog.line("later")
        assertEquals("the switch was never re-read after the interval elapsed", afterBind + 1, reads)
    }

    @Test
    fun `a throwing authority leaves the last known setting in place`() {
        DiagLog.setSessionTag("t")
        DiagLog.bindForTest()

        var now = 10_000L
        var explode = false
        DiagLog.clock = { now }
        DiagLog.bindEnabledSource {
            if (explode) throw IllegalStateException("prefs unreadable")
            true
        }

        explode = true
        now += 1_500

        DiagLog.line("kept-on")
        DiagLog.flushNow()
        assertTrue(
            "an unreadable preference silently disabled logging mid-session",
            written.any { it.contains("kept-on") },
        )
    }
}
