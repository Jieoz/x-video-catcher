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

    /** Must be the extension MediaStore maps [MIME] to. See [fileName]. */
    private const val EXT = ".txt"

    /**
     * The extension has to agree with [MIME], or MediaStore silently renames the row.
     *
     * This was `.log` against `text/plain`. MediaStore does not accept a name whose extension
     * contradicts the declared type: it appends its own, storing `xvc-diag-20260803.log.txt`. So
     * [Rows.find] queried `DISPLAY_NAME='xvc-diag-20260803.log'`, never matched the row it had just
     * created, and fell through to `create()` on every single flush -- 32 numbered files for one
     * session, each holding a fragment, and the user was told to open a path that did not exist.
     *
     * Keeping both facing the same constant is what prevents the pair drifting apart again.
     */
    fun fileName(now: Date = Date()): String =
        "xvc-diag-${SimpleDateFormat("yyyyMMdd", Locale.US).format(now)}$EXT"

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

    /**
     * The storage operations the append algorithm needs, so that algorithm can be tested.
     *
     * MediaStore is not fakeable in a JVM test — Robolectric has no provider behind
     * `MediaStore.Downloads` — and the bug that shipped was in the *ordering* of these three
     * operations, not in the Android calls themselves. Naming them as an interface puts the ordering
     * under test while [MediaStoreRows] stays a thin adapter with no logic to get wrong.
     */
    internal interface Rows {
        /** Row id for an existing file, or null. */
        fun find(name: String, relativePath: String): String?

        /** Creates a row and returns its id, or null. */
        fun create(name: String, relativePath: String): String?

        /** Appends to an existing row. */
        fun write(rowId: String, payload: String): Boolean
    }

    /**
     * Serialises the whole find-or-create-then-write sequence.
     *
     * The bug this fixes, from a user's device: the diagnostic log arrived as *two* files,
     * `xvc-diag-20260803.log` and `xvc-diag-20260803.log (1)`, with one session split between them.
     * The attach-time `flushNow()` on the host's main thread raced the drainer thread; both found no
     * existing row, both inserted, and MediaStore de-duplicated by suffixing the display name. The
     * log looked truncated at exactly the moment it was being read to diagnose something else.
     *
     * The lock has to span find *and* create — locking only the write would leave the race intact,
     * because the race is in the lookup. It is process-wide because this object is the only writer,
     * and uncontended in the steady state: one draining thread plus a few flushes per session.
     */
    private val writeLock = Any()

    /** Appends [lines], one per line. Returns false if nothing could be written. */
    fun append(context: Context, lines: List<String>): Boolean {
        if (lines.isEmpty()) return true
        val payload = payloadOf(lines)
        return appendEverywhere(context, payload)
    }

    /**
     * Writes [payload] through every channel available to the host process, and reports whether
     * *any* of them took it.
     *
     * Each channel is attempted independently and on purpose. There is no single path that works
     * across the versions this module has to run on, and the previous builds picked one, failed
     * silently, and left zero observability — which is exactly the state that made the last several
     * releases undiagnosable:
     *
     *  - [appendViaFile] writes `Download/XVideoCatcher/` directly. Only legal for a host with
     *    legacy external storage; on API 29+ scoped storage this is EACCES.
     *  - [MediaStoreRows] is the scoped-storage path. It works, but MediaStore enforces *ownership*:
     *    the host can only reopen rows it created itself, so a row the module app inserted (the
     *    `diag_enabled` flag is one) is not writable from here.
     *  - [appendViaAppExternal] writes the host's own `getExternalFilesDir`. Always writable, never
     *    permission-gated, and the reason the log can no longer come back empty.
     *
     * The last channel is the guarantee. The first two are kept because they land the file where the
     * user can actually reach it without a rooted file manager.
     */
    private fun appendEverywhere(context: Context, payload: String): Boolean {
        // FIRST MATCH WINS, and that is the fix for the 31-fragment log.
        //
        // Every channel below targets the same file name. Running them all on every flush had two
        // writers pointed at `Download/XVideoCatcher/xvc-diag-<date>.txt` at once: the direct file
        // write created the file on disk, and MediaStore — which cannot see or own that unscanned
        // file — resolved `find()` to nothing and called `create()` instead, on every flush. Because
        // MediaStore refuses to reuse a display name that already exists on disk, it suffixed each
        // one: `xvc-diag-20260828 (1).txt` … `(31).txt`. The 20260828 report is exactly that, 31
        // fragments of a 318-line session, and it is why the log looked "very messy".
        //
        // One session must produce one file, so exactly one channel writes and the rest are
        // fallbacks used only when it fails.
        val channels = buildList<Pair<String, () -> Boolean>> {
            add("download-file" to { synchronized(writeLock) { appendViaFile(payload) } })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add("mediastore" to { appendTo(MediaStoreRows(context), payload) })
            }
            add("app-external" to { appendViaAppExternal(context, payload) })
        }

        for ((name, write) in channels) {
            val result = runCatching { write() }
            if (result.getOrDefault(false)) {
                if (name != activeChannel) {
                    HostLog.log("DiagSink: writing via $name (${payload.length} bytes)")
                    activeChannel = name
                }
                return true
            }
            HostLog.log(
                "DiagSink: $name channel failed: " +
                    (result.exceptionOrNull()
                        ?.let { it.javaClass.simpleName + ": " + it.message }
                        ?: "returned false"),
            )
        }
        HostLog.log("DiagSink: every sink refused the payload")
        return false
    }

    /**
     * Channel that last accepted a write, so the choice is logged once instead of per flush.
     *
     * Diagnostic only: the selection above is recomputed every time, because a channel that works at
     * attach time can be revoked later and a cached decision would send the rest of the session
     * nowhere.
     */
    @Volatile
    private var activeChannel: String? = null

    /**
     * The host's own external files dir — `Android/data/<host>/files/XVideoCatcher/`.
     *
     * Unconditionally writable by the host process: no permission, no scoped-storage gate, no
     * MediaStore ownership rule. Its only drawback is that Android 11+ hides `Android/data` from
     * third-party file managers, so it is a fallback for retrieval, not the primary. It exists so
     * that "the log is empty" and "the hook never ran" stop being the same observation.
     */
    private fun appendViaAppExternal(context: Context, payload: String): Boolean = runCatching {
        val base = context.getExternalFilesDir(null) ?: return false
        appendInto(File(base, DIR_NAME), payload)
    }.getOrElse {
        HostLog.log("DiagSink: app-external write failed: $it")
        false
    }

    /** Where [appendViaAppExternal] puts the file, for the log line that tells the user. */
    fun appExternalPath(context: Context): String = runCatching {
        File(File(context.getExternalFilesDir(null), DIR_NAME), fileName()).absolutePath
    }.getOrDefault("(unavailable)")

    /**
     * Find-or-create the row, then append to it. The one place this ordering exists.
     *
     * The row is looked up every time rather than caching its id: a cached id goes stale when the
     * host process restarts, silently sending the rest of the day's records nowhere.
     */
    internal fun appendTo(rows: Rows, payload: String): Boolean = runCatching {
        val name = fileName()
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME/"
        synchronized(writeLock) {
            val rowId = rows.find(name, relative)
                ?: rows.create(name, relative)
                ?: return false
            rows.write(rowId, payload)
        }
    }.getOrDefault(false)

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
        // Takes [writeLock], like the production entry point. A test helper that skipped it would
        // make a concurrency test pass while the shipped path stayed racy.
        return synchronized(writeLock) { appendInto(dir, payloadOf(lines)) }
    }

    /**
     * [Rows] backed by MediaStore. A thin adapter by design: the ordering that caused the split-file
     * bug lives in [appendTo], where it is tested, and nothing here makes a decision.
     */
    private class MediaStoreRows(private val context: Context) : Rows {

        private val collection =
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        override fun find(name: String, relativePath: String): String? = runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(relativePath, name),
                null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0).toString() else null }
        }.getOrNull()

        override fun create(name: String, relativePath: String): String? = runCatching {
            context.contentResolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                },
            )?.let { ContentUris.parseId(it).toString() }
        }.getOrNull()

        override fun write(rowId: String, payload: String): Boolean = runCatching {
            val uri = ContentUris.withAppendedId(collection, rowId.toLong())
            // MediaStore has no append mode; "wa" on an existing row is the append path.
            context.contentResolver.openOutputStream(uri, "wa")
                ?.use { it.write(payload.toByteArray()) } != null
        }.getOrDefault(false)
    }

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
     * Direct file write fallback for the HOST process (X has storage permission).
     * Used when MediaStore.Downloads fails silently — which it does on some OEMs
     * when the host app doesn't own the file. X has WRITE_EXTERNAL_STORAGE so
     * direct File access works.
     */


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
