package com.jiesa.xvideocatcher.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for cloning a share-sheet row.
 *
 * ## Why this is tested at all
 *
 * The failure mode here is *visual and silent*: a row whose label went into the wrong String field
 * renders as a blank or wrongly-labelled entry, and nothing throws. That cannot be caught by a
 * device log, so it has to be caught here.
 *
 * The host row on 12.13.0-release.0 carries four Strings -- a label plus analytics scribe names --
 * and 11 fields in total. [HostRow] picks the label as the longest non-scribe String rather than by
 * field name, and these tests pin that choice, including the case where a scribe key is longer than
 * the label (which is common: `tweet.actionsheet.download_click` beats `Save`).
 */
class HostRowTest {

    /** A row shaped like the host's: an id, a style int, a label, and scribe keys. */
    private class Row(
        @JvmField var id: Int = 7,
        @JvmField var style: Int = 2,
        @JvmField var label: String? = "Share via",
        @JvmField var scribeElement: String? = "tweet.actionsheet.share_via_click",
        @JvmField var scribeSection: String? = "tweet_actionsheet",
        @JvmField var enabled: Boolean = true,
    )

    @Test
    fun `clone replaces id and label and leaves other fields alone`() {
        val template = Row()
        val copy = HostRow.cloneWithLabel(template, 0x58564331, "Download video")
        assertNotNull("clone must succeed", copy)
        assertNotSame("clone must be a new object", template, copy)
        assertTrue("clone must be the template's class", copy is Row)

        val row = copy as Row
        assertEquals(0x58564331, row.id)
        assertEquals("Download video", row.label)
        // Inherited, so the injected row looks native without this file knowing what these are.
        assertEquals("tweet.actionsheet.share_via_click", row.scribeElement)
        assertEquals("tweet_actionsheet", row.scribeSection)
        assertEquals(true, row.enabled)

        // The template must be untouched: it is a live object the host still renders.
        assertEquals(7, template.id)
        assertEquals("Share via", template.label)
    }

    @Test
    fun `a scribe key longer than the label is not mistaken for it`() {
        // The real hazard. "Save" is 4 chars; the scribe element is 33. Choosing the longest String
        // outright would put the download label into an analytics field and leave the row blank.
        val template = Row(label = "Save", scribeElement = "tweet.actionsheet.save_media_click")
        val copy = HostRow.cloneWithLabel(template, 1, "Download video") as Row
        assertEquals("Download video", copy.label)
        assertEquals(
            "scribe key must be preserved, not overwritten",
            "tweet.actionsheet.save_media_click",
            copy.scribeElement,
        )
    }

    @Test
    fun `the clone does not keep the template's id`() {
        // The bug this defends against: a clone that kept the template's id would be dispatched by
        // the host as its OWN row, so tapping "Download video" would fire Share via instead. Silent,
        // and wrong in a way the user sees.
        //
        // This replaced a test asserting "a row with no int field cannot be cloned", which ablation
        // showed could never fail: setInt on a String field throws regardless of any guard, so that
        // behaviour is guaranteed by reflection rather than by this code, and a test for it measures
        // the JDK. Only assertions that can go red for a code change belong here.
        val template = Row(id = 7)
        val copy = HostRow.cloneWithLabel(template, 99, "Download video") as Row
        assertEquals("id must be overwritten, not inherited", 99, copy.id)
        assertEquals("template must be untouched", 7, template.id)
    }

    @Test
    fun `a row with no usable label field cannot be cloned`() {
        // Every String is a scribe key, so there is nowhere to put visible text.
        class AllScribe(
            @JvmField var id: Int = 1,
            @JvmField var a: String? = "tweet.actionsheet.a_click",
            @JvmField var b: String? = "tweet_actionsheet",
        )
        assertNull(HostRow.cloneWithLabel(AllScribe(), 1, "Download video"))
    }

    @Test
    fun `blank labels are not chosen`() {
        // An empty String is a real occurrence on optional rows. Writing the label into it would
        // leave the visible field untouched and the row unlabelled.
        val template = Row(label = "Copy link", scribeElement = "")
        val copy = HostRow.cloneWithLabel(template, 1, "Download video") as Row
        assertEquals("Download video", copy.label)
        assertEquals("", copy.scribeElement)
    }
}
