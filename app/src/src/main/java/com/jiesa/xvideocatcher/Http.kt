package com.jiesa.xvideocatcher

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

    fun text(url: String): String = request(url) { it.readBytes().toString(Charsets.UTF_8) }

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
