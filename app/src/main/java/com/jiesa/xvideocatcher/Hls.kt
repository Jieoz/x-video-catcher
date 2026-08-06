package com.jiesa.xvideocatcher

/**
 * HLS playlist parsing for X's video CDN.
 *
 * ## Why this exists
 *
 * 1.13 through 1.18 downloaded `/vid/avc1/0/0/<WxH>/<key>.mp4` believing the `.mp4`
 * extension marked a complete progressive file. It does not. That URL is the HLS
 * **initialisation segment**: an fMP4 header (`ftyp` + `moov`) carrying codec
 * configuration and zero coded frames. Saving it produces a small file no player can
 * open, which is exactly what the device reported.
 *
 * The shape was documented in [MediaUrls]'s own header the whole time:
 *
 * ```
 *   .../<id>/vid/avc1/0/0/1920x1080/<k>.mp4     video init segment
 *   .../<id>/vid/avc1/0/3000/1920x1080/<k>.m4s  video media segment
 * ```
 *
 * The `0/0` is a byte/time offset pair, not a quality marker. Every `.mp4` in the 12.13
 * device capture (83 of them, 6 distinct videos) carried `/0/0/`; not one complete file
 * was ever served, because **this host does not serve one**. X publishes split-track HLS
 * only. There is no progressive rendition to find, so no amount of URL filtering could
 * have produced a playable download.
 *
 * ## What a real download requires
 *
 * Video and audio are separate tracks, each its own playlist and its own segment list:
 *
 * 1. Fetch the master playlist and pick a video variant (highest pixel area).
 * 2. Fetch that variant's media playlist: `#EXT-X-MAP` init segment, then `#EXTINF`
 *    media segments in order.
 * 3. Concatenate init + segments — an fMP4 stream `MediaExtractor` can read.
 * 4. Do the same for the audio group the variant names.
 * 5. Mux both tracks into one MP4.
 *
 * Steps 1, 2 and 4 are parsing and live here; they are pure string work and unit-tested
 * against the real playlist shapes. Steps 3 and 5 need the platform and live in
 * `HlsVideo`.
 *
 * Skipping the audio track yields a silent video — the failure this object's split
 * [Master.audio] list exists to prevent.
 */
object Hls {

    /** A video rendition from the master playlist. */
    data class Variant(
        val url: String,
        val width: Int,
        val height: Int,
        val bandwidth: Long,
        /** `AUDIO=` group this rendition expects its sound from, when declared. */
        val audioGroup: String?,
    ) {
        val area: Long get() = width.toLong() * height.toLong()
    }

    /** An `#EXT-X-MEDIA:TYPE=AUDIO` rendition. */
    data class AudioTrack(
        val url: String,
        val groupId: String,
        val isDefault: Boolean,
    )

    data class Master(
        val variants: List<Variant>,
        val audio: List<AudioTrack>,
    )

    /**
     * A media playlist: the init segment plus every media segment, in playback order.
     *
     * [initUrl] is nullable because a playlist without `#EXT-X-MAP` is legal in plain
     * TS HLS. X always supplies one for fMP4, and a null here means the concatenated
     * output would have no codec configuration — the caller must treat it as a failure
     * rather than writing a headerless file.
     */
    data class MediaPlaylist(
        val initUrl: String?,
        val segments: List<String>,
    )

    /**
     * Resolves a playlist reference against the playlist's own URL.
     *
     * X emits absolute paths (`/amplify_video/...`), so the host must be carried over
     * from [baseUrl]; the relative-path branch is standard-conformance rather than an
     * observed case.
     */
    fun resolve(ref: String, baseUrl: String): String {
        val trimmed = ref.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val schemeEnd = baseUrl.indexOf("://")
        if (schemeEnd < 0) return trimmed
        val hostStart = schemeEnd + 3
        val pathStart = baseUrl.indexOf('/', hostStart)
        val origin = if (pathStart < 0) baseUrl else baseUrl.substring(0, pathStart)
        if (trimmed.startsWith("/")) return origin + trimmed
        val dir = baseUrl.substringBefore('?').substringBeforeLast('/', "")
        return if (dir.isEmpty()) "$origin/$trimmed" else "$dir/$trimmed"
    }

    /**
     * Parses a master playlist.
     *
     * `#EXT-X-STREAM-INF` declares a rendition and the URL follows on the *next*
     * non-comment line, so the tag and its target are read as a pair. An audio-only
     * rendition (no `RESOLUTION`) is dropped from [Master.variants]: it satisfies every
     * other test and would otherwise be selectable as "the video", which is how a silent
     * download happens.
     */
    fun parseMaster(text: String, baseUrl: String): Master {
        val variants = mutableListOf<Variant>()
        val audio = mutableListOf<AudioTrack>()
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    if (attrs["TYPE"].equals("AUDIO", ignoreCase = true)) {
                        val uri = attrs["URI"]
                        val group = attrs["GROUP-ID"]
                        if (!uri.isNullOrEmpty() && !group.isNullOrEmpty()) {
                            audio += AudioTrack(
                                url = resolve(uri, baseUrl),
                                groupId = group,
                                isDefault = attrs["DEFAULT"].equals("YES", ignoreCase = true),
                            )
                        }
                    }
                    i++
                }

                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    // The URL is the next line that is not a tag or comment.
                    var j = i + 1
                    while (j < lines.size && lines[j].startsWith("#")) j++
                    val target = lines.getOrNull(j)
                    val resolution = attrs["RESOLUTION"]
                    val wh = resolution?.split('x', 'X')
                    val w = wh?.getOrNull(0)?.trim()?.toIntOrNull()
                    val h = wh?.getOrNull(1)?.trim()?.toIntOrNull()
                    if (target != null && w != null && h != null && w > 0 && h > 0) {
                        variants += Variant(
                            url = resolve(target, baseUrl),
                            width = w,
                            height = h,
                            bandwidth = attrs["BANDWIDTH"]?.trim()?.toLongOrNull() ?: 0L,
                            audioGroup = attrs["AUDIO"],
                        )
                    }
                    i = if (target != null) j + 1 else i + 1
                }

                else -> i++
            }
        }
        return Master(variants = variants, audio = audio)
    }

    /**
     * Parses a media playlist into its init segment and ordered media segments.
     *
     * Segment URLs are the non-tag lines. `#EXTINF` is not required to precede one for
     * this purpose, so no pairing is done — anything that is not a comment is a segment.
     */
    fun parseMedia(text: String, baseUrl: String): MediaPlaylist {
        var init: String? = null
        val segments = mutableListOf<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-MAP:")) {
                val uri = attributes(line.substringAfter(':'))["URI"]
                if (!uri.isNullOrEmpty()) init = resolve(uri, baseUrl)
                continue
            }
            if (line.startsWith("#")) continue
            segments += resolve(line, baseUrl)
        }
        return MediaPlaylist(initUrl = init, segments = segments)
    }

    /**
     * Highest-area video rendition.
     *
     * Area rather than height: X serves 720x1280 and 1280x720 in the same capture and
     * comparing height alone ranks portrait above an equally large landscape. Bandwidth
     * breaks an exact area tie.
     */
    fun bestVariant(master: Master): Variant? =
        master.variants.maxWithOrNull(compareBy({ it.area }, { it.bandwidth }))

    /**
     * The audio rendition a variant should be muxed with.
     *
     * Preference order: the variant's own `AUDIO=` group (marked DEFAULT first), then any
     * declared audio at all. The fallback matters because a master may omit `AUDIO=` on
     * the rendition while still declaring one audio group; refusing to guess there would
     * silently drop sound.
     */
    fun audioFor(master: Master, variant: Variant?): AudioTrack? {
        val group = variant?.audioGroup
        val inGroup = if (group != null) master.audio.filter { it.groupId == group } else emptyList()
        val pool = if (inGroup.isNotEmpty()) inGroup else master.audio
        return pool.firstOrNull { it.isDefault } ?: pool.firstOrNull()
    }

    /**
     * Splits an HLS attribute list.
     *
     * Hand-rolled rather than `split(',')` because quoted values legitimately contain
     * commas — `CODECS="avc1.4d001f,mp4a.40.2"` is one attribute, and splitting on every
     * comma turns it into two, losing whatever attribute follows it.
     */
    internal fun attributes(spec: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val sb = StringBuilder()
        var quoted = false
        val parts = mutableListOf<String>()
        for (c in spec) {
            when {
                c == '"' -> { quoted = !quoted; sb.append(c) }
                c == ',' && !quoted -> { parts += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) parts += sb.toString()
        for (part in parts) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim().removeSurrounding("\"")
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }
}
