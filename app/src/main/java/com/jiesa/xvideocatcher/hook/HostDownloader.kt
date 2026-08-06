package com.jiesa.xvideocatcher.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.jiesa.xvideocatcher.DiagLog
import com.jiesa.xvideocatcher.MediaUrls
import com.jiesa.xvideocatcher.DownloadTarget
import com.jiesa.xvideocatcher.HlsVideo
import com.jiesa.xvideocatcher.Http
import com.jiesa.xvideocatcher.MediaSaver
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.Executors

/**
 * Downloads the media of a tweet from inside the host process.
 *
 * Running here rather than in a service of our own is the whole point of the module form: X
 * already holds `INTERNET`, already has a process, and the tweet object is already in memory.
 * Adding a component of our own would mean a second process, a second permission set, and an
 * app-switch the user did not ask for.
 *
 * Threading: transfers run on a small pool, never on the host's main thread — a sheet click that
 * blocks would freeze X's UI and trip its own ANR watchdog. Toasts are posted back to the main
 * looper because that is a hard Android requirement.
 */
internal class HostDownloader(private val strings: ModuleStrings) {

    // Two threads: a tweet holds at most four media items, and X is a foreground app whose
    // bandwidth this shares. More parallelism would slow the visible download, not speed it.
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /**
     * Downloads a URL captured by [MediaSpy], for the path where no tweet object is available.
     *
     * The live share sheet (`com.x.share.impl`) is handed a status URL rather than a tweet, so
     * [download] cannot serve it -- that is what 1.11's device log proved. The player has already
     * resolved a playable URL by then, so this takes the URL directly and reuses the same naming and
     * saving path as the tweet route: [DownloadTarget] for the name, [MediaSaver] for the write.
     *
     * Returns false when the capture holds nothing usable, so the caller can tell the user rather
     * than silently doing nothing.
     */
    fun downloadCaptured(context: Context): Boolean {
        val hit = MediaSpy.best()
        if (hit == null) {
            DiagLog.line("download requested but no master playlist captured from the player")
            toast(context, strings.noMediaLabel(context))
            return false
        }

        val mediaId = MediaUrls.mediaId(hit.url)
        if (mediaId == null) {
            DiagLog.line("captured master has no media id: ${hit.url}")
            DiagLog.flushNow()
            toast(context, strings.noMediaLabel(context))
            return false
        }

        DiagLog.line("download starting from capture: media=$mediaId <- ${hit.url}")
        toast(context, strings.startedLabel(context, 1))
        pool.execute {
            // Planning is a network fetch of the master, so it runs off the main thread with the
            // transfer rather than before the toast.
            val plan = HlsVideo.plan(hit.url)
            if (plan == null) {
                DiagLog.line("  FAILED $mediaId: master playlist unusable")
                DiagLog.flushNow()
                main.post { toast(context, strings.failureLabel(context)) }
                return@execute
            }
            val v = plan.variant
            DiagLog.line(
                "  variant ${v.width}x${v.height} bw=${v.bandwidth} audio=${plan.audio?.groupId ?: "none"}",
            )
            val spec = DownloadTarget.videoSpec(mediaId, v.width, v.height)
            val workDir = context.cacheDir
            val result = runCatching {
                MediaSaver.save(context, spec) { out -> HlsVideo.saveTo(plan, workDir, out) }
            }.getOrElse { MediaSaver.Result.Failed(it.message ?: it.javaClass.simpleName, it) }

            val ok = result !is MediaSaver.Result.Failed
            when (result) {
                is MediaSaver.Result.Saved ->
                    DiagLog.line("  saved ${spec.fileName} (${result.bytes} bytes)")
                is MediaSaver.Result.AlreadyExists ->
                    DiagLog.line("  already on disk: ${spec.fileName}")
                is MediaSaver.Result.Failed -> {
                    DiagLog.line("  FAILED ${spec.fileName}: ${result.reason}")
                    XposedBridge.log("XVC: ${hit.url} failed: ${result.reason}")
                }
            }
            DiagLog.flushNow()
            main.post {
                if (ok) toast(context, strings.successLabel(context, 1))
                else toast(context, strings.failureLabel(context))
            }
        }
        return true
    }

    fun download(context: Context, tweet: Any) {
        val items = TweetMedia.extract(tweet)
        if (items.isEmpty()) {
            DiagLog.line("download requested but no media resolved")
            toast(context, strings.noMediaLabel(context))
            return
        }

        DiagLog.line("download starting: ${items.size} item(s)")
        for (item in items) DiagLog.line("  target ${item.spec.fileName} <- ${item.url}")
        toast(context, strings.startedLabel(context, items.size))

        pool.execute {
            var saved = 0
            var duplicate = 0
            for (item in items) {
                when (val result = saveOne(context, item)) {
                    is MediaSaver.Result.Saved -> {
                        saved++
                        DiagLog.line("  saved ${item.spec.fileName}")
                    }
                    is MediaSaver.Result.AlreadyExists -> {
                        duplicate++
                        DiagLog.line("  already on disk: ${item.spec.fileName}")
                    }
                    is MediaSaver.Result.Failed -> {
                        DiagLog.line("  FAILED ${item.spec.fileName}: ${result.reason}")
                        XposedBridge.log("XVC: ${item.url} failed: ${result.reason}")
                    }
                }
            }
            // A tweet whose media is already on disk is a success from the user's point of view;
            // reporting "failed" for it would send them looking for a problem that is not there.
            val done = saved + duplicate
            DiagLog.line("download finished: saved=$saved duplicate=$duplicate of ${items.size}")
            DiagLog.flushNow()
            main.post {
                if (done > 0) toast(context, strings.successLabel(context, done))
                else toast(context, strings.failureLabel(context))
            }
        }
    }

    /** Streams one item into shared storage via [MediaSaver], which brackets the write. */
    private fun saveOne(context: Context, item: HostMedia): MediaSaver.Result =
        runCatching {
            MediaSaver.save(context, item.spec) { out -> Http.copyTo(item.url, out) }
        }.getOrElse { MediaSaver.Result.Failed(it.message ?: it.javaClass.simpleName, it) }

    private fun toast(context: Context, text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
}
