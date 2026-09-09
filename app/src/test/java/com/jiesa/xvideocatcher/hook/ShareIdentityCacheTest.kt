package com.jiesa.xvideocatcher.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity selection for the injected row.
 *
 * Why this is a test and not a comment: the 20260829 device log timed the share sheet's state
 * constructor running 13 times for one sheet open, and pre-1.51 code did a full
 * `queryIntentActivities` sweep plus a `loadIcon` per candidate inside each of those. The user's
 * report was visible lag, so how the identity is chosen — and that the same input always yields the
 * same answer — is a product property rather than an implementation detail.
 */
class ShareIdentityCacheTest {

    private class FakeDrawable : android.graphics.drawable.Drawable() {
        override fun draw(canvas: android.graphics.Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("framework abstract")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }

    private fun candidates(n: Int) = List(n) {
        HostRow.ShareIdentity("pkg$it", "act$it", FakeDrawable())
    }

    @Test
    fun `pickFree skips identities already on the sheet`() {
        val all = candidates(4)
        val occupied = setOf("pkg0" to "act0", "pkg1" to "act1")

        val picked = HostRow.pickFree(all, occupied)

        assertNotNull(picked)
        assertEquals("pkg2", picked!!.packageName)
        // Reusing an identity the sheet already carries is what made 1.14-1.17's rows disappear:
        // Compose deduplicates by key, so the second row with that key is never composed.
        assertTrue((picked.packageName to picked.activityName) !in occupied)
    }

    @Test
    fun `pickFree returns null rather than a duplicate when every identity is taken`() {
        val all = candidates(2)
        val occupied = setOf("pkg0" to "act0", "pkg1" to "act1")

        // A null makes the injector log `no free ResolveInfo` and leave the sheet alone. Returning a
        // taken identity instead would silently produce a row that never renders, which is the
        // failure mode that took four releases to spot.
        assertEquals(null, HostRow.pickFree(all, occupied))
    }

    @Test
    fun `pickFree is stable across calls with the same input`() {
        val all = candidates(4)
        val occupied = setOf("pkg0" to "act0")

        val first = HostRow.pickFree(all, occupied)
        val second = HostRow.pickFree(all, occupied)

        // The sheet state is rebuilt many times per open and each rebuild asks again. An unstable
        // answer would give successive states different keys for the same row, which Compose reads as
        // a different row: the entry would flicker or duplicate mid-animation.
        assertSame(first, second)
    }

    @Test
    fun `duplicate candidates collapse so a repeated identity is never handed out twice`() {
        val dup = HostRow.ShareIdentity("pkg0", "act0", FakeDrawable())
        val all = listOf(dup, dup, HostRow.ShareIdentity("pkg1", "act1", FakeDrawable()))

        assertEquals("pkg0", HostRow.pickFree(all, emptySet())!!.packageName)
        assertEquals("pkg1", HostRow.pickFree(all, setOf("pkg0" to "act0"))!!.packageName)
    }
}
