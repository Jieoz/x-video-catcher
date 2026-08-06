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
 * No Robolectric: this host has no aarch64 conscrypt (see DiagSinkTest). PackageManager is
 * exercised only through [HostRow.pickFree], which is the load-bearing selection predicate.
 */
class HostRowTest {

    private val icon: Drawable = ColorDrawable(0xFF112233.toInt())

    @Test
    fun `constructs a final data-class row with label rewrite`() {
        val template = com.x.models.share.a(
            "com.whatsapp",
            "com.whatsapp.contact.ui.picker.ExternalShareAlias",
            "WhatsApp",
            icon,
            true,
        )
        val copy = HostRow.cloneWithLabel(template, 0x58564331, "下载视频")
        assertNotNull(copy)
        assertNotSame(template, copy)
        val row = copy as com.x.models.share.a
        assertEquals("下载视频", row.label)
        assertEquals("com.whatsapp", row.packageName)
        assertEquals("com.whatsapp.contact.ui.picker.ExternalShareAlias", row.activityName)
    }

    @Test
    fun `occupiedKeys reads package and activity from live-shaped rows`() {
        val rows = listOf(
            com.x.models.share.a("com.whatsapp", "com.whatsapp.A", "WhatsApp", icon, true),
            com.x.models.share.a(
                "org.telegram.messenger",
                "org.telegram.ui.LaunchActivity",
                "Telegram",
                icon,
                false,
            ),
        )
        assertEquals(
            setOf(
                "com.whatsapp" to "com.whatsapp.A",
                "org.telegram.messenger" to "org.telegram.ui.LaunchActivity",
            ),
            HostRow.occupiedKeys(rows),
        )
    }

    @Test
    fun `pickFree skips identities already on the sheet`() {
        val candidates = listOf(
            HostRow.ShareIdentity("com.whatsapp", "com.whatsapp.A", icon),
            HostRow.ShareIdentity("org.telegram.messenger", "org.telegram.ui.LaunchActivity", icon),
        )
        val free = HostRow.pickFree(candidates, setOf("com.whatsapp" to "com.whatsapp.A"))
        assertNotNull(free)
        assertEquals("org.telegram.messenger", free!!.packageName)
    }

    @Test
    fun `pickFree returns null when every match is occupied`() {
        val candidates = listOf(HostRow.ShareIdentity("com.whatsapp", "com.whatsapp.A", icon))
        assertNull(HostRow.pickFree(candidates, setOf("com.whatsapp" to "com.whatsapp.A")))
    }

    @Test
    fun `constructWithIdentity uses free package not the template`() {
        val template = com.x.models.share.a(
            "com.whatsapp",
            "com.whatsapp.A",
            "WhatsApp",
            icon,
            true,
        )
        val identity = HostRow.ShareIdentity(
            "com.discord",
            "com.discord.share.ShareActivity",
            ColorDrawable(0xFF00FF00.toInt()),
        )
        val copy = HostRow.constructWithIdentity(template, "下载视频", identity)
        assertNotNull(copy)
        val row = copy as com.x.models.share.a
        assertEquals("下载视频", row.label)
        assertEquals("com.discord", row.packageName)
        assertEquals("com.discord.share.ShareActivity", row.activityName)
        assertTrue(
            (row.packageName to row.activityName) !in
                HostRow.occupiedKeys(listOf(template)),
        )
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
