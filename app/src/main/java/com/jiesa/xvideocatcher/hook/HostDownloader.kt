package com.jiesa.xvideocatcher.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.jiesa.xvideocatcher.DiagLog
import com.jiesa.xvideocatcher.HostLog
import com.jiesa.xvideocatcher.MediaUrls
import com.jiesa.xvideocatcher.DownloadTarget
import com.jiesa.xvideocatcher.HlsVideo
import com.jiesa.xvideocatcher.Http
import com.jiesa.xvideocatcher.MediaSaver
import com.jiesa.xvideocatcher.StatusMedia
import java.util.concurrent.Executors

/**
 * Downloads media from inside the host process.
 *
 * 1.25 primary path: the share sheet hands a **status URL**. Media for that status is
 * resolved via [StatusMedia] (syndication → vxtwitter → fxtwitter) and frozen on
 * [DownloaderState]. Capture fallback is only used when the sheet has **no** status id;
 * a failed status resolve must not download a neighbour from MediaSpy (device 1.24).
 *
 * ## Progress is threaded, not global (1.50)
 *
 * Every path that starts a download calls [DownloadProgress.begin] and then passes the returned
 * `Task` down to whatever finishes it. The pool runs two downloads at a time and 1.49 kept a single
 * shared `activeId` on the progress object, so on the device a fast photo finishing reset the state
 * a slow 137 MB video was still using and that video's notification never left "downloading".
 * Passing the handle explicitly is what makes that class of bug unrepresentable: there is no shared
 * field left for a sibling to overwrite.
 */
internal class HostDownloader(private val strings: ModuleStrings) {

    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val progress = DownloadProgress(strings)

    /**
     * Entry used by the share-sheet tap. Resolves against the frozen status when present.
     */
    fun downloadCaptured(context: Context): Boolean {
        val statusId = DownloaderState.activeTweetId
        val frozen = DownloaderState.frozenResolved
        if (frozen != null && !frozen.isEmpty) {
            return downloadResolved(context, frozen)
        }
        if (!statusId.isNullOrEmpty()) {
            toast(context, strings.startedLabel(context, 1))
            val task = progress.begin(context, strings.progressTitle(context), indeterminate = true)
            task.setRetryAction { retryStatus(context, statusId) }
            // Resolve on the worker so the tap does not block the host main thread.
            pool.execute {
                val resolved = StatusMedia.resolve(statusId)
                if (resolved == null || resolved.isEmpty) {
                    // 1.25: never fall back to MediaSpy when we have a status id.
                    // Device 1.24: tombstone → capture saved a neighbour photo for a video tap.
                    DiagLog.line("STATUS resolve empty for $statusId; NOT falling back to capture")
                    main.post {
                        val msg = strings.noMediaLabel(context)
                        task.failure(context, msg)
                        toast(context, msg)
                    }
                    return@execute
                }
                DownloaderState.freezeResolved(resolved)
                downloadResolvedOnWorker(context, resolved, task)
            }
            return true
        }
        return downloadCaptureFallback(context, showStarted = true)
    }

    private fun retryStatus(context: Context, statusId: String) {
        DownloaderState.activeTweetId = statusId
        DownloaderState.frozenResolved = null
        toast(context, "正在重试")
        val task = progress.begin(context, strings.progressTitle(context), indeterminate = true)
        task.setRetryAction { retryStatus(context, statusId) }
        pool.execute {
            val resolved = StatusMedia.resolve(statusId)
            if (resolved == null || resolved.isEmpty) {
                main.post {
                    val msg = strings.failureLabel(context)
                    task.failure(context, msg)
                    toast(context, msg)
                }
                return@execute
            }
            DownloaderState.freezeResolved(resolved)
            downloadResolvedOnWorker(context, resolved, task)
        }
    }

    private fun downloadResolved(context: Context, resolved: StatusMedia.Resolved): Boolean {
        toast(context, strings.startedLabel(context, resolved.size.coerceAtLeast(1)))
        val task = progress.begin(context, strings.progressTitle(context), indeterminate = true)
        task.setRetryAction { retryStatus(context, resolved.statusId) }
        pool.execute { downloadResolvedOnWorker(context, resolved, task) }
        return true
    }

    private fun downloadResolvedOnWorker(
        context: Context,
        resolved: StatusMedia.Resolved,
        task: DownloadProgress.Task,
    ) {
        val total = resolved.size.coerceAtLeast(1)
        var success = 0
        var index = 0
        DiagLog.line(
            "download starting from status=${resolved.statusId} " +
                "photos=${resolved.photos.size} videos=${resolved.videos.size}",
        )

        val photosToDownload = resolved.photos
        DiagLog.line("  downloading all ${photosToDownload.size} photo(s)")
        for (photoUrl in photosToDownload) {
            index++
            val url = MediaUrls.highestQualityPhoto(photoUrl)
            val spec = DownloadTarget.photoSpec(url) ?: continue
            val at = index
            main.post {
                task.update(
                    context,
                    strings.progressTitle(context),
                    ((at - 1) * 100) / total,
                    strings.progressDetail(context, at, total) + " · ${spec.fileName}",
                )
            }
            DiagLog.line("  fetching photo: ${spec.fileName} <- $url")
            val result = saveWithRetry(context, spec, url)
            when (result) {
                is MediaSaver.Result.Saved -> {
                    success++
                    DiagLog.line("  saved ${spec.fileName} (${result.bytes} bytes)")
                }
                is MediaSaver.Result.AlreadyExists -> {
                    success++
                    DiagLog.line("  already on disk: ${spec.fileName}")
                }
                is MediaSaver.Result.Failed ->
                    DiagLog.line("  FAILED ${spec.fileName}: ${result.reason}")
            }
        }

        for (video in resolved.videos) {
            index++
            val url = video.downloadUrl
            if (url == null) {
                DiagLog.line("  FAILED media=${video.mediaId}: no progressive or master URL")
                continue
            }
            val at = index
            main.post {
                task.update(
                    context,
                    strings.progressTitle(context),
                    ((at - 1) * 100) / total,
                    strings.progressDetail(context, at, total) + " · ${video.mediaId}",
                )
            }
            val result = saveVideoWithRetry(context, video, url, task)
            when (result) {
                is MediaSaver.Result.Saved -> {
                    success++
                    DiagLog.line("  saved media=${video.mediaId} (${result.bytes} bytes)")
                }
                is MediaSaver.Result.AlreadyExists -> {
                    success++
                    DiagLog.line("  already on disk: media=${video.mediaId}")
                }
                is MediaSaver.Result.Failed -> {
                    DiagLog.line("  FAILED media=${video.mediaId}: ${result.reason}")
                    HostLog.log("status=${resolved.statusId} media=${video.mediaId} failed: ${result.reason}")
                }
            }
        }

        DiagLog.line("download finished status=${resolved.statusId}: ok=$success of $total")
        DiagLog.flushNow()
        main.post {
            if (success > 0) {
                val msg = strings.successLabel(context, success)
                task.success(context, msg)
                toast(context, msg)
            } else {
                val msg = strings.failureLabel(context)
                task.failure(context, msg)
                toast(context, msg)
            }
        }
    }

    private fun saveProgressive(
        context: Context,
        video: StatusMedia.Video,
        url: String,
        task: DownloadProgress.Task,
    ): MediaSaver.Result {
        val (w, h) = if (video.width > 0 && video.height > 0) {
            video.width to video.height
        } else {
            MediaUrls.resolution(url) ?: (0 to 0)
        }
        val spec = DownloadTarget.videoSpec(video.mediaId, w, h)
        DiagLog.line("  fetching progressive: ${spec.fileName} <- $url")
        return runCatching {
            MediaSaver.save(context, spec) { out ->
                Http.copyTo(url, out) { done ->
                    main.post {
                        task.update(
                            context,
                            strings.progressTitle(context),
                            null,
                            formatBytes(done) + " · ${spec.fileName}",
                        )
                    }
                }
            }
        }.getOrElse { MediaSaver.Result.Failed(it.message ?: it.javaClass.simpleName, it) }
    }

    private fun saveHls(
        context: Context,
        mediaId: String,
        masterUrl: String,
        task: DownloadProgress.Task,
    ): MediaSaver.Result {
        val plan = HlsVideo.plan(masterUrl)
            ?: return MediaSaver.Result.Failed("master playlist unusable")
        val v = plan.variant
        val spec = DownloadTarget.videoSpec(mediaId, v.width, v.height)
        DiagLog.line(
            "  fetching HLS: ${spec.fileName} variant ${v.width}x${v.height} <- $masterUrl",
        )
        val workDir = context.cacheDir
        return runCatching {
            MediaSaver.save(context, spec) { out ->
                HlsVideo.saveTo(plan, workDir, out) { done, totalHint ->
                    val pct = if (totalHint > 0) {
                        ((done * 100) / totalHint).toInt().coerceIn(0, 99)
                    } else null
                    main.post {
                        task.update(
                            context,
                            strings.progressTitle(context),
                            pct,
                            formatBytes(done) + " · ${spec.fileName}",
                        )
                    }
                }
            }
        }.getOrElse { MediaSaver.Result.Failed(it.message ?: it.javaClass.simpleName, it) }
    }

    private fun saveWithRetry(
        context: Context,
        spec: DownloadTarget.Spec,
        url: String,
    ): MediaSaver.Result = runCatching {
        // Http already retries each transient request up to three times. Retrying the
        // whole save here would restart a large transfer from byte zero and can turn one
        // failed segment into hours of repeated work.
        MediaSaver.save(context, spec) { out ->
            val data = Http.bytes(url)
            out.write(data)
            data.size.toLong()
        }
    }.getOrElse { MediaSaver.Result.Failed(it.message ?: it.javaClass.simpleName, it) }

    private fun saveVideoWithRetry(
        context: Context,
        video: StatusMedia.Video,
        url: String,
        task: DownloadProgress.Task,
    ): MediaSaver.Result = if (MediaUrls.isMasterPlaylist(url) || url.contains(".m3u8")) {
        // HLS downloads many segments and Http retries each one. Never restart the
        // entire video automatically; leave a visible Retry action on final failure.
        saveHls(context, video.mediaId, url, task)
    } else {
        saveProgressive(context, video, url, task)
    }

    /** Pre-1.24 path: download whatever capture freeze / MediaSpy.best offers. */
    private fun downloadCaptureFallback(context: Context, showStarted: Boolean): Boolean {
        val hits = DownloaderState.targetHits(DownloaderState.activeTweetId)
        val hit = hits.firstOrNull()
        if (hit == null) {
            DiagLog.line("download requested but no downloadable capture (photo or master)")
            if (showStarted) toast(context, strings.noMediaLabel(context))
            return false
        }
        if (hit.kind == MediaSpy.Kind.PHOTO) {
            DiagLog.line(
                "download starting from capture: photo keys=" +
                    hits.mapNotNull { MediaUrls.photoKey(it.url) }.distinct().joinToString() +
                    " n=${hits.size}",
            )
            val n = hits.size.coerceAtLeast(1)
            if (showStarted) toast(context, strings.startedLabel(context, n))
            val task = progress.begin(context, strings.progressTitle(context), indeterminate = true)
            // If already on pool thread (status miss path), run inline; else submit.
            val work: () -> Unit = {
                var successCount = 0
                var index = 0
                for (photoHit in hits) {
                    index++
                    val url = MediaUrls.highestQualityPhoto(photoHit.url)
                    val spec = DownloadTarget.photoSpec(url) ?: continue
                    val at = index
                    main.post {
                        task.update(
                            context,
                            strings.progressTitle(context),
                            ((at - 1) * 100) / n,
                            strings.progressDetail(context, at, n) + " · ${spec.fileName}",
                        )
                    }
                    val result = runCatching {
                        MediaSaver.save(context, spec) { out ->
                            val data = Http.bytes(url)
                            out.write(data)
                            data.size.toLong()
                        }
                    }.getOrElse { MediaSaver.Result.Failed(it.message ?: it.javaClass.simpleName, it) }
                    when (result) {
                        is MediaSaver.Result.Saved, is MediaSaver.Result.AlreadyExists -> successCount++
                        is MediaSaver.Result.Failed ->
                            DiagLog.line("  FAILED ${spec.fileName}: ${result.reason}")
                    }
                    if (result is MediaSaver.Result.Saved) {
                        DiagLog.line("  saved ${spec.fileName} (${result.bytes} bytes)")
                    } else if (result is MediaSaver.Result.AlreadyExists) {
                        DiagLog.line("  already on disk: ${spec.fileName}")
                    }
                }
                DiagLog.flushNow()
                main.post {
                    if (successCount > 0) {
                        val msg = strings.successLabel(context, successCount)
                        task.success(context, msg)
                        toast(context, msg)
                    } else {
                        val msg = strings.failureLabel(context)
                        task.failure(context, msg)
                        toast(context, msg)
                    }
                }
            }
            pool.execute(work)
            return true
        }

        val mediaId = MediaUrls.mediaId(hit.url)
        if (mediaId == null) {
            DiagLog.line("captured master has no media id: ${hit.url}")
            if (showStarted) toast(context, strings.noMediaLabel(context))
            return false
        }
        DiagLog.line("download starting from capture: media=$mediaId <- ${hit.url}")
        if (showStarted) toast(context, strings.startedLabel(context, 1))
        val task = progress.begin(context, strings.progressTitle(context), indeterminate = true)
        val work: () -> Unit = {
            val result = saveHls(context, mediaId, hit.url, task)
            val ok = result !is MediaSaver.Result.Failed
            when (result) {
                is MediaSaver.Result.Saved ->
                    DiagLog.line("  saved media=$mediaId (${result.bytes} bytes)")
                is MediaSaver.Result.AlreadyExists ->
                    DiagLog.line("  already on disk: media=$mediaId")
                is MediaSaver.Result.Failed -> {
                    DiagLog.line("  FAILED media=$mediaId: ${result.reason}")
                    HostLog.log("${hit.url} failed: ${result.reason}")
                }
            }
            DiagLog.flushNow()
            main.post {
                if (ok) {
                    val msg = strings.successLabel(context, 1)
                    task.success(context, msg)
                    toast(context, msg)
                } else {
                    val msg = strings.failureLabel(context)
                    task.failure(context, msg)
                    toast(context, msg)
                }
            }
        }
        pool.execute(work)
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
        toast(context, strings.startedLabel(context, items.size))
        val task = progress.begin(context, strings.progressTitle(context), indeterminate = true)
        pool.execute {
            var saved = 0
            var duplicate = 0
            for (item in items) {
                when (val result = runCatching {
                    MediaSaver.save(context, item.spec) { out -> Http.copyTo(item.url, out) }
                }.getOrElse { MediaSaver.Result.Failed(it.message ?: it.javaClass.simpleName, it) }) {
                    is MediaSaver.Result.Saved -> saved++
                    is MediaSaver.Result.AlreadyExists -> duplicate++
                    is MediaSaver.Result.Failed ->
                        DiagLog.line("  FAILED ${item.spec.fileName}: ${result.reason}")
                }
            }
            val done = saved + duplicate
            DiagLog.flushNow()
            main.post {
                if (done > 0) {
                    val msg = strings.successLabel(context, done)
                    task.success(context, msg)
                    toast(context, msg)
                } else {
                    val msg = strings.failureLabel(context)
                    task.failure(context, msg)
                    toast(context, msg)
                }
            }
        }
    }

    private fun toast(context: Context, text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    private fun formatBytes(n: Long): String {
        if (n < 1024) return "$n B"
        if (n < 1024 * 1024) return "${n / 1024} KB"
        return "%.1f MB".format(n / (1024.0 * 1024.0))
    }
}
