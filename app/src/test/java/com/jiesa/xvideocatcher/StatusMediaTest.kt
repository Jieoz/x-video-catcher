package com.jiesa.xvideocatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Status-keyed media resolution. Fixtures are real HTTP payloads (2026-08), no network
 * at test time. 1.25 adds vxtwitter/fxtwitter after syndication tombstones recent posts.
 */
class StatusMediaTest {

    @Test
    fun parseSyndicationPhoto() {
        val r = StatusMedia.parseSyndication(fixture("syndication_photo_1349129669258448897.json"), "1349129669258448897")
        assertNotNull(r)
        assertTrue(r!!.photos.any { it.contains("ErkSSFgW4AMKude") })
        assertTrue(r.videos.isEmpty())
    }

    @Test
    fun parseSyndicationVideoPrefersProgressiveMp4() {
        val r = StatusMedia.parseSyndication(fixture("syndication_video_1732824684683784516.json"), "1732824684683784516")
        assertNotNull(r)
        val v = r!!.videos.single()
        assertEquals("1732820284301058052", v.mediaId)
        assertNotNull(v.progressiveUrl)
        assertTrue(v.progressiveUrl!!.contains(".mp4"))
        assertTrue(v.progressiveUrl!!.contains("1920x1080") || (v.width * v.height >= 1280 * 720))
        assertNotNull(v.masterUrl)
        assertTrue(MediaUrls.isMasterPlaylist(v.masterUrl!!))
    }

    @Test
    fun syndicationTombstoneIsNull() {
        assertNull(StatusMedia.parseSyndication(fixture("syndication_tombstone.json"), "2084245524690211165"))
        assertTrue(StatusMedia.isTombstone(fixture("syndication_tombstone.json")))
    }

    @Test
    fun parseVxVideoRecentTombstonedOnSyndication() {
        val r = StatusMedia.parseVxTwitter(fixture("vxtwitter_video_2084245524690211165.json"), "2084245524690211165")
        assertNotNull(r)
        assertEquals(1, r!!.videos.size)
        val v = r.videos.single()
        assertEquals("2084243487395086336", v.mediaId)
        assertNotNull(v.progressiveUrl)
        assertTrue(v.progressiveUrl!!.contains("1920x1080"))
        assertTrue(r.photos.isEmpty())
    }

    @Test
    fun parseVxPhoto() {
        val r = StatusMedia.parseVxTwitter(fixture("vxtwitter_photo_2085685769625366632.json"), "2085685769625366632")
        assertNotNull(r)
        assertTrue(r!!.photos.any { it.contains("HPHYHt2aEAAhJyT") })
        assertTrue(r.videos.isEmpty())
    }

    @Test
    fun parseVxClassicVideo() {
        val r = StatusMedia.parseVxTwitter(fixture("vxtwitter_video_1732824684683784516.json"), "1732824684683784516")
        assertNotNull(r)
        assertEquals("1732820284301058052", r!!.videos.single().mediaId)
    }

    @Test
    fun rejectsIdMismatch() {
        assertNull(StatusMedia.parseSyndication(fixture("syndication_photo_1349129669258448897.json"), "999"))
        assertNull(StatusMedia.parseVxTwitter(fixture("vxtwitter_video_2084245524690211165.json"), "999"))
    }

    @Test
    fun rejectsNonStatusId() {
        assertNull(StatusMedia.resolve("not-a-status"))
    }

    @Test
    fun endpointsIncludeFallbacks() {
        val eps = StatusMedia.endpoints("1732824684683784516")
        assertEquals(listOf("syndication", "vxtwitter", "fxtwitter"), eps.map { it.first })
        assertTrue(eps[0].second.contains("cdn.syndication.twimg.com"))
        assertTrue(eps[1].second.contains("api.vxtwitter.com"))
        assertTrue(eps[2].second.contains("api.fxtwitter.com"))
    }

    // ---- 1.53: sources are raced, preference order is applied to the results ----------------
    //
    // The device cost this fixes: syndication tombstones every status posted after ~2026-04 (five
    // real ids measured) and still charged 1.36 s of the user's wait before vxtwitter was contacted.

    /**
     * The whole point: three sources, one source's worth of wall time.
     *
     * Asserted as elapsed time rather than by counting threads, because the wait is the thing the
     * user experiences. Each fetch sleeps 300 ms; serial would be >=900 ms.
     */
    @Test
    fun sourcesAreFetchedConcurrently() {
        val sources = StatusMedia.endpoints("1732824684683784516")
        val started = System.nanoTime()
        val bodies = StatusMedia.fetchAll(sources) { url ->
            Thread.sleep(300)
            "body-for-$url"
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(3, bodies.size)
        assertTrue(
            "three 300ms fetches must overlap, took ${elapsedMs}ms",
            elapsedMs < 750,
        )
    }

    /**
     * Racing must not silently demote the first-party source.
     *
     * The mirror answers instantly and syndication takes 250 ms; the result must still be
     * syndication's, because execution order and preference order are now separate concerns.
     */
    @Test
    fun preferenceOrderSurvivesASlowerFirstPartyAnswer() {
        val sources = StatusMedia.endpoints("1349129669258448897")
        val bodies = StatusMedia.fetchAll(sources) { url ->
            when {
                url.contains("cdn.syndication.twimg.com") -> {
                    Thread.sleep(250)
                    fixture("syndication_photo_1349129669258448897.json")
                }
                else -> "{}"
            }
        }
        val first = sources.map { it.first }
            .firstNotNullOfOrNull { name ->
                bodies[name]?.takeIf { it != "{}" }?.let { name to it }
            }
        assertEquals("syndication", first!!.first)
        val parsed = StatusMedia.parseFor("syndication", first.second, "1349129669258448897")
        assertNotNull(parsed)
        assertTrue(parsed!!.photos.any { it.contains("ErkSSFgW4AMKude") })
    }

    /** A source that throws must not remove the others' answers from the result. */
    @Test
    fun oneFailingSourceDoesNotLoseTheOthers() {
        val sources = StatusMedia.endpoints("2085685769625366632")
        val bodies = StatusMedia.fetchAll(sources) { url ->
            if (url.contains("cdn.syndication.twimg.com")) error("boom")
            fixture("vxtwitter_photo_2085685769625366632.json")
        }
        assertFalse("a throwing source must be absent", bodies.containsKey("syndication"))
        assertTrue(bodies.containsKey("vxtwitter"))
        assertTrue(bodies.containsKey("fxtwitter"))
    }

    /**
     * Every source name [endpoints] offers must have a parser.
     *
     * `parseFor` is a `when` on a string, so a new endpoint added without its branch would return
     * null for a body that arrived fine — a miss that reads as "the source had nothing".
     */
    @Test
    fun everyEndpointNameHasAParser() {
        val bodies = mapOf(
            "syndication" to fixture("syndication_photo_1349129669258448897.json"),
            "vxtwitter" to fixture("vxtwitter_photo_2085685769625366632.json"),
            "fxtwitter" to fixture("vxtwitter_photo_2085685769625366632.json"),
        )
        for ((name, _) in StatusMedia.endpoints("1")) {
            assertTrue(
                "endpoint '$name' has no fixture in this test, so it has no parser coverage",
                bodies.containsKey(name),
            )
        }
        // And the dispatch actually reaches a parser rather than falling through to null.
        assertNotNull(
            StatusMedia.parseFor(
                "syndication",
                bodies["syndication"]!!,
                "1349129669258448897",
            ),
        )
        assertNull(StatusMedia.parseFor("no-such-source", bodies["syndication"]!!, "1"))
    }

    // Keep old names used in earlier 1.24 tests if any external ref — aliases:
    @Test
    fun parsePhotoStatus() = parseSyndicationPhoto()

    @Test
    fun parseVideoStatusPrefersProgressiveMp4() = parseSyndicationVideoPrefersProgressiveMp4()

    @Test
    fun endpointPointsAtSyndication() {
        val u = StatusMedia.endpoint("1732824684683784516")
        assertTrue(u.contains("cdn.syndication.twimg.com/tweet-result"))
    }

    private fun fixture(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name")
            ?: error("missing fixture $name")
        return stream.readBytes().toString(StandardCharsets.UTF_8)
    }
}
