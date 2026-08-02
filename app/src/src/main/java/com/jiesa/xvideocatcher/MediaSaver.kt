package com.jiesa.xvideocatcher

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream

/**
 * Writes a finished download into the user's shared storage.
 *
 * The destination is MediaStore rather than app-private storage so the file lands in the
 * user's gallery, which is the whole point of saving it. MediaStore also makes the writing
 * process the owner, so no runtime storage permission is needed on API 29+.
 *
 * The same lesson applies in reverse and is why photos and videos go to `Pictures/` and
 * `Movies/` instead of `Downloads/`: a non-media file in Downloads is only visible to its
 * creator, so a video saved there would be invisible to the gallery — the user would be
 * told the download succeeded and find nothing.
 */
object MediaSaver {

    private const val SUBDIR = "XVideoCatcher"

    sealed interface Result {
        data class Saved(val uri: String, val bytes: Long) : Result
        /** The same media id/key is already present, so nothing was written. */
        data class AlreadyExists(val uri: String) : Result
        data class Failed(val reason: String, val cause: Throwable? = null) : Result
    }

    /**
     * Streams [body] into shared storage under [spec]'s name.
     *
     * The payload is a lambda rather than a byte array on purpose: a 1080p video is tens of
     * megabytes and materialising it in X's heap before writing risks an OOM in an app that
     * is not ours to destabilise. The writer streams straight to the MediaStore stream.
     *
     * `IS_PENDING` brackets the write so half-written files never appear in the gallery. On
     * failure the pending row is deleted, because a 0-byte entry that looks like a saved
     * video is worse than a visible error.
     */
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

        val relative = when (spec.kind) {
            DownloadTarget.Kind.VIDEO -> "${Environment.DIRECTORY_MOVIES}/$SUBDIR"
            DownloadTarget.Kind.PHOTO -> "${Environment.DIRECTORY_PICTURES}/$SUBDIR"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, spec.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = try {
            resolver.insert(collection, values)
        } catch (t: Throwable) {
            return Result.Failed("MediaStore insert threw", t)
        } ?: return Result.Failed("MediaStore insert returned null")

        return try {
            val written = resolver.openOutputStream(uri)?.use(body)
                ?: return Result.Failed("openOutputStream returned null").also {
                    runCatching { resolver.delete(uri, null, null) }
                }
            if (written <= 0L) {
                runCatching { resolver.delete(uri, null, null) }
                return Result.Failed("download produced 0 bytes")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            Result.Saved(uri.toString(), written)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            Result.Failed("write failed: ${t.javaClass.simpleName}", t)
        }
    }

    /**
     * Looks for a previous save of the same media.
     *
     * Matching is on DISPLAY_NAME, which carries the CDN identity ([DownloadTarget]), so a
     * re-tap on a video already saved is reported as a duplicate instead of writing
     * `x_123 (1).mp4`. Any failure to query is treated as "not present": a false duplicate
     * would refuse a download the user asked for, which is the worse error of the two.
     */
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
