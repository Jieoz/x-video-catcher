package com.jiesa.xvideocatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playlist parsing, against the shapes the 12.13 device capture actually served.
 *
 * The master below is the real structure of
 * `https://video.twimg.com/amplify_video/<id>/pl/<key>.m3u8`: absolute-path variant URIs, a
 * separate `#EXT-X-MEDIA` audio rendition, and a `CODECS` attribute containing a comma.
 */
class HlsTest {

    private val masterUrl = "https://video.twimg.com/amplify_video/2084996598422245376/pl/gHnIGEflU4EasiGc.m3u8?tag=14"

    private val master = """
        #EXTM3U
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Audio",DEFAULT=YES,URI="/amplify_video/2084996598422245376/pl/mp4a/128000/abc.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=432000,CODECS="avc1.4d001f,mp4a.40.2",RESOLUTION=320x568,AUDIO="aud"
        /amplify_video/2084996598422245376/pl/avc1/320x568/CiiZwwMhoNJmUerE.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2176000,CODECS="avc1.4d001f,mp4a.40.2",RESOLUTION=720x1280,AUDIO="aud"
        /amplify_video/2084996598422245376/pl/avc1/720x1280/nj--zwsnLjskBp6x.m3u8
    """.trimIndent()

    @Test
    fun `parses variants and resolves absolute paths against the master host`() {
        val m = Hls.parseMaster(master, masterUrl)
        assertEquals(2, m.variants.size)
        assertEquals(
            "https://video.twimg.com/amplify_video/2084996598422245376/pl/avc1/720x1280/nj--zwsnLjskBp6x.m3u8",
            m.variants[1].url,
        )
        assertEquals(720, m.variants[1].width)
        assertEquals(2176000L, m.variants[1].bandwidth)
    }

    @Test
    fun `codecs comma does not split the attribute list`() {
        val attrs = Hls.attributes("""BANDWIDTH=100,CODECS="avc1.4d001f,mp4a.40.2",RESOLUTION=320x568""")
        assertEquals("320x568", attrs["RESOLUTION"])
        assertEquals("avc1.4d001f,mp4a.40.2", attrs["CODECS"])
    }

    @Test
    fun `best variant is highest pixel area`() {
        val best = Hls.bestVariant(Hls.parseMaster(master, masterUrl))
        assertEquals(720 to 1280, best!!.width to best.height)
    }

    @Test
    fun `audio rendition is found via the variant group`() {
        val m = Hls.parseMaster(master, masterUrl)
        val audio = Hls.audioFor(m, Hls.bestVariant(m))
        assertEquals("aud", audio!!.groupId)
        assertTrue(audio.url.endsWith("/pl/mp4a/128000/abc.m3u8"))
    }

    @Test
    fun `audio-only rendition is not selectable as video`() {
        // A master whose only STREAM-INF lacks RESOLUTION must yield no variant, rather than
        // offering a soundtrack as the video to save.
        val audioOnly = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"
            /amplify_video/1/pl/mp4a/128000/abc.m3u8
        """.trimIndent()
        assertNull(Hls.bestVariant(Hls.parseMaster(audioOnly, masterUrl)))
    }

    @Test
    fun `media playlist yields init segment then ordered segments`() {
        val variantUrl = "https://video.twimg.com/amplify_video/1/pl/avc1/720x1280/v.m3u8"
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION=3
            #EXT-X-MAP:URI="/amplify_video/1/vid/avc1/0/0/720x1280/init.mp4"
            #EXTINF:3.000,
            /amplify_video/1/vid/avc1/0/3000/720x1280/a.m4s
            #EXTINF:3.000,
            /amplify_video/1/vid/avc1/3000/6000/720x1280/b.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = Hls.parseMedia(media, variantUrl)
        assertTrue(p.initUrl!!.endsWith("/0/0/720x1280/init.mp4"))
        assertEquals(2, p.segments.size)
        assertTrue(p.segments[0].endsWith("/a.m4s"))
        assertTrue(p.segments[1].endsWith("/b.m4s"))
    }

    @Test
    fun `playlist without EXT-X-MAP reports no init segment`() {
        // The header carries the moov box; without it the concatenated segments are undecodable,
        // which is the class of file 1.13-1.18 shipped. Callers must treat null as failure.
        val p = Hls.parseMedia("#EXTM3U\n/amplify_video/1/vid/avc1/0/3000/720x1280/a.m4s", masterUrl)
        assertNull(p.initUrl)
    }
}
