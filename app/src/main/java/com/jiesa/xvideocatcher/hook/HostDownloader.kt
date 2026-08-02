package com.jiesa.xvideocatcher.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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

    fun download(context: Context, tweet: Any) {
        val items = TweetMedia.extract(tweet)
        if (items.isEmpty()) {
            toast(context, strings.noMediaLabel(context))
            return
        }

        toast(context, strings.startedLabel(context, items.size))

        pool.execute {
            var saved = 0
            var duplicate = 0
            for (item in items) {
                when (val result = saveOne(context, item)) {
                    is MediaSaver.Result.Saved -> saved++
                    is MediaSaver.Result.AlreadyExists -> duplicate++
                    is MediaSaver.Result.Failed ->
                        XposedBridge.log("XVC: ${item.url} failed: ${result.reason}")
                }
            }
            // A tweet whose media is already on disk is a success from the user's point of view;
            // reporting "failed" for it would send them looking for a problem that is not there.
            val done = saved + duplicate
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
