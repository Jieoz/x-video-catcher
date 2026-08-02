package com.jiesa.xvideocatcher

/**
 * URL classification for X's media CDNs.
 *
 * Video lives on `video.twimg.com` as split audio/video HLS. Shapes confirmed from two
 * real captures (211 hits, 18 videos), all via OkHttp — this build of X has no Cronet:
 *
 *   .../<kind>/<id>/pl/<key>.m3u8                      master playlist
 *   .../<kind>/<id>/pl/avc1/1920x1080/<key>.m3u8       video variant playlist
 *   .../<kind>/<id>/pl/mp4a/128000/<key>.m3u8          audio variant playlist
 *   .../<kind>/<id>/vid/avc1/0/0/1920x1080/<k>.mp4     video init segment
 *   .../<kind>/<id>/vid/avc1/0/3000/1920x1080/<k>.m4s  video media segment
 *   .../<kind>/<id>/aud/mp4a/0/3000/32000/<k>.m4s      audio media segment
 *
 * Audio and video are separate tracks, so grabbing video segments alone yields a silent
 * file. A download has to take both.
 *
 * Photos live on a different host (`pbs.twimg.com`) with a different quality mechanism:
 * one stored image, resized on demand by a `name=` query parameter rather than by path.
 *
 *   https://pbs.twimg.com/media/<key>?format=jpg&name=small     ~680px
 *   https://pbs.twimg.com/media/<key>?format=jpg&name=orig      full stored size
 *   https://pbs.twimg.com/media/<key>.jpg                       defaults to medium
 *
 * That difference is why quality selection is split in two below: for video the answer
 * is the largest WxH in the ladder, for a photo it is the same URL with name=orig.
 */
object MediaUrls {

    /** `amplify_video` (promoted), `ext_tw_video` (user uploads), `tweet_video` (GIF). */
    private val MEDIA_KINDS = Regex("/(amplify_video|ext_tw_video|tweet_video)/")

    private val VIDEO_HOSTS = listOf(
        "video.twimg.com",
        "video-ft.twimg.com",
        "amp.twimg.com",
    )

    private val PHOTO_HOSTS = listOf(
        "pbs.twimg.com",
        "pbs-ft.twimg.com",
    )

    /**
     * A media-looking path. Required even on the video host: matching the host alone
     * logged `https://video.twimg.com/robots.txt` on every launch, which is exactly
     * the kind of noise that buries the URL actually being played.
     */
    private val MEDIA_PATH = Regex(
        """(\.m3u8|\.mp4|\.m4s|\.ts)(\?|$)|/pl/|/vid/|/aud/|/seg/""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Photo paths worth keeping. `/media/` is a tweet photo; `/tweet_video_thumb/` and
     * `/ext_tw_video_thumb/` are video posters, kept because they identify a video post
     * even when its playlist was never requested.
     *
     * Deliberately excluded: `/profile_images/`, `/profile_banners/`, `/emoji/`,
     * `/card_img/`, `/semantic_core_img/`. A timeline scroll fetches hundreds of those
     * per minute and none of them is content the user asked to save — with them included
     * the log is unreadable, which is the same failure as the robots.txt noise.
     */
    private val PHOTO_PATH = Regex(
        """/(media|tweet_video_thumb|ext_tw_video_thumb|amplify_video_thumb)/""",
        RegexOption.IGNORE_CASE,
    )

    private val PHOTO_EXCLUDED = Regex(
        """/(profile_images|profile_banners|emoji|card_img|semantic_core_img|hashflag|ads-payload)/""",
        RegexOption.IGNORE_CASE,
    )

    fun isInteresting(url: String): Boolean {
        if (url.isEmpty()) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        return isInterestingVideo(lower) || isInterestingPhoto(lower)
    }

    private fun isInterestingVideo(lower: String): Boolean {
        if (!MEDIA_PATH.containsMatchIn(lower) && !MEDIA_KINDS.containsMatchIn(lower)) return false
        // Either a known media host, or a known media path on some other CDN host —
        // X has changed CDN hostnames before, and the path shape is the stable part.
        return VIDEO_HOSTS.any { lower.contains(it) } || MEDIA_KINDS.containsMatchIn(lower)
    }

    private fun isInterestingPhoto(lower: String): Boolean {
        if (!PHOTO_HOSTS.any { lower.contains(it) }) return false
        if (PHOTO_EXCLUDED.containsMatchIn(lower)) return false
        return PHOTO_PATH.containsMatchIn(lower)
    }

    fun isPhoto(url: String): Boolean = isInterestingPhoto(url.lowercase())

    /** Any playlist. */
    fun isManifest(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains("/pl/")
    }

    /**
     * The master playlist: `/pl/<key>.m3u8` with no codec/resolution segment in
     * between. This is the only URL a downloader needs — every variant, resolution and
     * the audio track are reachable from it, so it is what gets surfaced to the user.
     *
     * Variant playlists (`/pl/avc1/1920x1080/…`) are deliberately excluded: they carry
     * one resolution only, and picking from them would mean trusting whichever quality
     * the player happened to switch to rather than the best available.
     *
     * The optional `pu/` segment is not cosmetic: `ext_tw_video` (user uploads) inserts it
     * between the id and the track, so a pattern demanding `<id>/pl/` classified every
     * user-upload master as a variant — the download path would then have had nothing to
     * work from for exactly the videos Jay cares about most.
     */
    private val MASTER_PLAYLIST = Regex(
        """/(?:amplify_video|ext_tw_video|tweet_video)/\d+/(?:pu/)?pl/[A-Za-z0-9_-]+\.m3u8""",
        RegexOption.IGNORE_CASE,
    )

    fun isMasterPlaylist(url: String): Boolean = MASTER_PLAYLIST.containsMatchIn(url)

    /**
     * Tweet id from a media URL, used to group the segments of one video together.
     * Note this is the *media* id, not the status id from the tweet's web link.
     */
    private val MEDIA_ID = Regex("""/(?:amplify_video|ext_tw_video|tweet_video)/(\d+)/""")

    fun mediaId(url: String): String? = MEDIA_ID.find(url)?.groupValues?.get(1)

    /**
     * Stable identity of a photo: the CDN key, which is what stays the same across the
     * small/medium/large/orig renderings of one image. Used to avoid saving the same
     * photo repeatedly as the timeline re-requests it at different sizes.
     */
    private val PHOTO_KEY = Regex(
        """/(?:media|tweet_video_thumb|ext_tw_video_thumb|amplify_video_thumb)/([A-Za-z0-9_-]+)""",
        RegexOption.IGNORE_CASE,
    )

    fun photoKey(url: String): String? =
        PHOTO_KEY.find(url)?.groupValues?.get(1)?.substringBefore('.')

    /**
     * Resolution encoded in a variant or segment URL, as width x height.
     * Returns null for audio and for master playlists, which carry no resolution.
     */
    private val RESOLUTION = Regex("""/(\d{2,5})x(\d{2,5})/""")

    fun resolution(url: String): Pair<Int, Int>? {
        val m = RESOLUTION.find(url) ?: return null
        val w = m.groupValues[1].toIntOrNull() ?: return null
        val h = m.groupValues[2].toIntOrNull() ?: return null
        return w to h
    }

    /**
     * Picks the highest-resolution video URL: the player requests several resolutions
     * while adapting to bandwidth, so "what was playing" is not "the best available".
     *
     * Ordered by pixel count rather than height, since X serves both portrait and
     * landscape (720x1280 and 1280x720 both appear in one capture, identical area) and
     * comparing height alone would rank them wrongly.
     */
    fun highestResolution(urls: Collection<String>): String? =
        urls.mapNotNull { u -> resolution(u)?.let { (w, h) -> u to w.toLong() * h } }
            .maxByOrNull { it.second }
            ?.first

    /**
     * The largest rendering X will serve for any stored format.
     *
     * `name=orig` looks like the obvious choice and is what every guide recommends, but
     * measured against Jay's own captured photos it **404s whenever `format` does not
     * match how the image is stored**: three PNG photos returned 404 for
     * `format=jpg&name=orig` while answering 200 for `format=png&name=orig`. Since the
     * stored format is not knowable from the URL, `orig` is unsafe to request blindly.
     *
     * `4096x4096` has neither problem: it returned 200 for every captured photo in both
     * formats, and on the 8 JPEGs where `orig` did work it returned a byte-identical
     * response. X caps uploads below 4096px, so this is the full image, not a resize.
     */
    private const val LARGEST_SIZE = "4096x4096"

    /**
     * Rewrites a photo URL to the full-size image.
     *
     * X keeps one image per photo and resizes on request, so quality is a query
     * parameter rather than a separate URL: `name=tiny|small|medium|large|4096x4096`.
     * Any existing `name` is replaced and a missing one is added, so the result is the
     * full image regardless of which size the timeline happened to load — captures show
     * X asking for `large` and `tiny` only, never the full one.
     *
     * `format` is **preserved rather than guessed** wherever one is given: the same photo
     * is stored as `png` or `jpg`, and forcing `jpg` onto a PNG re-encodes it at a
     * fraction of the size (358430 → 29817 bytes on a captured photo). A path extension
     * is folded into `format` for the same reason.
     *
     * Two exceptions, both measured rather than assumed:
     *  - `format=webp` is dropped. It is a display-time transcode X requests for
     *    thumbnails, not a stored format — the same photos were also fetched as
     *    `format=jpg`, and jpg comes back 45% larger (275762 → 360662 bytes).
     *  - `format` is never omitted. Requesting `?name=4096x4096` with no format 404s on
     *    every captured photo, so when there is nothing to preserve `jpg` is supplied as
     *    the default X itself uses for these.
     *
     * Returns the input unchanged when it is not a photo URL, so callers can apply it
     * blindly.
     */
    fun highestQualityPhoto(url: String): String {
        if (!isPhoto(url)) return url

        val hashIndex = url.indexOf('#')
        val fragment = if (hashIndex >= 0) url.substring(hashIndex) else ""
        val withoutFragment = if (hashIndex >= 0) url.substring(0, hashIndex) else url

        val queryIndex = withoutFragment.indexOf('?')
        var path = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
        val query = if (queryIndex >= 0) withoutFragment.substring(queryIndex + 1) else ""

        val params = query.split('&')
            .filter { it.isNotEmpty() }
            .mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }
            .toMutableList()

        // A path extension is the stored format, so fold it into format= rather than
        // dropping it — losing it is what would let a PNG be re-encoded as JPEG.
        val dot = path.lastIndexOf('.')
        val slash = path.lastIndexOf('/')
        if (dot > slash) {
            val ext = path.substring(dot + 1).lowercase()
            if (ext in setOf("jpg", "jpeg", "png", "webp", "gif")) {
                path = path.substring(0, dot)
                if (params.none { it.first.equals("format", ignoreCase = true) }) {
                    params += "format" to if (ext == "jpeg") "jpg" else ext
                }
            }
        }

        val rebuilt = params
            .filterNot { it.first.equals("name", ignoreCase = true) }
            // webp is a display-time transcode for thumbnails, not the stored format.
            .filterNot {
                it.first.equals("format", ignoreCase = true) &&
                    it.second.equals("webp", ignoreCase = true)
            }
            .toMutableList()

        // Never leave format off: with no format the CDN 404s this size on every
        // captured photo, so a dropped webp has to be replaced rather than just removed.
        if (rebuilt.none { it.first.equals("format", ignoreCase = true) }) {
            rebuilt += "format" to "jpg"
        }
        rebuilt += "name" to LARGEST_SIZE

        return path + "?" + rebuilt.joinToString("&") { "${it.first}=${it.second}" } + fragment
    }

    /**
     * An audio URL: either an audio segment (`/aud/mp4a/…`) or an audio *playlist*
     * (`/pl/mp4a/<bitrate>/…`).
     *
     * The playlist form matters and was missing. Matching only `/aud/` meant the 26 audio
     * playlists in the capture were labelled `variant`, since they satisfy the generic
     * playlist test — so the log said "variant" for a track carrying no video, and any
     * quality choice made over "the captured variants" could pick a silent playlist as the
     * best video rendition.
     */
    fun isAudioTrack(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/aud/") || AUDIO_PLAYLIST.containsMatchIn(lower)
    }

    private val AUDIO_PLAYLIST = Regex("""/pl/mp4a/\d+/""", RegexOption.IGNORE_CASE)

    /** True for a video-track URL (not audio, not a playlist). */
    fun isVideoTrack(url: String): Boolean =
        url.contains("/vid/", ignoreCase = true) && !isManifest(url)
}
