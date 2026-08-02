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

    /**
     * How a drained batch is persisted. Swappable so the queue's retry/ordering behaviour can be
     * tested against real bytes without going through MediaStore, which Robolectric cannot back.
     */
    @Volatile
    internal var writer: (List<String>) -> Boolean = { lines ->
        val ctx = context
        ctx != null && DiagSink.append(ctx, lines)
    }

    @Volatile
    private var drainer: Thread? = null

    /** Whether a sink destination is available yet. False until the host Application exists. */
    @Volatile
    private var bound = false

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
        bound = true
        startDrainer()
    }

    /**
     * Marks the sink writable without an Android Context, for tests.
     *
     * The queue logic under test - hold until bound, retry rather than drop, preserve order - is
     * pure; only [DiagSink] needs a Context. This keeps the tests on the production drain path
     * instead of re-implementing it.
     */
    internal fun bindForTest() {
        bound = true
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
            val ok = bound && writer(batch)
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
    fun flushNow() {
        if (!bound) return
        val batch: List<String>
        synchronized(lock) {
            if (queue.isEmpty()) return
            batch = queue.toList()
        }
        if (writer(batch)) {
            synchronized(lock) { repeat(batch.size) { queue.pollFirst() } }
        }
    }

    /** Where the log is being written, for reporting to the user. */
    fun path(): String = DiagSink.displayPath()

    /** Clears queue and binding so each test starts from a known state. */
    internal fun resetForTest() {
        synchronized(lock) { queue.clear() }
        context = null
        bound = false
        sessionTag = "?"
    }
}
