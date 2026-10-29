package com.jiesa.xvideocatcher

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Writes a finished download into the user's shared storage.
 *
 * Destination is [Environment.DIRECTORY_MOVIES]/XVideoCatcher (videos) or
 * [Environment.DIRECTORY_PICTURES]/XVideoCatcher (photos) via MediaStore, so the file shows
 * up in every gallery app without a manual rescan: MediaStore rows under Movies/Pictures are
 * what gallery apps actually query. Writing under Download/ (the 1.60-and-earlier path) left
 * files invisible in galleries — the 10-29 "did it even save?" report is exactly that.
 *
 * After the write, [MediaScannerConnection.scanFile] runs once on the saved path. The row was
 * created by this process so it is already scanned in principle, but OEM providers have been
 * observed dropping the volume rescan when IS_PENDING clears; the explicit scan is one binder
 * call per download and makes "saved but never appears" unrepresentable.
 *
 * Timestamps are **download time**:
 * 1. MediaStore `date_added` / `date_modified` / `datetaken`
 * 2. For video: MP4 `mvhd`/`tkhd`/`mdhd` creation+modification (OEM galleries read these)
 *
 * 1.26 only did (1); device still showed old dates on video. 1.28 does both.
 */
object MediaSaver {

    private const val SUBDIR = "XVideoCatcher"

    const val COL_DATE_ADDED = "date_added"
    const val COL_DATE_MODIFIED = "date_modified"
    const val COL_DATE_TAKEN = "datetaken"

    sealed interface Result {
        data class Saved(val uri: String, val bytes: Long) : Result
        data class AlreadyExists(val uri: String) : Result
        data class Failed(val reason: String, val cause: Throwable? = null) : Result
    }

    fun stampMap(nowMillis: Long = System.currentTimeMillis()): Map<String, Long> {
        val seconds = nowMillis / 1000L
        return mapOf(
            COL_DATE_ADDED to seconds,
            COL_DATE_MODIFIED to seconds,
            COL_DATE_TAKEN to nowMillis,
        )
    }

    /**
     * MediaStore RELATIVE_PATH for a kind, with the trailing slash the provider expects.
     *
     * Videos go to Movies/XVideoCatcher, photos to Pictures/XVideoCatcher — the two directories
     * gallery apps index by default. The 1.61 diag logs proved the save path itself was fine
     * (26.8 MB written, `saved media=…`), yet the user saw nothing: anything under Download/ is
     * invisible to galleries. This mapping is the fix, so it is pinned by test.
     *
     * Literal strings, not Environment.DIRECTORY_*: those constants are just "Movies"/"Pictures"
     * on every API level this module supports, and the JVM-side android.jar stubs initialise
     * them to null, which would silently build a "null/XVideoCatcher" path under unit tests.
     */
    fun relativeDir(kind: DownloadTarget.Kind): String = when (kind) {
        DownloadTarget.Kind.VIDEO -> "Movies/$SUBDIR/"
        DownloadTarget.Kind.PHOTO -> "Pictures/$SUBDIR/"
    }

    fun stampValues(nowMillis: Long = System.currentTimeMillis()): ContentValues {
        val values = ContentValues()
        for ((k, v) in stampMap(nowMillis)) values.put(k, v)
        return values
    }

    fun save(
        context: Context,
        spec: DownloadTarget.Spec,
        body: (OutputStream) -> Long,
    ): Result {
        existing(context, spec)?.let { return Result.AlreadyExists(it) }

        val collection = when (spec.kind) {
            DownloadTarget.Kind.VIDEO ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
            DownloadTarget.Kind.PHOTO ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
        }

        val relative = relativeDir(spec.kind)

        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, spec.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
            putAll(stampValues(now))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else if (spec.kind == DownloadTarget.Kind.VIDEO) {
                // Pre-Q: absolute path column instead of RELATIVE_PATH.
                put(MediaStore.Video.Media.DATA, "/sdcard/Movies/$SUBDIR/${spec.fileName}")
            }
        }

        val resolver = context.contentResolver
        val uri = try {
            resolver.insert(collection, values)
        } catch (t: Throwable) {
            return Result.Failed("MediaStore insert threw", t)
        } ?: return Result.Failed("MediaStore insert returned null")

        return try {
            val written = if (spec.kind == DownloadTarget.Kind.VIDEO) {
                writeVideoStamped(context, uri, now, body)
            } else {
                resolver.openOutputStream(uri)?.use(body)
                    ?: return Result.Failed("openOutputStream returned null").also {
                        runCatching { resolver.delete(uri, null, null) }
                    }
            }
            if (written <= 0L) {
                runCatching { resolver.delete(uri, null, null) }
                return Result.Failed("download produced 0 bytes")
            }
            val publish = stampValues(System.currentTimeMillis()).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
            }
            resolver.update(uri, publish, null, null)
            // One more stamp pass after publish: some providers re-probe the file and
            // rewrite MediaStore dates from container metadata at IS_PENDING clear.
            runCatching {
                resolver.update(uri, stampValues(System.currentTimeMillis()), null, null)
            }
            requestScan(context, uri.toString())
            Result.Saved(uri.toString(), written)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            Result.Failed("write failed: ${t.javaClass.simpleName}", t)
        }
    }

    /**
     * Stage video to a cache file, rewrite MP4 date boxes to [nowMillis], then copy into
     * the pending MediaStore stream. Photos skip this — EXIF is less often the sort key
     * on the OEMs Jay uses, and image bytes from pbs rarely carry misleading times.
     */
    private fun writeVideoStamped(
        context: Context,
        uri: android.net.Uri,
        nowMillis: Long,
        body: (OutputStream) -> Long,
    ): Long {
        val cache = File(context.cacheDir, "xvc_stamp_${System.nanoTime()}.mp4")
        try {
            val n = FileOutputStream(cache).use(body)
            if (n <= 0L) return n
            val boxes = Mp4DateStamp.stampFile(cache, nowMillis / 1000L)
            DiagLog.line("  mp4 date stamp boxes=$boxes file=${cache.length()}")
            val out = context.contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("openOutputStream returned null")
            out.use { sink ->
                FileInputStream(cache).use { src -> src.copyTo(sink) }
            }
            return cache.length()
        } finally {
            cache.delete()
        }
    }

    /**
     * Explicit MediaScanner pass on the saved row.
     *
     * The row is created by this process with IS_PENDING, so clearing the flag should publish it —
     * but OEM providers have been observed skipping the volume rescan at IS_PENDING clear, which
     * leaves the file on disk yet invisible in galleries. One scanFile call closes that gap; never
     * fatal, so failures are logged and swallowed.
     */
    private fun requestScan(context: Context, uri: String) {
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(uri), null) { _, _ -> }
            DiagLog.line("  mediastore scan requested: $uri")
        }.onFailure {
            DiagLog.line("  mediastore scan request failed: ${it.javaClass.simpleName}")
        }
    }

    private fun existing(context: Context, spec: DownloadTarget.Spec): String? = runCatching {
        val collection = when (spec.kind) {
            DownloadTarget.Kind.VIDEO ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
            DownloadTarget.Kind.PHOTO ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
        }
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(spec.fileName),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                android.net.Uri.withAppendedPath(collection, id.toString()).toString()
            } else {
                null
            }
        }
    }.getOrNull()
}
