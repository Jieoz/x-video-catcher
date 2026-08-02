package com.jiesa.xvideocatcher

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the diagnostic log into shared storage, from inside the host process.
 *
 * The host app writes the file itself. That is the whole design constraint, and it is not a style
 * choice: this code runs under X's UID, so it cannot write into the module's own storage, and
 * anything it writes into X's private dirs needs root to retrieve.
 *
 * The obvious alternative - route records to a `ContentProvider` in the module app - cannot work
 * and was shipped broken once. Since Android 11 a process can only resolve a `content://`
 * authority it declares in its own manifest `<queries>`, and X's manifest is not ours to edit. So
 * `insert()` never resolves, and because every sink call must be wrapped (never crash the host) it
 * fails *silently*: zero records, no error. Declaring `<queries>` in the module's manifest is the
 * fix that feels right and grants visibility in the opposite direction. CI now fails the build if
 * the APK declares any provider authority, so that design cannot regress in.
 *
 * Because the writer owns the file, no storage permission is involved on API 29+.
 */
internal object DiagSink {

    const val DIR_NAME = "XVideoCatcher"
    private const val MIME = "text/plain"

    fun fileName(now: Date = Date()): String =
        "xvc-diag-${SimpleDateFormat("yyyyMMdd", Locale.US).format(now)}.log"

    /**
     * The path shown to the user. Must equal where [append] actually writes: drift here sends
     * them to the wrong folder, where they correctly report "no log" on a working build.
     */
    fun displayPath(now: Date = Date()): String = "Download/$DIR_NAME/${fileName(now)}"

    /** Appends [lines], one per line. Returns false if nothing could be written. */
    fun append(context: Context, lines: List<String>): Boolean {
        if (lines.isEmpty()) return true
        // A record must never span two lines - a raw newline inside one would split it and
        // corrupt every consumer that reads this line-by-line.
        val payload = lines.joinToString(separator = "\n", postfix = "\n") {
            it.replace("\r", " ").replace("\n", " ")
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendViaMediaStore(context, payload)
        } else {
            appendViaFile(payload)
        }
    }

    private fun appendViaMediaStore(context: Context, payload: String): Boolean = runCatching {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = fileName()
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME/"

        // Look the row up every time rather than caching the Uri. A cached one goes stale when
        // the host process restarts, silently sending the rest of the day's records nowhere.
        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relative, name),
            null,
        )?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
        }

        val uri = existing ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            },
        ) ?: return false

        // MediaStore has no append mode; "wa" on an existing row is the append path.
        resolver.openOutputStream(uri, "wa")?.use { it.write(payload.toByteArray()) } ?: return false
        true
    }.getOrDefault(false)

    /** Pre-scoped-storage path. The host holds the storage permission on these versions. */
    private fun appendViaFile(payload: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DIR_NAME,
        )
        if (!dir.isDirectory && !dir.mkdirs()) return false
        File(dir, fileName()).appendText(payload)
        true
    }.getOrDefault(false)
}
