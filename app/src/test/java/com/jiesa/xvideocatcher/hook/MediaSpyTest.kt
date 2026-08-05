package com.jiesa.xvideocatcher.hook

import androidx.media3.datasource.BuilderDecoy
import androidx.media3.datasource.DataSpecFixture
import androidx.media3.datasource.ReorderedDecoy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the media-capture anchor that replaced the tweet-object search.
 *
 * ## What these protect
 *
 * 1.5 through 1.11 all failed the same way: they tried to reach media through a tweet object, and the
 * live share path never carries one. [MediaSpy] reads the URL the host's player already resolved
 * instead, anchored on `DataSpec` — resolved by field shape, because R8 renames the class.
 *
 * The decoys here are not hypothetical. `DataSpec.Builder` (`j$a` in 12.13.0-release.0) declares the
 * same nine field types in the same order as `DataSpec` itself, and an ablation over the shipped APK
 * matched **both**. Hooking the builder would capture half-assembled URLs and miss every spec built
 * through the 9-arg constructor — a silent partial capture, which is the failure mode this project
 * has the worst track record with.
 *
 * Each decoy breaks exactly one clause, so removing that clause must turn its test red.
 */
class MediaSpyTest {

    @Test
    fun acceptsTheRealShape() {
        assertTrue(MediaSpy.hasDataSpecShape(DataSpecFixture::class.java))
    }

    /**
     * The clause under test: all fields final.
     *
     * Delete `fields.all { Modifier.isFinal(...) }` from `hasDataSpecShape` and this must fail.
     */
    @Test
    fun rejectsTheBuilder() {
        assertFalse(MediaSpy.hasDataSpecShape(BuilderDecoy::class.java))
    }

    /**
     * The clause under test: field types compared **in order**.
     *
     * Compare them as a set instead and this must fail.
     */
    @Test
    fun rejectsReorderedFields() {
        assertFalse(MediaSpy.hasDataSpecShape(ReorderedDecoy::class.java))
    }

    @Test
    fun rejectsUnrelatedClasses() {
        assertFalse(MediaSpy.hasDataSpecShape(String::class.java))
        assertFalse(MediaSpy.hasDataSpecShape(MediaSpy::class.java))
    }

    // --- classification: delegated to MediaUrls, asserted here so a change there cannot silently
    // --- reclassify what the download row offers.

    @Test
    fun classifiesProgressiveVideo() {
        val url = "https://video.twimg.com/amplify_video/1900000000000000000/vid/avc1/1280x720/abc.mp4"
        assertEquals(MediaSpy.Kind.PROGRESSIVE_MP4, MediaSpy.classify(url))
    }

    @Test
    fun classifiesMasterPlaylist() {
        val url = "https://video.twimg.com/amplify_video/1900000000000000000/pl/xYz-1.m3u8"
        assertEquals(MediaSpy.Kind.HLS_MASTER, MediaSpy.classify(url))
    }

    /**
     * A user-upload master, which carries an extra `pu/` segment.
     *
     * Called out separately because that segment already caused a real misclassification once: a
     * pattern demanding `<id>/pl/` treated every user upload as a variant, leaving the download path
     * with nothing for exactly the videos that matter most.
     */
    @Test
    fun classifiesUserUploadMaster() {
        val url = "https://video.twimg.com/ext_tw_video/1900000000000000000/pu/pl/xYz-1.m3u8"
        assertEquals(MediaSpy.Kind.HLS_MASTER, MediaSpy.classify(url))
    }

    @Test
    fun classifiesVariantPlaylistApartFromMaster() {
        val url = "https://video.twimg.com/amplify_video/1900000000000000000/pl/avc1/1280x720/xYz.m3u8"
        assertEquals(MediaSpy.Kind.HLS_VARIANT, MediaSpy.classify(url))
    }

    /**
     * Avatars, emoji and malformed input must not be offered.
     *
     * Regression cover only. Ablation showed these cases do not exercise the `isInteresting` guard:
     * none of them matches any branch in `classify`, so they return null with or without it. Kept
     * because they are cheap and they document intent, but [rejectsForeignPlaylists] is the test that
     * actually holds the guard in place.
     */
    @Test
    fun ignoresNonTweetMedia() {
        assertNull(MediaSpy.classify("https://pbs.twimg.com/profile_images/123/abc.jpg"))
        assertNull(MediaSpy.classify("https://abs.twimg.com/emoji/v2/72x72/1f600.png"))
        assertNull(MediaSpy.classify("not a url"))
        assertNull(MediaSpy.classify(""))
    }

    /**
     * A playlist on a host unrelated to X must not be captured.
     *
     * This is the clause `isInteresting` exists for, and the only fixture in this suite that proves
     * it. `MediaUrls.isManifest` tests the path only -- `.m3u8` or `/pl/`, no host check -- so with
     * the guard removed an ad or third-party player's playlist classifies as `HLS_VARIANT` and can
     * become what the download row offers. Verified against the real predicates:
     * `interesting=false, manifest=true`.
     *
     * Remove `if (!MediaUrls.isInteresting(url)) return null` from `classify` and this must fail.
     */
    @Test
    fun rejectsForeignPlaylists() {
        assertNull(MediaSpy.classify("https://ads.example.com/promo/pl/track.m3u8"))
    }

    /**
     * Audio-only renditions are part of a stream, not something a user can be handed.
     *
     * Kept as a regression assertion, not as a guard: ablation showed an explicit `isAudioTrack`
     * check in `classify` was not load-bearing, because an audio URL matches no branch and returns
     * null regardless. The load-bearing clause is `isVideoTrack`, covered by
     * [progressiveRequiresAVideoTrack] — this test would stay green even with the classifier's
     * exclusion removed, and saying so here stops it being mistaken for the thing that protects it.
     */
    @Test
    fun ignoresAudioTracks() {
        val url = "https://video.twimg.com/amplify_video/1900000000000000000/aud/mp4a/128000/x.m4s"
        assertNull(MediaSpy.classify(url))
    }

    /**
     * The clause that actually gates progressive video: `MediaUrls.isVideoTrack`.
     *
     * A stream segment: it satisfies `isInteresting` (media host, media path, `.ts`) and matches
     * neither playlist branch nor the photo branch, so the only thing standing between it and being
     * offered as a download is the video-track test. Replace that branch condition with `true` and
     * this must fail.
     *
     * An earlier version of this test used `/vid/notes.txt`, which does not work: `isVideoTrack` is
     * `contains("/vid/") && !isManifest`, so that URL is a video track by the module's own rule and
     * the assertion was simply wrong. The fixture has to fail the clause under test, not the
     * extension check I assumed existed.
     */
    @Test
    fun progressiveRequiresAVideoTrack() {
        assertNull(MediaSpy.classify("https://video.twimg.com/amplify_video/1/seg/part1.ts"))
    }

    // --- ranking

    /**
     * A master playlist outranks a progressive file even though the file is simpler to fetch: the
     * master reaches every resolution, while a progressive URL is whatever the player picked for the
     * current network.
     */
    @Test
    fun prefersMasterOverProgressive() {
        MediaSpy.clear()
        val mp4 = "https://video.twimg.com/amplify_video/1/vid/avc1/640x360/a.mp4"
        val master = "https://video.twimg.com/amplify_video/1/pl/b.m3u8"
        record(mp4)
        record(master)
        assertEquals(master, MediaSpy.best()?.url)
    }

    /** Within one kind, the video the user is watching now wins. */
    @Test
    fun prefersMostRecentWithinAKind() {
        MediaSpy.clear()
        val older = "https://video.twimg.com/amplify_video/1/pl/older.m3u8"
        val newer = "https://video.twimg.com/amplify_video/2/pl/newer.m3u8"
        record(older)
        Thread.sleep(2)
        record(newer)
        assertEquals(newer, MediaSpy.best()?.url)
    }

    /** Photos are captured for diagnostics but must never be what the download row offers. */
    @Test
    fun bestIgnoresPhotos() {
        MediaSpy.clear()
        record("https://pbs.twimg.com/media/AbCdEf.jpg?format=jpg&name=orig")
        assertNull(MediaSpy.best())
    }

    @Test
    fun bestIsNullBeforeAnythingIsSeen() {
        MediaSpy.clear()
        assertNull(MediaSpy.best())
    }

    /**
     * Re-opening the same playlist must not consume the capture budget.
     *
     * HLS playback re-requests the same URL repeatedly; without dedup one video would evict
     * everything else and `best()` would be picking from a single stream's segments.
     */
    @Test
    fun deduplicatesRepeatedUrls() {
        MediaSpy.clear()
        val url = "https://video.twimg.com/amplify_video/1/pl/a.m3u8"
        repeat(5) { record(url) }
        assertEquals(1, MediaSpy.all().size)
    }

    /**
     * Drives the private recorder the way the hook does.
     *
     * Reflection rather than making `record` internal: the production entry point is the constructor
     * hook, and widening visibility for a test would invite a second caller.
     */
    private fun record(url: String) {
        val m = MediaSpy::class.java.getDeclaredMethod("record", String::class.java)
        m.isAccessible = true
        m.invoke(MediaSpy, url)
    }
}
