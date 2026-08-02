package com.jiesa.xvideocatcher

/**
 * Decides what a saved file is called and what MIME type it is declared as.
 *
 * This is separated from the download itself because getting it wrong is silent: an
 * `image/jpeg` MIME on a PNG payload makes the gallery re-encode or refuse the thumbnail,
 * and a name collision makes the second save overwrite the first. Both are invisible until
 * a user looks for a file that is not there.
 *
 * Naming rules, and why:
 *  - The CDN key is the identity, not the tweet text. It is what stays constant across the
 *    small/large/4096 renderings of one photo ([MediaUrls.photoKey]) and across the
 *    renditions of one video ([MediaUrls.mediaId]), so re-downloading the same media
 *    produces the same name and the caller can detect the duplicate instead of saving it
 *    twice under different timestamps.
 *  - Extension comes from the stored `format` parameter, never from a guess. X serves the
 *    same photo as png or jpg and [MediaUrls.highestQualityPhoto] deliberately preserves
 *    whichever it is; inventing `.jpg` here would undo that.
 *  - Resolution is appended for video because the master ladder makes it meaningful — a
 *    file named `1920x1080` is checkable against what was advertised.
 */
object DownloadTarget {

    enum class Kind { VIDEO, PHOTO }

    data class Spec(
        val kind: Kind,
        /** CDN identity: photo key or video media id. Stable across renditions. */
        val id: String,
        val fileName: String,
        val mimeType: String,
    )

    /** Characters Android's MediaStore tolerates in a display name. */
    private val UNSAFE = Regex("""[^A-Za-z0-9._-]""")

    private val FORMAT_PARAM = Regex("""[?&]format=([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)

    /**
     * Names a photo download from its URL.
     *
     * Expects the URL already normalised by [MediaUrls.highestQualityPhoto] — that is where
     * `format` is settled. Reading `format` here rather than accepting it as a parameter
     * keeps the two from disagreeing.
     */
    fun photoSpec(url: String): Spec? {
        val key = MediaUrls.photoKey(url) ?: return null
        val ext = normaliseExtension(FORMAT_PARAM.find(url)?.groupValues?.get(1) ?: extensionOf(url))
        return Spec(
            kind = Kind.PHOTO,
            id = key,
            fileName = "x_${sanitize(key)}.$ext",
            mimeType = mimeFor(ext),
        )
    }

    /**
     * Names a video download. Resolution is omitted when unknown rather than written as
     * `0x0`, since a master that advertised no RESOLUTION attribute is a real (if rare)
     * case and `x_123_0x0.mp4` reads like a bug.
     */
    fun videoSpec(mediaId: String, width: Int, height: Int): Spec {
        val suffix = if (width > 0 && height > 0) "_${width}x$height" else ""
        return Spec(
            kind = Kind.VIDEO,
            id = mediaId,
            fileName = "x_${sanitize(mediaId)}$suffix.mp4",
            mimeType = "video/mp4",
        )
    }

    private fun extensionOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val dot = path.lastIndexOf('.')
        val slash = path.lastIndexOf('/')
        return if (dot > slash) path.substring(dot + 1) else "jpg"
    }

    private fun normaliseExtension(raw: String): String {
        val ext = raw.lowercase()
        return when (ext) {
            "jpeg" -> "jpg"
            "jpg", "png", "webp", "gif" -> ext
            // An unrecognised format is far more likely to be a URL we mis-parsed than a
            // new X image format, so fall back to the one X actually defaults to.
            else -> "jpg"
        }
    }

    private fun mimeFor(ext: String): String = when (ext) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    private fun sanitize(value: String): String {
        val cleaned = UNSAFE.replace(value, "_")
        return if (cleaned.isEmpty()) "unknown" else cleaned
    }
}
