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
        assertEquals("xvc-diag-$day.txt", DiagSink.fileName())
    }

    /**
     * The extension must be one MediaStore accepts for the declared MIME type.
     *
     * Field failure this pins: the name ended `.log` while the row declared `text/plain`. MediaStore
     * does not reject that combination, it *renames* -- storing `xvc-diag-20260803.log.txt`. Every
     * subsequent `find()` then queried the pre-rename name, missed, and created another row: 32
     * fragment files for a single session, and the path shown to the user did not exist.
     *
     * Asserting `.txt` specifically, not merely "has an extension": the whole defect was a name the
     * store would not keep as given.
     */
    @Test
    fun `file name extension matches the declared mime type`() {
        assertTrue(
            "MediaStore renames a text/plain row that does not end .txt",
            DiagSink.fileName().endsWith(".txt"),
        )
    }

    /**
     * Sequential appends must reuse the row, not create one per flush.
     *
     * The 1.5.0-probe field failure, reduced: one session produced 32 numbered files because the
     * name written and the name queried differed by the extension MediaStore had appended. Nothing
     * concurrent about it -- every flush created a fresh row.
     *
     * Load-bearing check: revert [DiagSink.fileName] to `.log` and this fails, because [FakeRows]
     * renames on create exactly as the real store does.
     */
    @Test
    fun `repeated appends reuse a single row`() {
        val rows = FakeRows(writers = 1)
        repeat(4) { i -> assertTrue(DiagSink.appendTo(rows, "line-$i\n")) }

        assertEquals(
            "each flush created its own row: the stored name never matches the queried one",
            1,
            rows.rowCount(),
        )
        assertEquals("line-0\nline-1\nline-2\nline-3\n", rows.contentOf(0))
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
        /** A stored row: the name the store kept, which is not always the name asked for. */
        private class Row(val name: String, val body: StringBuilder)

        private val lock = Object()
        private val rows = LinkedHashMap<String, Row>()
        private var nextId = 0
        private var arrived = 0

        /** Rows created. More than one means the split-file bug. */
        fun rowCount(): Int = synchronized(lock) { rows.size }

        fun contentOf(index: Int): String =
            synchronized(lock) { rows.values.toList()[index].body.toString() }

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

        /**
         * Looks up by stored display name, the way the real query does.
         *
         * This used to return `rows.keys.firstOrNull()`, ignoring [name] -- so it always "found" the
         * row and could never express a lookup that misses its own creation. That is precisely how a
         * MIME/extension mismatch fails in the field, and it shipped green past this suite. Keyed on
         * the *stored* name so [create]'s rename is visible here.
         */
        override fun find(name: String, relativePath: String): String? {
            val existing = synchronized(lock) {
                rows.entries.firstOrNull { it.value.name == name }?.key
            }
            barrier()
            return existing
        }

        override fun create(name: String, relativePath: String): String = synchronized(lock) {
            // Both real behaviours of insert(): a duplicate name is suffixed rather than refused,
            // and a name whose extension contradicts the MIME type is renamed rather than kept.
            val stored = if (name.endsWith(".txt")) name else "$name.txt"
            val id = "row-${nextId++}"
            rows[id] = Row(stored, StringBuilder())
            id
        }

        override fun write(rowId: String, payload: String): Boolean = synchronized(lock) {
            rows[rowId]!!.body.append(payload)
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
