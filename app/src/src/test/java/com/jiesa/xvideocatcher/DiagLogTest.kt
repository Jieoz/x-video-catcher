package com.jiesa.xvideocatcher

import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests the queue behaviour that the original broken version got wrong.
 *
 * The failure being guarded against: records logged before a Context exists were dropped, which
 * silently discarded exactly the attach-time evidence proving the module had loaded. Queue-then-bind
 * must preserve them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiagLogTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Suppress("DEPRECATION")
    private fun logText(): String {
        val f = File(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DiagSink.DIR_NAME,
            ),
            DiagSink.fileName(),
        )
        return if (f.exists()) f.readText() else ""
    }

    @Test
    fun `records logged before a context exists survive until bind`() {
        DiagLog.setSessionTag("test")
        DiagLog.line("queued-before-context")

        // No Context yet, so nothing can have been written.
        assertTrue("log should not exist before bind", logText().isEmpty())

        DiagLog.bindContext(context)
        DiagLog.flushNow()

        assertTrue(
            "attach-time record was dropped instead of queued",
            logText().contains("queued-before-context"),
        )
    }

    @Test
    fun `lines carry a timestamp and the session tag`() {
        DiagLog.setSessionTag("12.13.0")
        DiagLog.bindContext(context)
        DiagLog.line("hello")
        DiagLog.flushNow()

        val written = logText().trimEnd('\n').split("\n").last { it.contains("hello") }
        assertTrue("missing session tag: $written", written.contains("[12.13.0]"))
        assertTrue("missing timestamp: $written", Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} """).containsMatchIn(written))
    }

    @Test
    fun `advertised path is the sink path`() {
        assertTrue(DiagLog.path().startsWith("Download/${DiagSink.DIR_NAME}/"))
    }
}
