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
        assertEquals(MediaSpy.Kind.VIDEO_INIT, MediaSpy.classify(url))
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
     * Progressive outranks a master: downloadCaptured can save the file today, and the 1.12 device
     * log captured both on one playback. Preferring the master left the tap with nothing to write.
     *
     * Flip RANK so master is last and this must fail.
     */
    @Test
    fun prefersMasterOverInitSegment() {
        MediaSpy.clear()
        val init = "https://video.twimg.com/amplify_video/1/vid/avc1/0/0/640x360/a.mp4"
        val master = "https://video.twimg.com/amplify_video/1/pl/b.m3u8"
        record(init)
        record(master)
        // Inverted in 1.19. This test asserted the opposite through 1.18 and is why the bug
        // shipped green: the `.mp4` it preferred is an fMP4 header with no frames, so every save
        // produced a few dozen unplayable KB. Only a master leads to a complete file.
        assertEquals(master, MediaSpy.best()?.url)
    }

    /** An init segment alone is not downloadable: no master means no row, not a broken save. */
    @Test
    fun initSegmentAloneIsNotDownloadable() {
        MediaSpy.clear()
        record("https://video.twimg.com/amplify_video/1/vid/avc1/0/0/1080x1920/only.mp4")
        assertNull(MediaSpy.best())
    }

    /**
     * fMP4 media segments share /vid/ with progressive files. Device 1.12 logged them as
     * VIDEO_INIT because isVideoTrack alone matched; they are not a playable download.
     *
     * Drop the `.mp4` extension check in classify and this must fail.
     */
    @Test
    fun rejectsFragmentedMediaSegments() {
        val m4s = "https://video.twimg.com/amplify_video/1/vid/avc1/0/3000/1080x1920/seg.m4s"
        assertNull(MediaSpy.classify(m4s))
    }

    /**
     * Recency beats resolution across tweets.
     *
     * The regression this pins: 1.18 ranked pixel area above recency, so a 1080x1920 video seen
     * early won every later tap. The device log shows taps on three different tweets all saving
     * one file from the first. Quality is no longer chosen here at all -- it comes from the
     * selected master's own ladder in [Hls.bestVariant].
     */
    @Test
    fun recentTweetBeatsAnEarlierLargerOne() {
        MediaSpy.clear()
        record("https://video.twimg.com/amplify_video/1/vid/avc1/0/0/1080x1920/big.mp4")
        record("https://video.twimg.com/amplify_video/1/pl/big.m3u8")
        Thread.sleep(2)
        record("https://video.twimg.com/amplify_video/2/vid/avc1/0/0/480x852/small.mp4")
        record("https://video.twimg.com/amplify_video/2/pl/small.m3u8")
        assertEquals("https://video.twimg.com/amplify_video/2/pl/small.m3u8", MediaSpy.best()?.url)
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

    /**
     * Within one media group, the freshest master wins.
     *
     * X re-requests the same master with a changing `?tag=` (the device log shows
     * `.../pl/gHnIGEflU4EasiGc.m3u8?tag=14` fetched repeatedly), so one video legitimately produces
     * several capture entries. The oldest is the likeliest to have expired, and an expired master
     * 403s with no retry that can help -- so the newest is the one to hand the downloader.
     *
     * This is the ablation partner for the recency comparator: flip `maxByOrNull` to `minByOrNull`
     * in [MediaSpy.best] and only this test fails. Without it that comparator was untested, because
     * every other case has a single master per group where min and max agree.
     */
    @Test
    fun freshestMasterWinsWithinOneMediaGroup() {
        MediaSpy.clear()
        val stale = "https://video.twimg.com/amplify_video/7/pl/key.m3u8?tag=12"
        val fresh = "https://video.twimg.com/amplify_video/7/pl/key.m3u8?tag=14"
        record(stale)
        Thread.sleep(2)
        record(fresh)
        assertEquals(fresh, MediaSpy.best()?.url)
    }

    /**
     * The master must match the video being *watched*, not merely be the newest master seen.
     *
     * This is the shape the device log actually produced. Scrolling the timeline makes X prefetch
     * masters for tweets that were never played: at 16:11:03-04 the capture contains masters for
     * four different media ids while the segments still arriving belong to the one on screen. So
     * "newest master" and "the video the user is looking at" are different answers, and only the
     * second is correct.
     *
     * Ablation partner for the `newestId` grouping clause: replace it with null -- so any master
     * can win -- and only this test fails. The cross-tweet test above cannot catch it, because
     * there the newest capture and the newest master are the same video.
     */
    @Test
    fun masterMustBelongToTheVideoBeingWatched() {
        MediaSpy.clear()
        val watched = "https://video.twimg.com/amplify_video/100/pl/watched.m3u8"
        record(watched)
        Thread.sleep(2)
        // Prefetched while scrolling: newer master, different tweet, never played.
        record("https://video.twimg.com/amplify_video/200/pl/prefetched.m3u8")
        Thread.sleep(2)
        // A segment of the watched video arrives last -- this is what identifies the group.
        record("https://video.twimg.com/amplify_video/100/vid/avc1/0/0/720x1280/init.mp4")
        assertEquals(watched, MediaSpy.best()?.url)
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
