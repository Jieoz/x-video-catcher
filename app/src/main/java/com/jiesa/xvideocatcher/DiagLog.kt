package com.jiesa.xvideocatcher

import android.content.Context
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Diagnostic log for the hook, batched and written to shared storage by [DiagSink].
 *
 * **Default off**. Open the module app → Settings to turn on; the switch is re-read while X runs,
 * so turning it off stops the file growing without restarting the host.
 * When disabled, [line] is a no-op and nothing is written to Download/XVideoCatcher/.
 *
 * Exists so the user never needs adb when debugging. Writes are queued on a low-priority
 * daemon; the queue is bounded and drops oldest under flood.
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
     * Master switch. Default off; the host turns it on from the user's stored preference.
     *
     * This is a *cache* of [enabledSource], not the authority. See [currentlyEnabled].
     */
    @Volatile
    private var enabled: Boolean = false

    /**
     * Live view of the user's setting, or null when nobody supplied one (tests, and the window
     * before the host binds).
     *
     * ## Why a source and not just a value
     *
     * Until 1.54 the host sampled the preference exactly once, in `handleLoadPackage`, and stored
     * the result here for the life of the process. Turning the switch **off** therefore did nothing
     * observable: X keeps its process alive for hours, so the module went on writing with the value
     * it read at attach. The settings screen even promised "改完后下次打开分享面板即生效", which no code
     * implemented. The switch only ever appeared to work when the user happened to restart X.
     *
     * The fix has to be re-reading, not a wider default: the authority is a file the *other* process
     * owns, so any cached copy here is stale by construction.
     */
    @Volatile
    private var enabledSource: (() -> Boolean)? = null

    @Volatile
    private var lastSampledAt = 0L

    /**
     * How stale the cached switch may be. Re-reading is a `stat` plus, when the file changed, a
     * small parse — cheap, but [line] is called ~160 places on interaction paths, so it is not run
     * per record. A second of latency on a manual toggle is imperceptible; a `stat` per log line
     * would not be.
     */
    private const val RESAMPLE_INTERVAL_MS = 1_000L

    /**
     * Time source, as a seam. Real elapsed time would make the re-read test either slow (sleep past
     * the interval) or flaky, and a test that sleeps is a test that gets deleted.
     */
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    fun isEnabled(): Boolean = currentlyEnabled()

    /**
     * Binds the authority for the switch. Sampled immediately, then at most every
     * [RESAMPLE_INTERVAL_MS] from [line].
     */
    fun bindEnabledSource(source: () -> Boolean) {
        enabledSource = source
        setEnabled(runCatching { source() }.getOrDefault(false))
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        lastSampledAt = clock()
        if (!value) {
            // Drop anything already queued so a later enable starts clean, and so that turning the
            // switch off cannot flush records captured while it was on.
            synchronized(lock) { queue.clear() }
        }
    }

    /**
     * The switch as the user currently has it, re-reading the bound source when the cached value has
     * aged out. Falls back to the cache when no source is bound or the read throws.
     */
    private fun currentlyEnabled(): Boolean {
        val source = enabledSource ?: return enabled
        val now = clock()
        if (now - lastSampledAt < RESAMPLE_INTERVAL_MS) return enabled
        val fresh = runCatching { source() }.getOrElse {
            lastSampledAt = now
            return enabled
        }
        if (fresh != enabled) setEnabled(fresh) else lastSampledAt = now
        return enabled
    }

    fun setSessionTag(tag: String) {
        sessionTag = tag
    }

    /** Queues one line when enabled. Safe from any thread. */
    fun queueSize(): Int = synchronized(lock) { queue.size }

    fun line(text: String) {
        if (!currentlyEnabled()) return
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
        startDrainer()
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
            if (!currentlyEnabled()) {
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
        if (!currentlyEnabled()) return
        drainOnce()
    }

    private fun drainOnce(): Boolean = synchronized(drainLock) {
        if (!currentlyEnabled()) return false
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
        enabledSource = null
        lastSampledAt = 0L
        clock = { System.currentTimeMillis() }
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
