package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.StatusMedia

/**
 * Cross-hook session state for the share-sheet download path.
 *
 * [activeTweetId] is the **status** id from the sheet arg URL
 * (`https://x.com/i/status/<id>`). status ≠ media ≠ photoKey.
 *
 * 1.23 froze [MediaSpy.best] at panel open. Device log 2026-08-08: every open still
 * downloaded a neighbouring timeline capture because focus/harvest never bound to the
 * status being shared (`HARVEST photos=0`, FREEZE on unrelated photoKey/mediaId).
 *
 * 1.24 freezes the **status id** and resolves media via [StatusMedia] (syndication for
 * that id). [MediaSpy] is fallback only when no status id is on the sheet.
 */
internal object DownloaderState {
    @Volatile var activeTweetId: String? = null

    @Volatile
    var frozenResolved: StatusMedia.Resolved? = null

    /** Legacy capture freeze — only used when the sheet has no status id. */
    @Volatile
    var frozenHits: List<MediaSpy.Seen>? = null

    /**
     * Photo key the user was viewing when share opened. null = no specific photo
     * (video tweet, or focus lost). When set, only that photo is downloaded.
     */
    @Volatile
    var frozenPhotoKey: String? = null

    fun freezeStatus(statusId: String, photoKey: String? = null) {
        activeTweetId = statusId
        frozenPhotoKey = photoKey
        // Drop a previous status's resolution so a re-open cannot download the last tweet.
        if (frozenResolved?.statusId != statusId) {
            frozenResolved = null
        }
        frozenHits = null
    }

    fun freezeResolved(resolved: StatusMedia.Resolved) {
        activeTweetId = resolved.statusId
        frozenResolved = resolved
        frozenHits = null
    }

    fun freeze(hits: List<MediaSpy.Seen>) {
        frozenHits = hits.toList()
    }

    fun clearFreeze() {
        frozenResolved = null
        frozenHits = null
        frozenPhotoKey = null
    }

    fun targetHits(tweetId: String? = activeTweetId): List<MediaSpy.Seen> {
        frozenHits?.takeIf { it.isNotEmpty() }?.let { return it }
        return MediaSpy.best(tweetId)
    }
}
