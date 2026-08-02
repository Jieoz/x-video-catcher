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

    /**
     * Flattens records into the payload actually written.
     *
     * A record must never span two lines: a raw newline inside one would split it and corrupt any
     * consumer reading this file line-by-line. Separated out so this contract is assertable
     * without going through storage.
     */
    internal fun payloadOf(lines: List<String>): String =
        lines.joinToString(separator = "\n", postfix = "\n") {
            it.replace("\r", " ").replace("\n", " ")
        }

    /** Appends [lines], one per line. Returns false if nothing could be written. */
    fun append(context: Context, lines: List<String>): Boolean {
        if (lines.isEmpty()) return true
        val payload = payloadOf(lines)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendViaMediaStore(context, payload)
        } else {
            appendViaFile(payload)
        }
    }

    /**
     * The direct-file branch, exposed for tests.
     *
     * Robolectric cannot back `MediaStore.Downloads` - `ShadowMediaStore` covers only thumbnail and
     * cloud-media surfaces, so `insert()` returns null there regardless of how correct this code is,
     * and asserting on that branch fails permanently while proving nothing. Robolectric *does* back
     * external storage with a real temp dir, so the file branch gives byte-level assertions.
     * MediaStore correctness is established on-device by the log appearing in Download/.
     */
    internal fun appendDirectForTest(dir: File, lines: List<String>): Boolean {
        if (lines.isEmpty()) return true
        return appendInto(dir, payloadOf(lines))
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
    private fun appendViaFile(payload: String): Boolean {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DIR_NAME,
        )
        return appendInto(dir, payload)
    }

    /**
     * Appends [payload] into [dir], creating it if needed.
     *
     * Takes the directory as a parameter so this is testable with a real temp dir and no Android
     * framework at all. Robolectric would be the conventional choice, but it cannot back
     * `MediaStore.Downloads` (so the interesting branch is unassertable there anyway) and it pulls
     * in a conscrypt native library with no aarch64 build, which makes the suite unrunnable on an
     * arm64 machine. Plain JUnit over real bytes tests more and depends on less.
     */
    internal fun appendInto(dir: File, payload: String): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        File(dir, fileName()).appendText(payload)
        true
    }.getOrDefault(false)
}
