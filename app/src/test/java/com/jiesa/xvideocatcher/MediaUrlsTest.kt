package com.jiesa.xvideocatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe hooks sit on X's network hot path, so the filter has to be correct and
 * cheap. Cases marked "captured" are verbatim from a real probe run on Jay's device,
 * not invented — a hand-written sample set shares the author's assumptions with the
 * implementation and would agree with a wrong regex just as happily.
 */
class MediaUrlsTest {

    // --- what must pass ---------------------------------------------------------

    @Test
    fun `captured master playlist is interesting`() {
        assertTrue(
            MediaUrls.isInteresting(
                "https://video.twimg.com/amplify_video/2079090871887396864/pl/fuUhtbdKLhYlx018.m3u8"
            )
        )
    }

    @Test
    fun `captured variant playlist, init segment and media segment are interesting`() {
        assertTrue(
            MediaUrls.isInteresting(
                "https://video.twimg.com/amplify_video/2083449067250884608/pl/avc1/1920x1080/dRCgPaC3VUbgwRPd.m3u8"
            )
        )
        assertTrue(
            MediaUrls.isInteresting(
                "https://video.twimg.com/amplify_video/2083449067250884608/vid/avc1/0/0/1920x1080/pckUTCZRL0jS4qXO.mp4"
            )
        )
        assertTrue(
            MediaUrls.isInteresting(
                "https://video.twimg.com/amplify_video/2079090871887396864/vid/avc1/0/3000/720x960/sNY6Pd3epW4yk56-.m4s"
            )
        )
    }

    @Test
    fun `captured audio track is interesting`() {
        // Audio is a separate track; dropping it would silently produce silent videos.
        assertTrue(
            MediaUrls.isInteresting(
                "https://video.twimg.com/amplify_video/2079090871887396864/aud/mp4a/0/3000/128000/Jmbmt6BhgTd2j_w-.m4s"
            )
        )
    }

    @Test
    fun `user upload and gif paths are interesting`() {
        // Not yet seen in a capture — amplify_video was all Jay's session produced —
        // so these keep the other two kinds from regressing unnoticed.
        assertTrue(
            MediaUrls.isInteresting(
                "https://video.twimg.com/ext_tw_video/1234/pu/vid/720x1280/abcd.mp4"
            )
        )
        assertTrue(MediaUrls.isInteresting("https://video.twimg.com/tweet_video/abcd.mp4"))
    }

    @Test
    fun `media path on an unknown cdn host still passes`() {
        assertTrue(MediaUrls.isInteresting("https://cdn.example.net/amplify_video/999/vid/a.mp4"))
    }

    // --- what must be dropped --------------------------------------------------

    @Test
    fun `robots txt on the video host is dropped`() {
        // Regression guard: matching the host alone logged this on every launch.
        assertFalse(MediaUrls.isInteresting("https://video.twimg.com/robots.txt"))
    }

    @Test
    fun `graphql, telemetry and profile images are dropped`() {
        assertFalse(MediaUrls.isInteresting("https://x.com/i/api/graphql/abc/TweetDetail"))
        assertFalse(MediaUrls.isInteresting("https://pbs.twimg.com/profile_images/1/avatar.jpg"))
        assertFalse(MediaUrls.isInteresting("https://api.x.com/1.1/jot/client_event.json"))
    }

    @Test
    fun `non http and empty inputs are dropped`() {
        assertFalse(MediaUrls.isInteresting(""))
        assertFalse(MediaUrls.isInteresting("file:///data/user/0/com.twitter.android/cache/a.mp4"))
        assertFalse(MediaUrls.isInteresting("content://media/external/video/1"))
    }

    // --- master playlist, the download entry point -----------------------------

    @Test
    fun `master playlist is distinguished from variant playlist`() {
        assertTrue(
            MediaUrls.isMasterPlaylist(
                "https://video.twimg.com/amplify_video/2079090871887396864/pl/fuUhtbdKLhYlx018.m3u8"
            )
        )
        // A variant carries one resolution only; treating it as the master would lock
        // the download to whatever quality the player happened to be using.
        assertFalse(
            MediaUrls.isMasterPlaylist(
                "https://video.twimg.com/amplify_video/2079090871887396864/pl/avc1/320x426/FeOGjnOvQazWy3dr.m3u8"
            )
        )
        assertFalse(
            MediaUrls.isMasterPlaylist(
                "https://video.twimg.com/amplify_video/2079090871887396864/pl/mp4a/128000/_62BD5_OuRmsPpp3.m3u8"
            )
        )
    }

    @Test
    fun `master playlist with a query string is still recognised`() {
        // Captured with ?tag=14 — an anchored match would have missed it.
        assertTrue(
            MediaUrls.isMasterPlaylist(
                "https://video.twimg.com/amplify_video/2076928325608726528/pl/r57orE4eCEYiIEDR.m3u8?tag=14"
            )
        )
    }

    @Test
    fun `manifest is distinguished from segment`() {
        assertTrue(MediaUrls.isManifest("https://video.twimg.com/a/pl/720/x.m3u8"))
        assertFalse(
            MediaUrls.isManifest("https://video.twimg.com/ext_tw_video/1/vid/720x1280/a.mp4")
        )
    }

    // --- grouping and quality selection ----------------------------------------

    @Test
    fun `media id groups the segments of one video`() {
        assertEquals(
            "2079090871887396864",
            MediaUrls.mediaId(
                "https://video.twimg.com/amplify_video/2079090871887396864/vid/avc1/0/3000/720x960/x.m4s"
            ),
        )
        assertNull(MediaUrls.mediaId("https://video.twimg.com/robots.txt"))
    }

    @Test
    fun `resolution is read from variant and segment urls`() {
        assertEquals(
            1920 to 1080,
            MediaUrls.resolution(
                "https://video.twimg.com/amplify_video/2083449067250884608/vid/avc1/0/0/1920x1080/p.mp4"
            ),
        )
        // Audio has no resolution — must not be mistaken for a video variant.
        assertNull(
            MediaUrls.resolution(
                "https://video.twimg.com/amplify_video/2079090871887396864/aud/mp4a/0/3000/128000/J.m4s"
            )
        )
    }

    @Test
    fun `highest resolution wins, from the real variant set of one captured video`() {
        // Every resolution X requested for media id 2079090871887396864 while adapting.
        val captured = listOf(
            "https://video.twimg.com/amplify_video/2079090871887396864/pl/avc1/320x426/a.m3u8",
            "https://video.twimg.com/amplify_video/2079090871887396864/pl/avc1/480x640/b.m3u8",
            "https://video.twimg.com/amplify_video/2079090871887396864/pl/avc1/720x960/c.m3u8",
        )

        assertEquals(
            "https://video.twimg.com/amplify_video/2079090871887396864/pl/avc1/720x960/c.m3u8",
            MediaUrls.highestResolution(captured),
        )
    }

    @Test
    fun `portrait and landscape are ranked by pixel count, not height`() {
        // Both shapes appear in one capture. Ranking by height alone would call
        // 720x1280 better than 1920x1080, which is wrong by a wide margin.
        assertEquals(
            "https://video.twimg.com/amplify_video/1/pl/avc1/1920x1080/a.m3u8",
            MediaUrls.highestResolution(
                listOf(
                    "https://video.twimg.com/amplify_video/1/pl/avc1/720x1280/b.m3u8",
                    "https://video.twimg.com/amplify_video/1/pl/avc1/1920x1080/a.m3u8",
                )
            ),
        )
    }

    @Test
    fun `highest resolution ignores urls that carry none`() {
        assertNull(MediaUrls.highestResolution(listOf("https://video.twimg.com/a/pl/x.m3u8")))
        assertEquals(
            "https://video.twimg.com/amplify_video/1/pl/avc1/480x640/v.m3u8",
            MediaUrls.highestResolution(
                listOf(
                    "https://video.twimg.com/amplify_video/1/pl/mp4a/128000/a.m3u8",
                    "https://video.twimg.com/amplify_video/1/pl/avc1/480x640/v.m3u8",
                )
            ),
        )
    }

    @Test
    fun `audio and video tracks are told apart`() {
        val aud = "https://video.twimg.com/amplify_video/1/aud/mp4a/0/3000/128000/a.m4s"
        val vid = "https://video.twimg.com/amplify_video/1/vid/avc1/0/3000/720x960/v.m4s"

        assertTrue(MediaUrls.isAudioTrack(aud))
        assertFalse(MediaUrls.isVideoTrack(aud))
        assertTrue(MediaUrls.isVideoTrack(vid))
        assertFalse(MediaUrls.isAudioTrack(vid))
    }

    // --- photos -----------------------------------------------------------------
    //
    // Photos come from a different host than video and carry quality in a query
    // parameter instead of the path, so they need their own cases rather than an
    // extension of the video ones.

    @Test
    fun `tweet photos and video posters are interesting`() {
        assertTrue(
            MediaUrls.isInteresting("https://pbs.twimg.com/media/Go6lFkVWsAAQwOo?format=jpg&name=small")
        )
        assertTrue(MediaUrls.isInteresting("https://pbs.twimg.com/media/Go6lFkVWsAAQwOo.jpg"))
        assertTrue(
            MediaUrls.isInteresting("https://pbs.twimg.com/tweet_video_thumb/GpOC7WQagAA3LSc.jpg")
        )
        assertTrue(
            MediaUrls.isInteresting(
                "https://pbs.twimg.com/ext_tw_video_thumb/2083509343769792512/pu/img/abc.jpg"
            )
        )
    }

    /**
     * Scrolling a timeline fetches hundreds of avatars, emoji and card previews. They
     * are on the photo host and would sail through a host-only check, burying the
     * photos the user actually wants — the same failure mode as the robots.txt noise.
     */
    @Test
    fun `avatars, banners, emoji and card previews are not photos to save`() {
        assertFalse(
            MediaUrls.isInteresting("https://pbs.twimg.com/profile_images/1234567890/a_normal.jpg")
        )
        assertFalse(
            MediaUrls.isInteresting("https://pbs.twimg.com/profile_banners/12345/1600000000/1500x500")
        )
        assertFalse(
            MediaUrls.isInteresting("https://pbs.twimg.com/card_img/1234567890/abc?format=jpg")
        )
        assertFalse(
            MediaUrls.isInteresting("https://pbs.twimg.com/semantic_core_img/1234/abcd?format=jpg")
        )
        assertFalse(MediaUrls.isInteresting("https://abs-0.twimg.com/emoji/v2/svg/1f600.svg"))
    }

    @Test
    fun `photos are told apart from video`() {
        assertTrue(MediaUrls.isPhoto("https://pbs.twimg.com/media/KEY?format=jpg&name=small"))
        assertFalse(MediaUrls.isPhoto("https://video.twimg.com/amplify_video/1/pl/k.m3u8"))
    }

    @Test
    fun `photo key is stable across renderings of one image`() {
        assertEquals(
            "Go6lFkVWsAAQwOo",
            MediaUrls.photoKey("https://pbs.twimg.com/media/Go6lFkVWsAAQwOo?format=jpg&name=small"),
        )
        assertEquals(
            "Go6lFkVWsAAQwOo",
            MediaUrls.photoKey("https://pbs.twimg.com/media/Go6lFkVWsAAQwOo?format=jpg&name=orig"),
        )
        assertEquals(
            "Go6lFkVWsAAQwOo",
            MediaUrls.photoKey("https://pbs.twimg.com/media/Go6lFkVWsAAQwOo.jpg"),
        )
    }

    /**
     * The point of the rewrite: whichever size the timeline loaded, the download must
     * land on the full image. Captures show X asking only for `large` and `tiny`.
     *
     * The target is `4096x4096`, not `orig`, and that is a measured choice — see
     * [photo rewrite never asks for orig, which 404s on png photos].
     */
    @Test
    fun `photo url is rewritten to the largest size`() {
        assertEquals(
            "https://pbs.twimg.com/media/KEY?format=jpg&name=4096x4096",
            MediaUrls.highestQualityPhoto("https://pbs.twimg.com/media/KEY?format=jpg&name=large"),
        )
        assertEquals(
            "https://pbs.twimg.com/media/KEY?format=jpg&name=4096x4096",
            MediaUrls.highestQualityPhoto("https://pbs.twimg.com/media/KEY?format=jpg&name=tiny"),
        )
        assertEquals(
            "https://pbs.twimg.com/media/KEY?format=jpg&name=4096x4096",
            MediaUrls.highestQualityPhoto("https://pbs.twimg.com/media/KEY"),
        )
    }

    /**
     * `name=orig` is what every guide recommends and it is wrong here. Measured against
     * the captured photos, `format=jpg&name=orig` returns **404** for photos stored as
     * PNG, while `4096x4096` answered 200 for every captured photo in both formats and
     * was byte-identical to `orig` on the 8 JPEGs where `orig` did work.
     */
    @Test
    fun `photo rewrite never asks for orig, which 404s on png photos`() {
        val out = MediaUrls.highestQualityPhoto(
            "https://pbs.twimg.com/media/HOmXCrJbQAA4YJp?format=png&name=large"
        )
        assertFalse("orig 404s when format does not match storage", out.contains("name=orig"))
        assertTrue(out.contains("name=4096x4096"))
    }

    /**
     * Forcing jpg onto a PNG photo re-encodes it: a captured photo went from 358430 to
     * 29817 bytes that way. The stored format has to survive the rewrite.
     */
    @Test
    fun `png photos keep their format instead of being forced to jpg`() {
        assertEquals(
            "https://pbs.twimg.com/media/HOmXCrJbQAA4YJp?format=png&name=4096x4096",
            MediaUrls.highestQualityPhoto(
                "https://pbs.twimg.com/media/HOmXCrJbQAA4YJp?format=png&name=large"
            ),
        )
    }

    /**
     * webp is a display-time transcode X requests for thumbnails, not a stored format —
     * the same captured photos were also fetched as jpg, which comes back 45% larger
     * (275762 vs 360662 bytes). It gets replaced rather than preserved.
     */
    @Test
    fun `webp is replaced with jpg rather than preserved`() {
        assertEquals(
            "https://pbs.twimg.com/media/HOorBqIb0AAvMim?format=jpg&name=4096x4096",
            MediaUrls.highestQualityPhoto(
                "https://pbs.twimg.com/media/HOorBqIb0AAvMim?format=webp&name=tiny"
            ),
        )
    }

    /**
     * Dropping webp must not leave `format` off: `?name=4096x4096` with no format 404s
     * on every captured photo, so the parameter always has to be present.
     */
    @Test
    fun `rewritten photo url always carries a format`() {
        val inputs = listOf(
            "https://pbs.twimg.com/media/KEY?format=webp&name=tiny",
            "https://pbs.twimg.com/media/KEY?name=large",
            "https://pbs.twimg.com/media/KEY",
            "https://pbs.twimg.com/media/KEY.jpg",
        )
        for (u in inputs) {
            assertTrue("no format= in rewrite of $u", MediaUrls.highestQualityPhoto(u).contains("format="))
        }
    }

    /** A path extension is the stored format, so it is folded in rather than dropped. */
    @Test
    fun `photo extension is folded into the format parameter`() {
        assertEquals(
            "https://pbs.twimg.com/media/KEY?format=jpg&name=4096x4096",
            MediaUrls.highestQualityPhoto("https://pbs.twimg.com/media/KEY.jpg"),
        )
        assertEquals(
            "https://pbs.twimg.com/media/KEY?format=png&name=4096x4096",
            MediaUrls.highestQualityPhoto("https://pbs.twimg.com/media/KEY.png"),
        )
    }

    /**
     * Video posters use the extension form. Captured live, the rewritten form returns a
     * larger image than the `.jpg` X requested (720x1280 vs 675x1200).
     */
    @Test
    fun `captured video poster is rewritten to the query form`() {
        assertEquals(
            "https://pbs.twimg.com/amplify_video_thumb/2082864630440091648/img/4fwDG9dTpjBt-i6j" +
                "?format=jpg&name=4096x4096",
            MediaUrls.highestQualityPhoto(
                "https://pbs.twimg.com/amplify_video_thumb/2082864630440091648/img/4fwDG9dTpjBt-i6j.jpg"
            ),
        )
    }

    @Test
    fun `existing format is kept when replacing the size`() {
        assertEquals(
            "https://pbs.twimg.com/media/KEY?format=png&name=4096x4096",
            MediaUrls.highestQualityPhoto("https://pbs.twimg.com/media/KEY?name=large&format=png"),
        )
    }

    @Test
    fun `rewriting is idempotent`() {
        val once = MediaUrls.highestQualityPhoto("https://pbs.twimg.com/media/KEY?format=jpg&name=small")
        assertEquals(once, MediaUrls.highestQualityPhoto(once))
    }

    /** Callers apply the rewrite blindly, so non-photos must come back untouched. */
    @Test
    fun `rewriting leaves video urls alone`() {
        val master = "https://video.twimg.com/amplify_video/1/pl/k.m3u8"
        assertEquals(master, MediaUrls.highestQualityPhoto(master))
    }

    // --- ext_tw_video: the `pu/` path segment -----------------------------------

    /**
     * User uploads insert `pu/` between the id and the track. A pattern demanding
     * `<id>/pl/` labelled all three captured user-upload masters as variants, which
     * would have left the download path with nothing to start from for exactly the
     * videos worth downloading. Captured verbatim.
     */
    @Test
    fun `captured ext_tw_video master is recognised despite the pu segment`() {
        assertTrue(
            MediaUrls.isMasterPlaylist(
                "https://video.twimg.com/ext_tw_video/2083304240613888000/pu/pl/bhU45nXNm7ekYLia.m3u8?tag=12"
            )
        )
        assertTrue(
            MediaUrls.isMasterPlaylist(
                "https://video.twimg.com/ext_tw_video/2082862956648513536/pu/pl/ZS2EucO3WQS6lxwX.m3u8?tag=12"
            )
        )
        assertTrue(
            MediaUrls.isMasterPlaylist(
                "https://video.twimg.com/ext_tw_video/2028482401027162112/pu/pl/eiovNvtmSr1WmOOd.m3u8?tag=12"
            )
        )
    }

    /** `pu/` must not make variants look like masters either. Captured verbatim. */
    @Test
    fun `captured ext_tw_video variants are still not masters`() {
        assertFalse(
            MediaUrls.isMasterPlaylist(
                "https://video.twimg.com/ext_tw_video/2083304240613888000/pu/pl/avc1/720x1280/1dw-tz0anED11q1v.m3u8"
            )
        )
        assertFalse(
            MediaUrls.isMasterPlaylist(
                "https://video.twimg.com/ext_tw_video/2083304240613888000/pu/pl/mp4a/32000/y9JpY5FLkXGUD1BL.m3u8"
            )
        )
    }

    @Test
    fun `captured ext_tw_video tracks and id are parsed`() {
        assertEquals(
            "2082862956648513536",
            MediaUrls.mediaId(
                "https://video.twimg.com/ext_tw_video/2082862956648513536/pu/vid/avc1/0/3000/720x960/XIZ3A1koUydYzKLc.m4s"
            ),
        )
        assertTrue(
            MediaUrls.isAudioTrack(
                "https://video.twimg.com/ext_tw_video/2082862956648513536/pu/aud/mp4a/0/3000/128000/UOxAcxY4zRwQi3XA.m4s"
            )
        )
        assertTrue(
            MediaUrls.isVideoTrack(
                "https://video.twimg.com/ext_tw_video/2082862956648513536/pu/vid/avc1/0/3000/720x960/XIZ3A1koUydYzKLc.m4s"
            )
        )
    }

    /**
     * A captured final segment ends at 6162, not a 3000 multiple, and one variant ladder
     * contained 606x1078 — neither fits a fixed-step assumption, so resolution handling
     * must read the value rather than match known sizes.
     */
    @Test
    fun `off-ladder resolutions are read rather than matched`() {
        assertEquals(606 to 1078, MediaUrls.resolution(
            "https://video.twimg.com/amplify_video/1/vid/avc1/0/3000/606x1078/k.m4s"))
        assertEquals(
            "https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/6000/6162/720x960/k.m4s",
            MediaUrls.highestResolution(
                listOf(
                    "https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/0/3000/320x568/k.m4s",
                    "https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/6000/6162/720x960/k.m4s",
                )
            ),
        )
    }
}
