package com.jiesa.xvideocatcher

import android.content.Context
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Diagnostic log for the hook, off by default.
 *
 * ## Switch model (read once, no polling)
 *
 * The switch value is read **once**, outside this class, when X starts — the hook samples the
 * user's setting from libxposed remote preferences ([ModuleSettings.readDiagEnabledFromHost]) and
 * hands it to [setEnabled] via [bindContext]/attach. There is no listener, no per-record re-read,
 * and no background thread while the switch is off.
 *
 * X keeps its process alive for hours, so a value read at attach is honest only if the UI tells the
 * user how it takes effect: turning the switch on or off applies the next time X is **force-stopped
 * and reopened**. [SettingsActivity] says exactly that. (An earlier build promised "下次打开分享面板
 * 即生效", which no code implemented — that mismatch, not "read once" itself, was the 1.53 defect.)
 *
 * ## Cost
 *
 * **While OFF (the default):** [line] does a single volatile read and returns. No queue, no thread,
 * no MediaStore, no disk, no binder — zero wake-ups and zero battery cost. The drain thread is
 * created only when the switch is on.
 *
 * **While ON:** records are queued and drained on one low-priority daemon; the queue is bounded and
 * drops oldest under flood. [DiagSink] writes the bytes to Download/XVideoCatcher/.
 */
object DiagLog {

    private const val MAX_QUEUED = 512

    private val queue = ArrayDeque<String>()
    private val lock = Object()
    private val drainLock = Object()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var context: Context? = null

    @Volatile
    internal var writer: (List<String>) -> Boolean = { lines ->
        val ctx = context
        if (ctx == null) {
            HostLog.log("DiagLog: writer called but context is null")
            false
        } else {
            DiagSink.append(ctx, lines)
        }
    }

    @Volatile
    private var drainer: Thread? = null

    @Volatile
    private var bound = false

    @Volatile
    private var sessionTag: String = "?"

    /**
     * Master switch. Default off. The host reads the user's stored preference once at attach and
     * calls [setEnabled]; the value then stands for the life of X's process (see class docs). No
     * source callback, no resampling — a changed setting applies when X is force-stopped and
     * reopened, exactly as the settings screen states.
     */
    @Volatile
    private var enabled: Boolean = false

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) {
            startDrainer()
        } else {
            // Off means off with nothing left behind: drop anything queued so we neither write it
            // now nor on a later enable.
            synchronized(lock) { queue.clear() }
        }
    }

    fun setSessionTag(tag: String) {
        sessionTag = tag
    }

    /** Queues one line when enabled. Safe from any thread. */
    fun queueSize(): Int = synchronized(lock) { queue.size }

    fun line(text: String) {
        if (!enabled) return
        val formatted = "${stamp.format(Date())} [$sessionTag] $text"
        HostLog.log(formatted)
        synchronized(lock) {
            while (queue.size >= MAX_QUEUED) queue.pollFirst()
            queue.addLast(formatted)
            lock.notifyAll()
        }
    }

    fun bindContext(context: Context) {
        // applicationContext can be null in early host init; use the raw context.
        this.context = context
        writer = { lines -> DiagSink.append(context, lines) }
        bound = true
        if (enabled) startDrainer()
    }

    internal fun bindForTest() {
        bound = true
    }

    /** Redirects the sink so a test can assert on the lines that were actually emitted. */
    internal fun setWriterForTest(sink: (List<String>) -> Boolean) {
        writer = sink
    }

    private fun startDrainer() {
        synchronized(lock) {
            if (drainer != null) return
            val t = Thread({ drainLoop() }, "xvc-diag")
            t.isDaemon = true
            t.priority = Thread.MIN_PRIORITY
            drainer = t
            t.start()
        }
    }

    private fun drainLoop() {
        while (true) {
            synchronized(lock) {
                while (queue.isEmpty()) lock.wait()
            }
            if (!enabled) {
                synchronized(lock) { queue.clear() }
                continue
            }
            val ok = drainOnce()
            if (!ok) {
                Thread.sleep(5_000)
            } else {
                Thread.sleep(500)
            }
        }
    }

    fun flushNow() {
        if (!enabled) return
        drainOnce()
    }

    private fun drainOnce(): Boolean = synchronized(drainLock) {
        if (!enabled) return false
        if (!bound) {
            // Queued lines with nowhere to go. Silence here is what made "no log file" and "hook
            // never attached" indistinguishable on the last several field builds.
            HostLog.log("DiagLog: ${queueSize()} line(s) queued but sink is not bound yet")
            return false
        }
        val batch: List<String>
        synchronized(lock) {
            if (queue.isEmpty()) return false
            batch = queue.toList()
        }
        val ok = writer(batch)
        if (ok) {
            synchronized(lock) { repeat(batch.size) { queue.pollFirst() } }
        }
        return ok
    }

    fun path(): String = DiagSink.displayPath()

    internal fun resetForTest() {
        synchronized(lock) { queue.clear() }
        context = null
        bound = false
        sessionTag = "?"
        enabled = false  // default off
        drainer = null
        writer = { lines ->
            val ctx = context
            if (ctx == null) {
                HostLog.log("DiagLog: writer called but context is null")
                false
            } else {
                DiagSink.append(ctx, lines)
            }
        }
    }
}
