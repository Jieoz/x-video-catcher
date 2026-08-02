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
}
