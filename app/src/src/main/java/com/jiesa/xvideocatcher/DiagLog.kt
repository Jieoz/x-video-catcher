package com.jiesa.xvideocatcher

import android.content.Context
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Diagnostic log for the hook, batched and written to shared storage by [DiagSink].
 *
 * Exists so the user never needs adb. The module has no UI of its own - it cannot, since it runs
 * inside X - so a log file the user can long-press and share is the only self-service way to see
 * what happened. Reaching for logcat instead makes a phone-only user dependent on a computer.
 *
 * Writes are queued, not synchronous: a file write per record on a UI or network path is not
 * acceptable inside a foreground app, so a daemon thread drains the queue.
 *
 * The queue is bounded and evicts the **oldest** record on overflow, and a record that could not be
 * persisted stays queued for retry. Discarding on failure is what throws away exactly the
 * attach-time evidence that proves the module loaded, since the earliest records are produced
 * before a Context exists.
 */
object DiagLog {

    private const val MAX_QUEUED = 512

    private val queue = ArrayDeque<String>()
    private val lock = Object()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var context: Context? = null

    @Volatile
    private var drainer: Thread? = null

    /** Appended to every line so a stale log is recognisable as stale. */
    @Volatile
    private var sessionTag: String = "?"

    fun setSessionTag(tag: String) {
        sessionTag = tag
    }

    /** Queues one line. Safe to call from any thread, including host UI callbacks. */
    fun line(text: String) {
        val formatted = "${stamp.format(Date())} [$sessionTag] $text"
        synchronized(lock) {
            // Evict oldest: under a flood the recent records are the ones that explain the
            // current state, and stalling the host to keep history is not an option.
            while (queue.size >= MAX_QUEUED) queue.pollFirst()
            queue.addLast(formatted)
            lock.notifyAll()
        }
    }

    /**
     * Binds the host Context and starts draining.
     *
     * Nothing can be written before this: `handleLoadPackage` runs before the host `Application`
     * exists, so there is no Context at attach time and any sink call then writes nothing - as
     * silently as the provider design did, which is why it survived that fix and looked like the
     * fix had failed. Records queued before this point are drained here.
     */
    fun bindContext(context: Context) {
        this.context = context
        startDrainer()
    }

    private fun startDrainer() {
        synchronized(lock) {
            if (drainer != null) return
            val t = Thread({ drainLoop() }, "xvc-diag")
            // Low priority daemon: this is diagnostics inside someone else's foreground app.
            t.isDaemon = true
            t.priority = Thread.MIN_PRIORITY
            drainer = t
            t.start()
        }
    }

    private fun drainLoop() {
        while (true) {
            val batch: List<String>
            synchronized(lock) {
                while (queue.isEmpty()) lock.wait()
                batch = queue.toList()
            }
            val ctx = context
            val ok = ctx != null && DiagSink.append(ctx, batch)
            synchronized(lock) {
                if (ok) {
                    // Remove exactly what was written. Anything queued meanwhile stays.
                    repeat(batch.size) { queue.pollFirst() }
                }
            }
            if (!ok) {
                // Retry rather than drop, but back off: a permanently failing sink must not spin.
                Thread.sleep(5_000)
            } else {
                Thread.sleep(500)
            }
        }
    }

    /** Blocks briefly until the queue drains, so attach-time evidence lands immediately. */
    fun flushNow(timeoutMs: Long = 2_000) {
        val ctx = context ?: return
        val batch: List<String>
        synchronized(lock) {
            if (queue.isEmpty()) return
            batch = queue.toList()
        }
        if (DiagSink.append(ctx, batch)) {
            synchronized(lock) { repeat(batch.size) { queue.pollFirst() } }
        }
    }

    /** Where the log is being written, for reporting to the user. */
    fun path(): String = DiagSink.displayPath()
}
