package com.jiesa.xvideocatcher

import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Byte-level tests for the diagnostic sink.
 *
 * Pinned to sdk 28 deliberately. Robolectric's `ShadowMediaStore` implements only thumbnail and
 * cloud-media surfaces - there is no provider backing `MediaStore.Downloads`, so `insert()` returns
 * null there no matter how correct the production code is, and an assertion on that branch fails
 * permanently while telling you nothing. On 28 `append` takes the direct-file branch, which
 * Robolectric backs with a real temp dir, so these assertions are about actual bytes on disk.
 *
 * A mocked `ContentResolver` is specifically avoided: that is the construct that reported success
 * while dropping every record in the original shipped-broken version.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiagSinkTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Suppress("DEPRECATION")
    private fun logFile(): File = File(
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DiagSink.DIR_NAME,
        ),
        DiagSink.fileName(),
    )

    @Test
    fun `append writes one line per record`() {
        assertTrue(DiagSink.append(context, listOf("first", "second")))

        val lines = logFile().readText().trimEnd('\n').split("\n")
        assertEquals(listOf("first", "second"), lines)
    }

    @Test
    fun `append twice keeps earlier records`() {
        assertTrue(DiagSink.append(context, listOf("one")))
        assertTrue(DiagSink.append(context, listOf("two")))

        // Truncating instead of appending would lose the whole session so far - including the
        // attach-time evidence that proves the module loaded.
        val lines = logFile().readText().trimEnd('\n').split("\n")
        assertEquals(listOf("one", "two"), lines)
    }

    @Test
    fun `a record containing a newline still occupies one line`() {
        assertTrue(DiagSink.append(context, listOf("before\nafter", "next")))

        val lines = logFile().readText().trimEnd('\n').split("\n")
        assertEquals(2, lines.size)
        assertEquals("before after", lines[0])
        assertEquals("next", lines[1])
    }

    @Test
    fun `empty batch creates no file`() {
        assertTrue(DiagSink.append(context, emptyList()))
        assertFalse(logFile().exists())
    }

    @Test
    fun `displayPath matches the real write location`() {
        assertTrue(DiagSink.append(context, listOf("x")))

        // Drift between the advertised path and the actual one makes the user look in the wrong
        // folder and report "no log" on a perfectly working build.
        val actual = logFile().absolutePath.replace(File.separatorChar, '/')
        assertTrue(
            "advertised ${DiagSink.displayPath()} not found in $actual",
            actual.endsWith(DiagSink.displayPath()),
        )
    }

    @Test
    fun `file name carries the date`() {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        assertEquals("xvc-diag-$day.log", DiagSink.fileName())
    }
}
