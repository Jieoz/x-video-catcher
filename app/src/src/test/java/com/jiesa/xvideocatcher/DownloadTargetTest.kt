package com.jiesa.xvideocatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for file naming and MIME typing.
 *
 * These matter because both failure modes are silent: a wrong MIME makes the gallery refuse or
 * re-encode the file, and an unstable name turns a re-download into a second copy instead of a
 * detected duplicate. Neither shows up as an error.
 */
class DownloadTargetTest {

    @Test
    fun `photo name is stable across renditions of the same image`() {
        val large = DownloadTarget.photoSpec(
            MediaUrls.highestQualityPhoto("https://pbs.twimg.com/media/HOkVIBvWwAEXXw7?format=jpg&name=large")
        )
        val tiny = DownloadTarget.photoSpec(
            MediaUrls.highestQualityPhoto("https://pbs.twimg.com/media/HOkVIBvWwAEXXw7?format=webp&name=tiny")
        )
        assertEquals(large!!.fileName, tiny!!.fileName)
        assertEquals("x_HOkVIBvWwAEXXw7.jpg", large.fileName)
    }

    /**
     * webp is dropped by [MediaUrls.highestQualityPhoto] because it is a display-time transcode,
     * so the saved file must be declared as the jpg it actually is.
     */
    @Test
    fun `webp thumbnail is saved as jpeg`() {
        val spec = DownloadTarget.photoSpec(
            MediaUrls.highestQualityPhoto("https://pbs.twimg.com/media/HOkVIBvWwAEXXw7?format=webp&name=tiny")
        )
        assertEquals("image/jpeg", spec!!.mimeType)
        assertEquals("x_HOkVIBvWwAEXXw7.jpg", spec.fileName)
    }

    @Test
    fun `png keeps its format and mime`() {
        val spec = DownloadTarget.photoSpec(
            MediaUrls.highestQualityPhoto("https://pbs.twimg.com/media/HOmXCrJbQAA4YJp?format=png&name=large")
        )
        assertEquals("x_HOmXCrJbQAA4YJp.png", spec!!.fileName)
        assertEquals("image/png", spec.mimeType)
    }

    @Test
    fun `photo with extension instead of format parameter`() {
        val spec = DownloadTarget.photoSpec(
            MediaUrls.highestQualityPhoto("https://pbs.twimg.com/media/HN3SvIgawAA7t4A.png")
        )
        assertEquals("x_HN3SvIgawAA7t4A.png", spec!!.fileName)
        assertEquals("image/png", spec.mimeType)
    }

    @Test
    fun `non photo url yields no spec`() {
        assertNull(DownloadTarget.photoSpec("https://video.twimg.com/amplify_video/1/pl/a.m3u8"))
    }

    @Test
    fun `video name carries resolution`() {
        val spec = DownloadTarget.videoSpec("2082862956648513536", 1080, 1920)
        assertEquals("x_2082862956648513536_1080x1920.mp4", spec.fileName)
        assertEquals("video/mp4", spec.mimeType)
    }

    /** `0x0` in a filename reads like a bug, so an unknown resolution is omitted instead. */
    @Test
    fun `video name omits unknown resolution`() {
        assertEquals("x_123.mp4", DownloadTarget.videoSpec("123", 0, 0).fileName)
    }

    @Test
    fun `different videos get different names`() {
        assertNotEquals(
            DownloadTarget.videoSpec("111", 720, 1280).fileName,
            DownloadTarget.videoSpec("222", 720, 1280).fileName,
        )
    }

    /** Nothing that reaches MediaStore may contain a path separator. */
    @Test
    fun `names are filesystem safe`() {
        val spec = DownloadTarget.videoSpec("../../etc/passwd", 720, 1280)
        assertEquals("x_.._.._etc_passwd_720x1280.mp4", spec.fileName)
    }
}
