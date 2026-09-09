package com.jiesa.xvideocatcher.hook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jiesa.xvideocatcher.DiagLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Download progress as a real system notification inside the host process.
 *
 * Toast alone is not progress: a multi-megabyte HLS mux can take tens of seconds with no
 * feedback after "started". X already holds notification permission and a running process,
 * so this uses the platform [NotificationManager] — no module Service, no extra permission
 * declaration, no launcher component.
 *
 * ## Why progress is a per-download object (1.50)
 *
 * Up to 1.49 this class held ONE `activeId`, plus one `lastPercent` / `lastPostedAt` / `retryAction`
 * shared by every download. The downloader runs a two-thread pool, so two taps overlap routinely,
 * and the 12.20.5 device log shows exactly what that costs:
 *
 * ```
 * PROGRESS start id=...257     video, 137 MB, slow
 * PROGRESS start id=...258     photo -- overwrites activeId
 * PROGRESS end   id=...258     photo done, activeId reset to 0
 * saved media=... (137789419 bytes)
 * download finished ok=1 of 1  the video really did finish
 *                              ...and no `PROGRESS end id=...257` ever follows
 * ```
 *
 * The video's completion hit `if (activeId == 0) return` and returned, so its notification kept
 * `setOngoing(true)` and sat in the shade saying "downloading" forever. The file was on disk the
 * whole time — only the notification lied.
 *
 * One mutable field cannot describe N concurrent downloads, so the fix is structural rather than a
 * guard: [begin] returns a [Task] that owns its id and throttle state, and every later call goes
 * through that object. Two downloads can no longer see each other's state, and a completion can no
 * longer be swallowed because a sibling finished first. Genuinely process-wide pieces — the channel,
 * the manager, the retry receiver — stay on the outer object.
 */
internal class DownloadProgress(private val strings: ModuleStrings) {

    private val main = Handler(Looper.getMainLooper())
    private var manager: NotificationManager? = null
    @Volatile private var receiverRegistered = false

    /**
     * Retry actions keyed by notification id, so pressing Retry on one failed download cannot
     * restart whichever download happened to register its action last.
     */
    private val retryActions = ConcurrentHashMap<Int, () -> Unit>()

    /**
     * One download's notification. Created by [begin]; everything a single download needs in order
     * to throttle and to finish lives here rather than on the shared object.
     */
    internal inner class Task(val id: Int) {
        private var lastPostedAt = 0L
        private var lastPercent = -1
        @Volatile private var finished = false

        fun setRetryAction(action: (() -> Unit)?) {
            if (action == null) retryActions.remove(id) else retryActions[id] = action
        }

        /**
         * @param percent 0..100, or null to keep indeterminate / byte-only text
         * @param detail optional second line (bytes / file name)
         */
        fun update(context: Context, label: String, percent: Int?, detail: String? = null) {
            if (finished) return
            val nm = manager ?: notificationManager(context) ?: return
            val now = System.currentTimeMillis()
            val p = percent?.coerceIn(0, 100)
            if (p != null && p == lastPercent && now - lastPostedAt < MIN_INTERVAL_MS) return
            if (p == null && now - lastPostedAt < MIN_INTERVAL_MS) return
            lastPercent = p ?: lastPercent
            lastPostedAt = now
            val n = base(context, label)
                .setContentText(detail ?: label)
                .setProgress(100, p ?: 0, p == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            runCatching { nm.notify(NOTIF_TAG, id, n) }
        }

        fun success(context: Context, message: String) = finish(context, message, ok = true)

        fun failure(context: Context, message: String) = finish(context, message, ok = false)

        /**
         * Clears the ongoing flag and posts the terminal text.
         *
         * Guarded by this task's own [finished] flag, never by a shared "is anything active" field:
         * the whole point of 1.50 is that another download completing must not be able to suppress
         * this one's completion.
         */
        private fun finish(context: Context, message: String, ok: Boolean) {
            val nm = manager ?: notificationManager(context) ?: return
            if (finished) return
            finished = true
            val builder = base(context, message)
                .setContentText(message)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
            if (!ok && retryActions.containsKey(id)) {
                builder.addAction(
                    Notification.Action.Builder(null, "重试", retryPendingIntent(context, id)).build(),
                )
            }
            runCatching { nm.notify(NOTIF_TAG, id, builder.build()) }
            DiagLog.line("PROGRESS end id=$id ok=$ok msg=${message.take(60)}")
            // Success is transient. Failure remains visible with a Retry action.
            if (ok) {
                retryActions.remove(id)
                main.postDelayed({ runCatching { nm.cancel(NOTIF_TAG, id) } }, 4_000L)
            }
        }
    }

    /**
     * Starts a notification and returns the handle that owns it.
     *
     * Returns a [Task] even when the notification could not be posted, so callers never have to
     * null-check a progress handle: a download whose progress UI failed must still run and still
     * report its result through the same code path.
     */
    fun begin(context: Context, label: String, indeterminate: Boolean = true): Task {
        val id = IDS.incrementAndGet()
        val task = Task(id)
        val nm = notificationManager(context)
        if (nm == null) {
            DiagLog.line("PROGRESS start id=$id (no NotificationManager; progress UI disabled)")
            return task
        }
        ensureChannel(nm)
        registerRetryReceiver(context)
        val n = base(context, label)
            .setProgress(100, 0, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(NOTIF_TAG, id, n) }
            .onFailure { DiagLog.line("PROGRESS notify start failed: $it") }
        DiagLog.line("PROGRESS start id=$id label=${label.take(40)}")
        return task
    }

    private fun base(context: Context, title: String): Notification.Builder {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return b
            .setContentTitle(title)
            // Framework drawable *on purpose*, unlike the launcher icon fixed in 1.51. This
            // notification is posted from inside the host process, so its small icon is resolved
            // against com.twitter.android's resources -- a module R.drawable id is not in that table
            // and Android would reject the notification outright. Framework ids are the one namespace
            // both processes share. stat_sys_download is also the icon the system status bar expects
            // for an ongoing download, so it themes correctly.
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setCategory(Notification.CATEGORY_PROGRESS)
    }

    private fun notificationManager(context: Context): NotificationManager? {
        val existing = manager
        if (existing != null) return existing
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager = nm
        return nm
    }

    private fun registerRetryReceiver(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_RETRY)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(retryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(retryReceiver, filter)
            }
            receiverRegistered = true
        }.onFailure { DiagLog.line("PROGRESS retry receiver registration failed: $it") }
    }

    /**
     * The id travels in the Intent, and also as the request code so two PendingIntents stay distinct
     * instead of one `FLAG_UPDATE_CURRENT` overwriting the other's extras.
     */
    private fun retryPendingIntent(context: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id,
            Intent(ACTION_RETRY).setPackage(context.packageName).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private val retryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_RETRY) return
            val id = intent.getIntExtra(EXTRA_ID, 0)
            val action = retryActions[id]
            if (action == null) {
                DiagLog.line("PROGRESS retry pressed but no retry action is retained for id=$id")
                return
            }
            DiagLog.line("PROGRESS retry pressed id=$id")
            main.post { action.invoke() }
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "X Video Catcher",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Download progress"
            setShowBadge(false)
        }
        runCatching { nm.createNotificationChannel(ch) }
    }

    private companion object {
        const val CHANNEL_ID = "xvc_download"
        const val NOTIF_TAG = "xvc"
        const val ACTION_RETRY = "com.jiesa.xvideocatcher.action.RETRY_DOWNLOAD"
        const val EXTRA_ID = "xvc_notif_id"
        const val MIN_INTERVAL_MS = 400L

        private val IDS = AtomicInteger(0x58564301)
    }
}
