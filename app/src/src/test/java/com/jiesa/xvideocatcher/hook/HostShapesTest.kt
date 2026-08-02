package com.jiesa.xvideocatcher.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the shape-based host lookups against stand-ins built to X 12.13's actual structure.
 *
 * These fakes are not invented shapes. Each mirrors what was read out of the host's dex:
 * field counts, field types and declaration order all match, only the names differ — which is
 * precisely the axis obfuscation changes and therefore the axis these lookups must not depend on.
 *
 * What this protects: a rename in X must leave the module working. A test that used the real
 * names would pass while the production path fails, so the fakes here deliberately use *different*
 * names than the recorded ones.
 */
class HostShapesTest {

    // --- stand-ins for host classes ------------------------------------

    /** Mirrors `com.twitter.media.av.model.a0`: one int (bitrate) plus String fields. */
    @Suppress("unused")
    private class FakeVariant(
        @JvmField val bitrateLike: Int,
        @JvmField val urlLike: String,
        @JvmField val typeLike: String,
    )

    /** Mirrors `com.twitter.media.av.model.z`: two floats + exactly one List. */
    @Suppress("unused")
    private class FakeVideoInfo(
        @JvmField val aspectW: Float,
        @JvmField val aspectH: Float,
        @JvmField val renditions: List<Any>,
    )

    /** Mirrors `entity.b0$d`: enum whose constant names survive obfuscation. */
    private enum class FakeMediaType { UNKNOWN, IMAGE, VIDEO, ANIMATED_GIF, MODEL3D }

    /** Mirrors `entity.b0`: media type enum + video info + assorted ids and strings. */
    @Suppress("unused")
    private class FakeMediaEntity(
        @JvmField val someId: Long,
        @JvmField val mediaType: FakeMediaType,
        @JvmField val videoInfo: FakeVideoInfo?,
        @JvmField val mediaUrl: String,
    )

    /** A class with two Lists, to prove "unique field of type" really means unique. */
    @Suppress("unused")
    private class TwoLists(
        @JvmField val a: List<Any>,
        @JvmField val b: List<Any>,
    )

    // --- media type ----------------------------------------------------

    @Test
    fun `media type field is found by enum constants not by name`() {
        val field = HostShapes.mediaTypeField(FakeMediaEntity::class.java)
        assertNotNull("enum carrying VIDEO and IMAGE should be recognised", field)
        assertEquals("mediaType", field!!.name)
    }

    @Test
    fun `unrelated enum is not mistaken for the media type`() {
        class OtherEnumHolder(@JvmField val mode: Thread.State = Thread.State.NEW)
        assertNull(HostShapes.mediaTypeField(OtherEnumHolder::class.java))
    }

    // --- video info ----------------------------------------------------

    @Test
    fun `video info field is found by its two-float-one-list shape`() {
        val field = HostShapes.videoInfoField(FakeMediaEntity::class.java)
        assertNotNull("2 floats + 1 List identifies video info", field)
        assertEquals("videoInfo", field!!.name)
    }

    @Test
    fun `variants list is the single list on video info`() {
        val field = HostShapes.variantsField(FakeVideoInfo::class.java)
        assertNotNull(field)
        assertEquals("renditions", field!!.name)
    }

    @Test
    fun `ambiguous list is refused rather than guessed`() {
        // Two Lists means the structural claim "the variants list is the only List" no longer
        // holds. Returning either one would be a coin flip that saves a wrong file.
        assertNull(HostShapes.variantsField(TwoLists::class.java))
    }

    // --- variant reading -----------------------------------------------

    @Test
    fun `variant url and bitrate are read without knowing field names`() {
        val v = FakeVariant(
            bitrateLike = 2176000,
            urlLike = "https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/1280x720/x.mp4",
            typeLike = "video/mp4",
        )
        val read = HostShapes.readVariant(v)
        assertNotNull(read)
        assertEquals(2176000, read!!.bitrate)
        assertEquals(
            "https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/1280x720/x.mp4",
            read.url,
        )
        assertEquals("video/mp4", read.contentType)
    }

    @Test
    fun `content type is not mistaken for the url`() {
        // Both values contain '/', which is why the url is identified by scheme instead. Swapping
        // the constructor order simulates the host reordering its fields.
        val v = FakeVariant(
            bitrateLike = 832000,
            urlLike = "application/x-mpegURL",
            typeLike = "https://video.twimg.com/a/vid/640x360/y.mp4",
        )
        val read = HostShapes.readVariant(v)
        assertNotNull(read)
        assertEquals("https://video.twimg.com/a/vid/640x360/y.mp4", read!!.url)
        assertEquals("application/x-mpegURL", read.contentType)
    }

    @Test
    fun `variant with no url yields null`() {
        class NoUrl(@JvmField val bitrate: Int = 100, @JvmField val label: String = "sd")
        assertNull(HostShapes.readVariant(NoUrl()))
    }

    // --- field-by-type -------------------------------------------------

    @Test
    fun `unique field of type searches superclasses`() {
        open class Parent(@JvmField val items: List<Any> = emptyList())
        class Child(@JvmField val flag: Boolean = false) : Parent()

        val field = HostShapes.uniqueFieldOfType(Child::class.java, List::class.java)
        assertNotNull("a field inherited from the host's parent class must still be found", field)
        assertEquals("items", field!!.name)
    }
}
