package com.jiesa.xvideocatcher

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Resolves media for a **status id** (`https://x.com/i/status/<id>`), never from
 * timeline capture recency.
 *
 * Device 1.23: freezing [hook.MediaSpy.best] downloaded a neighbour.
 * Device 1.24: syndication alone returned `TweetTombstone` (46 bytes) for recent
 * posts Jay actually taps; the code then fell back to capture and saved a photo
 * while the user was on a video (`STATUS parse miss … fallback capture`).
 *
 * 1.25 tries sources in order, all keyed by the same status id:
 * 1. `cdn.syndication.twimg.com/tweet-result?token=x` (works for older public posts)
 * 2. `api.vxtwitter.com/Twitter/status/<id>` (works on tombstoned recent posts)
 * 3. `api.fxtwitter.com/status/<id>`
 *
 * A media id must never be passed as [statusId]. JSON is parsed with the in-file
 * reader (plain JUnit; Android `org.json` is "not mocked").
 */
object StatusMedia {

    data class Video(
        val mediaId: String,
        val progressiveUrl: String?,
        val masterUrl: String?,
        val width: Int,
        val height: Int,
    ) {
        val downloadUrl: String?
            get() = progressiveUrl ?: masterUrl
    }

    data class Resolved(
        val statusId: String,
        val photos: List<String>,
        val videos: List<Video>,
        val source: String = "unknown",
    ) {
        val isEmpty: Boolean get() = photos.isEmpty() && videos.isEmpty()
        val size: Int get() = photos.size + videos.size
    }

    /**
     * Media for [statusId], from whichever source can answer.
     *
     * ## Why the sources are raced rather than tried in order
     *
     * Preference order and *execution* order were the same thing until 1.53, and that cost the user
     * the whole wait for a source that structurally cannot answer. `cdn.syndication.twimg.com`
     * returns a 46-byte `TweetTombstone` for every status posted after roughly 2026-04 — measured on
     * five real ids: 2021-01 and 2023-12 return media, 2026-04 and later return a tombstone or 404.
     * Jay taps recent posts, so on the 20260829 device log syndication was a guaranteed miss that
     * still charged **1.36 s** before vxtwitter was even contacted.
     *
     * Dropping syndication is the wrong fix: it is the only first-party source, and it still answers
     * for older posts, which is exactly the case the other two are least reliable on. So all three
     * are issued concurrently and the *preference* order is applied to the results. Wall time
     * becomes the slowest useful source instead of the sum of the failures before it, and no source
     * had to be given up to get that.
     *
     * Callers already run this off the host's main thread ([hook.HostDownloader] resolves on its
     * pool), so the pool here only adds the two extra sockets, and it is shut down before returning.
     */
    fun resolve(statusId: String): Resolved? {
        if (!STATUS_ID.matches(statusId)) {
            DiagLog.line("$MARK reject non-status id=${statusId.take(24)}")
            return null
        }
        val sources = endpoints(statusId)
        val bodies = fetchAll(sources)

        // Preference order, independent of which one returned first. A slower first-party answer is
        // still preferred over a faster mirror.
        for ((name, _) in sources) {
            val body = bodies[name]
            if (body == null) {
                DiagLog.line("$MARK $name fetch failed status=$statusId")
                continue
            }
            if (body.isBlank() || body == "{}") {
                DiagLog.line("$MARK $name empty status=$statusId bytes=${body.length}")
                continue
            }
            if (isTombstone(body)) {
                DiagLog.line("$MARK $name tombstone status=$statusId bytes=${body.length}")
                continue
            }
            val parsed = parseFor(name, body, statusId)
            if (parsed != null && !parsed.isEmpty) {
                val withSrc = parsed.copy(source = name)
                DiagLog.line(
                    "$MARK resolved via=$name status=$statusId " +
                        "photos=${withSrc.photos.size} videos=${withSrc.videos.size}",
                )
                return withSrc
            }
            DiagLog.line("$MARK $name parse miss status=$statusId bytes=${body.length}")
        }
        DiagLog.line("$MARK all sources miss status=$statusId")
        return null
    }

    /**
     * Bodies for every source, fetched concurrently. Missing key = that source did not answer.
     *
     * [fetch] is a seam so the concurrency and the preference-ordering can be tested without
     * network: the production value is [Http.text].
     */
    internal fun fetchAll(
        sources: List<Pair<String, String>>,
        fetch: (String) -> String = { Http.text(it) },
    ): Map<String, String> {
        if (sources.isEmpty()) return emptyMap()
        if (sources.size == 1) {
            val (name, url) = sources[0]
            return runCatching { fetch(url) }.getOrNull()?.let { mapOf(name to it) } ?: emptyMap()
        }
        val pool = Executors.newFixedThreadPool(sources.size) { r ->
            Thread(r, "xvc-status-resolve").apply { isDaemon = true }
        }
        try {
            val pending = sources.map { (name, url) ->
                name to pool.submit<String?> { runCatching { fetch(url) }.getOrNull() }
            }
            val out = LinkedHashMap<String, String>()
            for ((name, future) in pending) {
                val body = runCatching { future.get(SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                    .getOrElse {
                        // A hung source must not hold the tap hostage: it is abandoned and the
                        // remaining answers are still used.
                        future.cancel(true)
                        DiagLog.line("$MARK $name gave up after ${SOURCE_TIMEOUT_MS}ms")
                        null
                    }
                if (body != null) out[name] = body
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    /** Dispatches a body to the parser for [name]. One place, so [resolve] and its tests agree. */
    internal fun parseFor(name: String, body: String, statusId: String): Resolved? = when (name) {
        "syndication" -> parseSyndication(body, statusId)
        "vxtwitter" -> parseVxTwitter(body, statusId)
        "fxtwitter" -> parseFxTwitter(body, statusId)
        else -> null
    }

    fun endpoints(statusId: String): List<Pair<String, String>> = listOf(
        "syndication" to "https://cdn.syndication.twimg.com/tweet-result?id=$statusId&token=x",
        "vxtwitter" to "https://api.vxtwitter.com/Twitter/status/$statusId",
        "fxtwitter" to "https://api.fxtwitter.com/status/$statusId",
    )

    /** @deprecated use [endpoints]; kept so older tests that call endpoint() still compile. */
    fun endpoint(statusId: String): String = endpoints(statusId).first().second

    fun isTombstone(body: String): Boolean =
        body.contains("TweetTombstone") || (
            body.contains("\"tombstone\"") && !body.contains("mediaDetails") &&
                !body.contains("media_extended") && body.length < 200
            )

    fun parse(body: String, statusId: String): Resolved? = parseSyndication(body, statusId)

    fun parseSyndication(body: String, statusId: String): Resolved? {
        if (isTombstone(body)) return null
        val root = runCatching { Json.parseObject(body) }.getOrNull() ?: return null
        val typeName = root.str("__typename")
        if (typeName == "TweetTombstone") return null
        val idStr = root.str("id_str").ifEmpty { root.str("id") }
        if (idStr.isNotEmpty() && idStr != statusId) {
            DiagLog.line("$MARK id mismatch want=$statusId got=$idStr")
            return null
        }
        val photos = linkedSetOf<String>()
        val videos = LinkedHashMap<String, Video>()
        root.arr("photos").forEach { node ->
            val o = node as? Json.Obj ?: return@forEach
            val u = o.str("url").ifEmpty { o.str("media_url_https") }
            if (u.startsWith("http") && MediaUrls.isTweetPhoto(u)) photos.add(u)
        }
        (root["video"] as? Json.Obj)?.let { absorbVideoObject(it, videos) }
        root.arr("mediaDetails").forEach { node ->
            val md = node as? Json.Obj ?: return@forEach
            when (md.str("type")) {
                "photo" -> {
                    val u = md.str("media_url_https").ifEmpty { md.str("media_url") }
                    if (u.startsWith("http") && MediaUrls.isTweetPhoto(u)) photos.add(u)
                }
                "video", "animated_gif" -> absorbMediaDetailVideo(md, videos)
            }
        }
        (root["entities"] as? Json.Obj)?.arr("media")?.forEach { node ->
            val md = node as? Json.Obj ?: return@forEach
            when (md.str("type")) {
                "photo" -> {
                    val u = md.str("media_url_https").ifEmpty { md.str("media_url") }
                    if (u.startsWith("http") && MediaUrls.isTweetPhoto(u)) photos.add(u)
                }
                "video", "animated_gif" -> absorbMediaDetailVideo(md, videos)
            }
        }
        if (photos.isEmpty() && videos.isEmpty()) return null
        return Resolved(statusId, photos.toList(), videos.values.toList(), "syndication")
    }

    fun parseVxTwitter(body: String, statusId: String): Resolved? {
        val root = runCatching { Json.parseObject(body) }.getOrNull() ?: return null
        val id = root.str("tweetID").ifEmpty { root.str("conversationID") }
        if (id.isNotEmpty() && id != statusId) {
            DiagLog.line("$MARK vx id mismatch want=$statusId got=$id")
            return null
        }
        val photos = linkedSetOf<String>()
        val videos = LinkedHashMap<String, Video>()
        root.arr("media_extended").forEach { node ->
            val m = node as? Json.Obj ?: return@forEach
            val type = m.str("type")
            val url = m.str("url")
            if (!url.startsWith("http")) return@forEach
            when (type) {
                "image", "photo" -> {
                    if (MediaUrls.isTweetPhoto(url) || url.contains("pbs.twimg.com/media/")) {
                        photos.add(url)
                    }
                }
                "video", "gif", "animated_gif" -> {
                    val mediaId = m.str("id_str").ifEmpty {
                        MediaUrls.mediaId(url) ?: return@forEach
                    }
                    val size = m["size"] as? Json.Obj
                    val w = size?.int("width") ?: MediaUrls.resolution(url)?.first ?: 0
                    val h = size?.int("height") ?: MediaUrls.resolution(url)?.second ?: 0
                    val progressive = if (url.substringBefore('?').endsWith(".mp4", true)) url else null
                    val master = if (MediaUrls.isMasterPlaylist(url) || url.contains(".m3u8")) url else null
                    val prev = videos[mediaId]
                    videos[mediaId] = Video(
                        mediaId = mediaId,
                        progressiveUrl = progressive ?: prev?.progressiveUrl,
                        masterUrl = master ?: prev?.masterUrl,
                        width = maxOf(w, prev?.width ?: 0),
                        height = maxOf(h, prev?.height ?: 0),
                    )
                }
            }
        }
        // mediaURLs as last-resort type inference when media_extended is thin
        if (photos.isEmpty() && videos.isEmpty()) {
            root.arr("mediaURLs").forEach { node ->
                val u = (node as? Json.Str)?.value ?: return@forEach
                when {
                    u.contains(".mp4") || u.contains("/amplify_video/") || u.contains("/ext_tw_video/") -> {
                        val mid = MediaUrls.mediaId(u) ?: return@forEach
                        val (w, h) = MediaUrls.resolution(u) ?: (0 to 0)
                        videos[mid] = Video(mid, u, null, w, h)
                    }
                    MediaUrls.isTweetPhoto(u) || u.contains("pbs.twimg.com/media/") -> photos.add(u)
                }
            }
        }
        if (photos.isEmpty() && videos.isEmpty()) return null
        return Resolved(statusId, photos.toList(), videos.values.toList(), "vxtwitter")
    }

    fun parseFxTwitter(body: String, statusId: String): Resolved? {
        val root = runCatching { Json.parseObject(body) }.getOrNull() ?: return null
        val tweet = root["tweet"] as? Json.Obj ?: return null
        val id = tweet.str("id")
        if (id.isNotEmpty() && id != statusId) {
            DiagLog.line("$MARK fx id mismatch want=$statusId got=$id")
            return null
        }
        val media = tweet["media"] as? Json.Obj ?: return null
        val photos = linkedSetOf<String>()
        val videos = LinkedHashMap<String, Video>()
        media.arr("photos").forEach { node ->
            val p = node as? Json.Obj ?: return@forEach
            val u = p.str("url")
            if (u.startsWith("http")) photos.add(u)
        }
        media.arr("videos").forEach { node ->
            absorbFxVideo(node as? Json.Obj ?: return@forEach, videos)
        }
        // all[] may mix types
        media.arr("all").forEach { node ->
            val m = node as? Json.Obj ?: return@forEach
            when (m.str("type")) {
                "photo" -> {
                    val u = m.str("url")
                    if (u.startsWith("http")) photos.add(u)
                }
                "video", "gif", "animated_gif" -> absorbFxVideo(m, videos)
            }
        }
        if (photos.isEmpty() && videos.isEmpty()) return null
        return Resolved(statusId, photos.toList(), videos.values.toList(), "fxtwitter")
    }

    private fun absorbFxVideo(m: Json.Obj, out: MutableMap<String, Video>) {
        val mediaId = m.str("id").ifEmpty { MediaUrls.mediaId(m.str("url")) ?: return }
        var progressive: String? = m.str("url").takeIf {
            it.startsWith("http") && it.substringBefore('?').endsWith(".mp4", true)
        }
        var master: String? = null
        var bestArea = progressive?.let { MediaUrls.resolution(it) }?.let { it.first.toLong() * it.second } ?: 0L
        m.arr("formats").forEach { node ->
            val f = node as? Json.Obj ?: return@forEach
            val u = f.str("url")
            if (!u.startsWith("http")) return@forEach
            val container = f.str("container")
            when {
                container.contains("m3u8", true) || u.contains(".m3u8") ->
                    if (master == null) master = u
                u.substringBefore('?').endsWith(".mp4", true) -> {
                    val area = MediaUrls.resolution(u)?.let { it.first.toLong() * it.second } ?: 0L
                    if (area >= bestArea) {
                        bestArea = area
                        progressive = u
                    }
                }
            }
        }
        val w = m.int("width") ?: MediaUrls.resolution(progressive ?: "")?.first ?: 0
        val h = m.int("height") ?: MediaUrls.resolution(progressive ?: "")?.second ?: 0
        val prev = out[mediaId]
        out[mediaId] = Video(
            mediaId,
            progressive ?: prev?.progressiveUrl,
            master ?: prev?.masterUrl,
            maxOf(w, prev?.width ?: 0),
            maxOf(h, prev?.height ?: 0),
        )
    }

    private fun absorbVideoObject(video: Json.Obj, out: MutableMap<String, Video>) {
        val variants = video.arr("variants")
        val urls = variantUrls(variants, srcKey = "src", typeKey = "type")
        val mediaId = urls.firstNotNullOfOrNull { MediaUrls.mediaId(it.second) }
            ?: (video["videoId"] as? Json.Obj)?.str("id")?.takeIf { it.isNotEmpty() }
            ?: return
        val progressive = bestProgressive(urls)
        val master = urls.firstOrNull { it.first.contains("mpegURL", ignoreCase = true) }?.second
            ?: urls.firstOrNull { MediaUrls.isMasterPlaylist(it.second) }?.second
        val (w, h) = aspectToSize(video["aspectRatio"] as? Json.Arr)
            ?: progressive?.let { MediaUrls.resolution(it) }
            ?: (0 to 0)
        out[mediaId] = Video(mediaId, progressive, master, w, h)
    }

    private fun absorbMediaDetailVideo(md: Json.Obj, out: MutableMap<String, Video>) {
        val vi = md["video_info"] as? Json.Obj ?: return
        val urls = variantUrls(vi.arr("variants"), srcKey = "url", typeKey = "content_type")
        val thumb = md.str("media_url_https")
        val mediaId = urls.firstNotNullOfOrNull { MediaUrls.mediaId(it.second) }
            ?: MediaUrls.mediaId(thumb)
            ?: return
        val progressive = bestProgressive(urls)
        val master = urls.firstOrNull { it.first.contains("mpegURL", ignoreCase = true) }?.second
            ?: urls.firstOrNull { MediaUrls.isMasterPlaylist(it.second) }?.second
        val oi = md["original_info"] as? Json.Obj
        val w = oi?.int("width") ?: 0
        val h = oi?.int("height") ?: 0
        val size = if (w > 0 && h > 0) w to h else progressive?.let { MediaUrls.resolution(it) } ?: (0 to 0)
        out[mediaId] = Video(mediaId, progressive, master, size.first, size.second)
    }

    private fun variantUrls(
        variants: List<Json.Node>,
        srcKey: String,
        typeKey: String,
    ): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(variants.size)
        for (node in variants) {
            val v = node as? Json.Obj ?: continue
            val src = v.str(srcKey).ifEmpty { v.str("url") }
            if (!src.startsWith("http")) continue
            val type = v.str(typeKey).ifEmpty { v.str("type") }
            out += type to src
        }
        return out
    }

    private fun bestProgressive(urls: List<Pair<String, String>>): String? {
        val mp4 = urls.filter { (_, u) ->
            u.substringBefore('?').endsWith(".mp4", ignoreCase = true) && !u.contains("/pl/")
        }
        if (mp4.isEmpty()) return null
        return mp4.maxByOrNull { (_, u) ->
            MediaUrls.resolution(u)?.let { (w, h) -> w.toLong() * h } ?: 0L
        }?.second
    }

    private fun aspectToSize(arr: Json.Arr?): Pair<Int, Int>? {
        if (arr == null || arr.size < 2) return null
        val w = (arr[0] as? Json.Num)?.toInt() ?: return null
        val h = (arr[1] as? Json.Num)?.toInt() ?: return null
        return if (w > 0 && h > 0) w to h else null
    }

    /**
     * Minimal JSON reader for objects/arrays/strings/numbers/bools/null.
     * Only what syndication payloads need — not a general-purpose parser.
     */
    internal object Json {
        sealed interface Node
        class Obj(private val map: Map<String, Node>) : Node {
            operator fun get(key: String): Node? = map[key]
            fun str(key: String): String = (map[key] as? Str)?.value ?: ""
            fun int(key: String): Int? = (map[key] as? Num)?.toInt()
            fun arr(key: String): List<Node> = (map[key] as? Arr)?.items ?: emptyList()
        }
        class Arr(val items: List<Node>) : Node {
            val size: Int get() = items.size
            operator fun get(i: Int): Node = items[i]
        }
        class Str(val value: String) : Node
        class Num(val value: String) : Node {
            fun toInt(): Int = value.toDouble().toInt()
        }
        object Null : Node
        object True : Node
        object False : Node

        fun parseObject(text: String): Obj {
            val p = Parser(text)
            val n = p.parseValue()
            p.skipWs()
            require(n is Obj) { "root is not object" }
            return n
        }

        private class Parser(private val s: String) {
            private var i = 0

            fun parseValue(): Node {
                skipWs()
                return when (val c = s.getOrNull(i)) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> Str(parseString())
                    't' -> { expect("true"); True }
                    'f' -> { expect("false"); False }
                    'n' -> { expect("null"); Null }
                    in '0'..'9', '-', '+' -> Num(parseNumber())
                    else -> error("unexpected '$c' at $i")
                }
            }

            private fun parseObject(): Obj {
                expect('{')
                val map = linkedMapOf<String, Node>()
                skipWs()
                if (s.getOrNull(i) == '}') { i++; return Obj(map) }
                while (true) {
                    skipWs()
                    val key = parseString()
                    skipWs(); expect(':')
                    map[key] = parseValue()
                    skipWs()
                    when (s.getOrNull(i)) {
                        ',' -> i++
                        '}' -> { i++; break }
                        else -> error("expected , or } at $i")
                    }
                }
                return Obj(map)
            }

            private fun parseArray(): Arr {
                expect('[')
                val items = ArrayList<Node>()
                skipWs()
                if (s.getOrNull(i) == ']') { i++; return Arr(items) }
                while (true) {
                    items += parseValue()
                    skipWs()
                    when (s.getOrNull(i)) {
                        ',' -> i++
                        ']' -> { i++; break }
                        else -> error("expected , or ] at $i")
                    }
                }
                return Arr(items)
            }

            private fun parseString(): String {
                expect('"')
                val sb = StringBuilder()
                while (i < s.length) {
                    when (val c = s[i++]) {
                        '"' -> return sb.toString()
                        '\\' -> {
                            val e = s.getOrNull(i++) ?: error("bad escape")
                            sb.append(
                                when (e) {
                                    '"', '\\', '/' -> e
                                    'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'
                                    'r' -> '\r'; 't' -> '\t'
                                    'u' -> {
                                        val hex = s.substring(i, i + 4)
                                        i += 4
                                        hex.toInt(16).toChar()
                                    }
                                    else -> e
                                },
                            )
                        }
                        else -> sb.append(c)
                    }
                }
                error("unterminated string")
            }

            private fun parseNumber(): String {
                val start = i
                if (s.getOrNull(i) == '+' || s.getOrNull(i) == '-') i++
                while (s.getOrNull(i)?.isDigit() == true) i++
                if (s.getOrNull(i) == '.') {
                    i++
                    while (s.getOrNull(i)?.isDigit() == true) i++
                }
                if (s.getOrNull(i) == 'e' || s.getOrNull(i) == 'E') {
                    i++
                    if (s.getOrNull(i) == '+' || s.getOrNull(i) == '-') i++
                    while (s.getOrNull(i)?.isDigit() == true) i++
                }
                return s.substring(start, i)
            }

            fun skipWs() {
                while (i < s.length && s[i].isWhitespace()) i++
            }

            private fun expect(c: Char) {
                if (s.getOrNull(i) != c) error("expected '$c' at $i got '${s.getOrNull(i)}'")
                i++
            }

            private fun expect(lit: String) {
                if (!s.startsWith(lit, i)) error("expected $lit at $i")
                i += lit.length
            }
        }
    }


    private val STATUS_ID = Regex("""^\d{5,25}$""")
    private const val MARK = "STATUS"

    /**
     * How long one source may take before the others' answers are used without it.
     *
     * Above [Http]'s own worst case for a single attempt (15 s connect + 30 s read) would make this
     * decoration; below the time a healthy source needs would throw away good answers. 12 s is over
     * the measured cost of the slowest source on the 20260829 log (1.4 s) by a wide margin while
     * still bounding the tap.
     */
    private const val SOURCE_TIMEOUT_MS = 12_000L
}
