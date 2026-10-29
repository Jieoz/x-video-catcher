package com.jiesa.xvideocatcher

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP client for fetching playlists and segments.
 *
 * `HttpURLConnection` rather than OkHttp: this code is injected into X's process, and
 * pulling a networking library into a foreign app means shipping a second copy of whatever
 * X already uses and risking a classloader clash. The platform client is always present and
 * needs no dependency.
 *
 * Requests carry a browser-ish `User-Agent` and a `Referer`, but **not** because the CDN
 * demands them: measured against Jay's captured URLs, `video.twimg.com` serves masters,
 * variants and segments with **no headers at all** (200 on every probe — bare request, UA
 * only, and UA+Referer returned identical `Content-Length`). They are sent because
 * `video.twimg.com` is unauthenticated public CDN traffic where an absent UA is the one thing
 * that stands out, and adding them costs nothing. Do not describe them as required, and do
 * not "fix" a 403 by adding more headers — a 403 here means the URL expired, which retrying
 * cannot help.
 */
object Http {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** How many times a failed request is retried. CDN 5xx on a single segment is common. */
    private const val ATTEMPTS = 3

    class HttpError(val code: Int, url: String) :
        java.io.IOException("HTTP $code for ${url.take(140)}")

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", "https://twitter.com/")
        conn.setRequestProperty("Accept", "*/*")
        return conn
    }

    /**
     * Runs [block] against a successful response body, retrying transient failures.
     *
     * A 4xx is not retried: it means the URL is wrong or expired, and hammering it three
     * times only delays the error the caller needs to see. 5xx and I/O errors are retried
     * with a linear backoff.
     */
    private fun <T> request(url: String, block: (InputStream) -> T): T {
        var last: Exception? = null
        for (attempt in 1..ATTEMPTS) {
            var conn: HttpURLConnection? = null
            try {
                conn = open(url)
                val code = conn.responseCode
                if (code in 200..299) {
                    return conn.inputStream.use(block)
                }
                val error = HttpError(code, url)
                if (code in 400..499) throw error
                last = error
            } catch (e: HttpError) {
                if (e.code in 400..499) throw e
                last = e
            } catch (e: Exception) {
                last = e
            } finally {
                runCatching { conn?.disconnect() }
            }
            if (attempt < ATTEMPTS) Thread.sleep(400L * attempt)
        }
        throw last ?: java.io.IOException("request failed: ${url.take(140)}")
    }

    fun bytes(url: String): ByteArray = request(url) { it.readBytes() }

    fun text(url: String): String = request(url) { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Downloads [url] into [target] with HTTP Range resume.
     *
     * Streaming straight into the MediaStore stream meant a mid-transfer stall could not be
     * recovered without restarting from byte zero — and restarting hit the same stall, so the
     * progress bar froze at the same offset until the retry budget ran out. Staging into a
     * cache file changes the failure mode: a timeout or dropped connection aborts only this
     * connection, and the next attempt asks the CDN for `Range: bytes=N-` and appends.
     *
     * A 4xx is still fatal (the URL is expired; no byte offset fixes that). A 200 in reply to
     * a Range request means the server ignored the header — the cache is discarded and the
     * body replaces it from zero. Per-connection read timeout (30 s) is the stall detector;
     * there is no separate watchdog.
     */
    fun downloadResumable(
        url: String,
        target: File,
        onBytes: ((Long) -> Unit)? = null,
        isAborted: (() -> Boolean)? = null,
    ): Long {
        val maxConnections = 8
        var last: Exception? = null
        for (attempt in 1..maxConnections) {
            if (isAborted?.invoke() == true) {
                throw java.io.IOException("download aborted by user")
            }
            var conn: HttpURLConnection? = null
            try {
                conn = open(url)
                val have = if (target.exists()) target.length() else 0L
                var appending = false
                if (have > 0L) {
                    conn.setRequestProperty("Range", "bytes=$have-")
                }
                val code = conn.responseCode
                when {
                    code == 206 -> appending = true
                    code in 200..299 -> {
                        // Full-body reply: either a first attempt or a server that ignored
                        // our Range. Either way the cache starts (or restarts) at zero.
                        if (have > 0L) DiagLog.line("  http: server ignored Range, restarting")
                        target.delete()
                        target.createNewFile()
                    }
                    else -> throw HttpError(code, url)
                }
                val base: Long = if (appending) have else 0L
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(target, appending).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = base
                        var lastReported = base
                        while (true) {
                            if (isAborted?.invoke() == true) {
                                throw java.io.IOException("download aborted by user")
                            }
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            total += n
                            if (onBytes != null && total - lastReported >= 512 * 1024) {
                                lastReported = total
                                onBytes(total)
                            }
                        }
                        out.flush()
                        onBytes?.invoke(total)
                    }
                }
                return target.length()
            } catch (e: HttpError) {
                if (e.code in 400..499) throw e
                last = e
            } catch (e: Exception) {
                last = e
            } finally {
                runCatching { conn?.disconnect() }
            }
            if (attempt < maxConnections) {
                if (isAborted?.invoke() == true) throw java.io.IOException("download aborted by user")
                DiagLog.line(
                    "  http: transfer interrupted at ${target.length()} bytes " +
                        "(attempt $attempt), resuming",
                )
                Thread.sleep(400L * (attempt + 1))
            }
        }
        throw last ?: java.io.IOException("download failed: ${url.take(140)}")
    }

    /**
     * Streams a response into [sink], returning the byte count.
     *
     * [onBytes] is called with the running total, throttled to roughly every 512 KB rather
     * than every 64 KB chunk: a 1080p video is ~330 chunks and the callback drives a UI
     * update, so firing it per chunk floods the main thread with work that renders identically.
     */
    fun copyTo(url: String, sink: OutputStream, onBytes: ((Long) -> Unit)? = null): Long =
        request(url) { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            var lastReported = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sink.write(buffer, 0, n)
                total += n
                if (onBytes != null && total - lastReported >= 512 * 1024) {
                    lastReported = total
                    onBytes(total)
                }
            }
            onBytes?.invoke(total)
            total
        }
}
