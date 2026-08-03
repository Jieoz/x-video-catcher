package com.jiesa.xvideocatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Byte-level tests for the diagnostic sink, over a real temp directory.
 *
 * Two branches exist in production: MediaStore on API 29+, direct file below. These drive the file
 * branch, and that is not a shortcut — Robolectric's `ShadowMediaStore` implements only thumbnail
 * and cloud-media surfaces, so there is no provider backing `MediaStore.Downloads` and `insert()`
 * returns null there however correct the code is. Asserting through a mocked `ContentResolver` is
 * worse still: that is exactly the construct that reported success while dropping every record in
 * the version that shipped broken. MediaStore correctness is established on-device, by the log
 * appearing in `Download/XVideoCatcher/`.
 *
 * Deliberately plain JUnit: Robolectric pulls in a conscrypt native library with no aarch64 build,
 * which makes the whole suite unrunnable on an arm64 machine.
 */
class DiagSinkTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dir(): File = temp.root
    private fun logFile(): File = File(temp.root, DiagSink.fileName())

    @Test
    fun `append writes one line per record`() {
        assertTrue(DiagSink.appendDirectForTest(dir(), listOf("first", "second")))

        val lines = logFile().readText().trimEnd('\n').split("\n")
        assertEquals(listOf("first", "second"), lines)
    }

    @Test
    fun `append twice keeps earlier records`() {
        assertTrue(DiagSink.appendDirectForTest(dir(), listOf("one")))
        assertTrue(DiagSink.appendDirectForTest(dir(), listOf("two")))

        // Truncating instead of appending would lose the whole session so far — including the
        // attach-time evidence that proves the module loaded.
        val lines = logFile().readText().trimEnd('\n').split("\n")
        assertEquals(listOf("one", "two"), lines)
    }

    @Test
    fun `a record containing a newline still occupies one line`() {
        assertTrue(DiagSink.appendDirectForTest(dir(), listOf("before\nafter", "next")))

        val lines = logFile().readText().trimEnd('\n').split("\n")
        assertEquals(2, lines.size)
        assertEquals("before after", lines[0])
        assertEquals("next", lines[1])
    }

    @Test
    fun `a record containing a carriage return still occupies one line`() {
        assertEquals("a b\n", DiagSink.payloadOf(listOf("a\rb")))
    }

    @Test
    fun `empty batch creates no file`() {
        assertTrue(DiagSink.appendDirectForTest(dir(), emptyList()))
        assertFalse(logFile().exists())
    }

    @Test
    fun `missing directory is created`() {
        val nested = File(temp.root, "XVideoCatcher")
        assertFalse(nested.exists())

        assertTrue(DiagSink.appendDirectForTest(nested, listOf("x")))
        assertTrue(File(nested, DiagSink.fileName()).exists())
    }

    @Test
    fun `displayPath is the advertised location and carries the file name`() {
        // Drift between the advertised path and the real write location makes the user look in the
        // wrong folder and report "no log" on a perfectly working build.
        assertEquals("Download/${DiagSink.DIR_NAME}/${DiagSink.fileName()}", DiagSink.displayPath())
    }

    @Test
    fun `file name carries the date`() {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        assertEquals("xvc-diag-$day.log", DiagSink.fileName())
    }

    /**
     * A row store with MediaStore's actual concurrency behaviour.
     *
     * The defect being tested is not a torn write — it is a *duplicated row*. MediaStore does not
     * reject a second `insert()` for a name that already exists; it silently creates another file with
     * a ` (1)` suffix. So this fake does the same: every `create` makes a new row, and a lookup only
     * finds a row that was already committed. Concurrency is made deterministic with a barrier rather
     * than left to chance, because a race that reproduces "sometimes" is a test that passes
     * "sometimes".
     */
    private class FakeRows(private val writers: Int) : DiagSink.Rows {
        private val lock = Object()
        private val rows = LinkedHashMap<String, StringBuilder>()
        private var nextId = 0
        private var arrived = 0

        /** Rows created. More than one means the split-file bug. */
        fun rowCount(): Int = synchronized(lock) { rows.size }

        fun contentOf(index: Int): String =
            synchronized(lock) { rows.values.toList()[index].toString() }

        /**
         * Releases every caller only once all of them have looked up the row.
         *
         * This is what forces the interleaving the user hit: without the barrier the first writer
         * usually finishes before the second looks, and an unsynchronised implementation would pass.
         */
        private fun barrier() {
            synchronized(lock) {
                arrived++
                if (arrived >= writers) {
                    lock.notifyAll()
                } else {
                    val deadline = System.currentTimeMillis() + 5_000
                    while (arrived < writers && System.currentTimeMillis() < deadline) {
                        lock.wait(50)
                    }
                }
            }
        }

        override fun find(name: String, relativePath: String): String? {
            val existing = synchronized(lock) { rows.keys.firstOrNull() }
            barrier()
            return existing
        }

        override fun create(name: String, relativePath: String): String = synchronized(lock) {
            // MediaStore's real behaviour: a duplicate name is suffixed, not refused.
            val id = "row-${nextId++}"
            rows[id] = StringBuilder()
            id
        }

        override fun write(rowId: String, payload: String): Boolean = synchronized(lock) {
            rows[rowId]!!.append(payload)
            true
        }
    }

    /**
     * Concurrent appends must land in exactly one file.
     *
     * Regression test for a real field failure. A user's diagnostic log arrived as *two* files —
     * `xvc-diag-20260803.log` and `xvc-diag-20260803.log (1)` — with one session split between them
     * and neither complete. The attach-time `flushNow()` on the host's main thread raced the drainer
     * thread; both found no existing row, both inserted, and MediaStore de-duplicated by suffixing
     * the display name. The log looked truncated at exactly the moment it was being read to diagnose
     * something else.
     *
     * This drives [DiagSink.appendTo] — the real find-or-create-then-write ordering — through a fake
     * that reproduces MediaStore's duplicate-on-insert behaviour. The direct-file branch cannot
     * express this bug at all: `appendText` opens with `O_APPEND`, whose writes the kernel already
     * serialises, so a file-based test stays green with or without the lock. Ablation caught exactly
     * that, and this replaced it.
     *
     * Ablation-checked: removing `synchronized(writeLock)` from [DiagSink.appendTo] makes this fail
     * with 2 rows instead of 1.
     */
    @Test
    fun `concurrent appends land in exactly one row`() {
        val writers = 2
        val rows = FakeRows(writers)
        val done = java.util.concurrent.CountDownLatch(writers)
        val failures = java.util.concurrent.atomic.AtomicInteger()

        repeat(writers) { t ->
            Thread {
                if (!DiagSink.appendTo(rows, "writer-$t\n")) failures.incrementAndGet()
                done.countDown()
            }.start()
        }

        assertTrue("writers must finish", done.await(30, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("no append may report failure", 0, failures.get())
        assertEquals(
            "both writers must share one row — a second row is the split-file bug",
            1,
            rows.rowCount(),
        )

        val content = rows.contentOf(0)
        assertTrue("first writer's record must be present: $content", content.contains("writer-0"))
        assertTrue("second writer's record must be present: $content", content.contains("writer-1"))
    }

    @Test
    fun `append creates the row when none exists`() {
        val rows = FakeRows(1)
        assertTrue(DiagSink.appendTo(rows, "first\n"))
        assertEquals(1, rows.rowCount())
        assertEquals("first\n", rows.contentOf(0))
    }

    @Test
    fun `append reuses an existing row rather than creating a second`() {
        // Pins find-before-create. Reversing them would start a new file on every flush.
        val rows = FakeRows(1)
        assertTrue(DiagSink.appendTo(rows, "one\n"))
        assertTrue(DiagSink.appendTo(rows, "two\n"))
        assertEquals("must append to the same row", 1, rows.rowCount())
        assertEquals("one\ntwo\n", rows.contentOf(0))
    }

    @Test
    fun `append reports failure when a row cannot be created`() {
        // A sink that cannot write must say so: DiagLog keeps the batch queued and retries, which is
        // what preserves attach-time evidence across a failing sink.
        val refusing = object : DiagSink.Rows {
            override fun find(name: String, relativePath: String): String? = null
            override fun create(name: String, relativePath: String): String? = null
            override fun write(rowId: String, payload: String) = true
        }
        assertFalse(DiagSink.appendTo(refusing, "x\n"))
    }
}
