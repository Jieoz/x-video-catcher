package com.jiesa.xvideocatcher.hook

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for building a share-sheet row.
 *
 * ## Device failure this suite must keep red-on-regression
 *
 * 1.13 on device: `INJECT row clone failed from com.x.models.share.a` three times per share.
 * That class is all-final, no int id, no usable `clone()` / no-arg ctor. A suite that only
 * exercised a mutable Java bean never saw the failure.
 *
 * Each decoy / positive case below breaks one clause of the live path.
 */
class HostRowTest {

    private val icon: Drawable = ColorDrawable(0xFF112233.toInt())

    /** Live shape: final fields, primary ctor, no int id — [com.x.models.share.a]. */
    @Test
    fun `constructs a final data-class row with new label and unique activity`() {
        val template = com.x.models.share.a(
            "com.whatsapp",
            "com.whatsapp.contact.ui.picker.ExternalShareAlias",
            "WhatsApp",
            icon,
            true,
        )
        val copy = HostRow.cloneWithLabel(template, 0x58564331, "下载视频")
        assertNotNull("final data-class row must clone via constructor", copy)
        assertNotSame(template, copy)
        assertTrue(copy is com.x.models.share.a)

        val row = copy as com.x.models.share.a
        assertEquals("下载视频", row.label)
        assertEquals("com.whatsapp", row.packageName)
        assertEquals(
            "activity must be unique so Compose does not key-collide with the template",
            HostRow.MODULE_ACTIVITY,
            row.activityName,
        )
        assertEquals(icon, row.icon)
        assertEquals(true, row.direct)

        // Template is a live host object — never mutate it.
        assertEquals("com.whatsapp", template.packageName)
        assertEquals("WhatsApp", template.label)
    }

    @Test
    fun `labelOf reads the display string off a live-shaped row`() {
        val row = com.x.models.share.a(
            "org.telegram.messenger",
            "org.telegram.ui.LaunchActivity",
            "Telegram",
            icon,
            false,
        )
        assertEquals("Telegram", HostRow.labelOf(row))
    }

    @Test
    fun `package-like strings are not chosen as the label`() {
        // package and activity are longer than some labels and full of dots. Choosing longest
        // String outright would write the download text into the package slot.
        val template = com.x.models.share.a(
            "com.very.long.package.name.that.outruns.label",
            "com.very.long.activity.ComponentNameAlias",
            "Save",
            icon,
            false,
        )
        val copy = HostRow.cloneWithLabel(template, 1, "Download video") as com.x.models.share.a
        assertEquals("Download video", copy.label)
        assertEquals("com.very.long.package.name.that.outruns.label", copy.packageName)
        assertEquals(HostRow.MODULE_ACTIVITY, copy.activityName)
    }

    @Test
    fun `chinese labels without spaces still win over package fields`() {
        val template = com.x.models.share.a(
            "com.tencent.mm",
            "com.tencent.mm.ui.tools.ShareImgUI",
            "发送给朋友",
            icon,
            true,
        )
        val copy = HostRow.cloneWithLabel(template, 1, "下载视频") as com.x.models.share.a
        assertEquals("下载视频", copy.label)
        assertEquals("com.tencent.mm", copy.packageName)
        assertEquals(HostRow.MODULE_ACTIVITY, copy.activityName)
        assertEquals("发送给朋友", HostRow.labelOf(template))
    }

    @Test
    fun `injected activity differs from every template in a typical sheet`() {
        // Reproduces the 1.15 crash shape: cloning WhatsApp with the same activity collides.
        val templates = listOf(
            com.x.models.share.a("com.whatsapp", "com.whatsapp.contact.ui.picker.ExternalShareAlias", "WhatsApp", icon, true),
            com.x.models.share.a("org.telegram.messenger", "org.telegram.ui.LaunchActivity", "Telegram", icon, false),
        )
        val injected = HostRow.cloneWithLabel(templates[0], 1, "下载视频") as com.x.models.share.a
        val keys = templates.map { it.packageName to it.activityName }.toSet()
        assertTrue(
            "injected (package, activity) must not collide with any host row",
            (injected.packageName to injected.activityName) !in keys,
        )
    }

    // ---- mutable fallback (unit-test bean; not the live host model) -------------------------

    private class MutableRow(
        @JvmField var id: Int = 7,
        @JvmField var style: Int = 2,
        @JvmField var label: String? = "Share via",
        @JvmField var scribeElement: String? = "tweet.actionsheet.share_via_click",
        @JvmField var scribeSection: String? = "tweet_actionsheet",
        @JvmField var enabled: Boolean = true,
    )

    @Test
    fun `mutable fallback still replaces id and label`() {
        val template = MutableRow()
        val copy = HostRow.cloneWithLabel(template, 0x58564331, "Download video")
        assertNotNull(copy)
        val row = copy as MutableRow
        assertEquals(0x58564331, row.id)
        assertEquals("Download video", row.label)
        assertEquals("tweet.actionsheet.share_via_click", row.scribeElement)
        assertEquals(7, template.id)
    }

    @Test
    fun `a scribe key longer than the label is not mistaken for it on mutable rows`() {
        val template = MutableRow(label = "Save", scribeElement = "tweet.actionsheet.save_media_click")
        val copy = HostRow.cloneWithLabel(template, 1, "Download video") as MutableRow
        assertEquals("Download video", copy.label)
        assertEquals("tweet.actionsheet.save_media_click", copy.scribeElement)
    }

    @Test
    fun `all-scribe mutable row cannot be labelled`() {
        class AllScribe(
            @JvmField var id: Int = 1,
            @JvmField var a: String? = "tweet.actionsheet.a_click",
            @JvmField var b: String? = "tweet_actionsheet",
        )
        assertNull(HostRow.cloneWithLabel(AllScribe(), 1, "Download video"))
    }
}
