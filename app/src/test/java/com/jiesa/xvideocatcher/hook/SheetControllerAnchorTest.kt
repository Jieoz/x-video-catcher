package com.jiesa.xvideocatcher.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the tweet-action-sheet anchor, the path 1.11.0 injects into.
 *
 * ## What these do and do not cover
 *
 * Reachability is *not* covered here and cannot be: a unit test runs against fixtures, and the
 * question "does the shipped app ever call this" is only answerable over the real APK. That half is
 * `tools/verify_host_anchors.py` plus its ablation, wired into CI. Versions 1.2-1.4 died precisely
 * because shape checks like these passed against dead code.
 *
 * What these cover is the other half: that the predicate identifies the controller **uniquely**, and
 * that every clause in it is load-bearing. Each decoy breaks exactly one clause, so deleting that
 * clause from [HostResolver.isSheetControllerShape] must turn the matching test red.
 *
 * Fixture member names differ from the host's on purpose (`rows` not `a`, `showSheet` not `h`), so a
 * resolver that has drifted back to hardcoded names cannot pass.
 */
class SheetControllerAnchorTest {

    private val loader = javaClass.classLoader!!

    @Test
    fun `sheet controller resolves uniquely in its package`() {
        val cls = HostResolver.sheetController(loader)
        assertNotNull("sheet controller must resolve", cls)
        assertEquals("com.twitter.tweet.action.legacy.e0", cls!!.name)
    }

    @Test
    fun `the real controller matches the shape`() {
        assertTrue(
            HostResolver.isSheetControllerShape(
                com.twitter.tweet.action.legacy.e0::class.java,
            ),
        )
    }

    @Test
    fun `controller with two lists is rejected`() {
        // Decoy a1: tweet + show method, but two Lists. Which list the sheet renders would be a
        // guess, and a wrong guess is silent -- the row lands in a list nothing displays.
        assertFalse(
            "two-List decoy must not match",
            HostResolver.isSheetControllerShape(
                com.twitter.tweet.action.legacy.a1::class.java,
            ),
        )
    }

    @Test
    fun `controller without a tweet is rejected`() {
        // Decoy b1: the shape of any dialog helper. Without the tweet clause the injector would
        // hook a sheet with nothing to download.
        assertFalse(
            "tweet-less decoy must not match",
            HostResolver.isSheetControllerShape(
                com.twitter.tweet.action.legacy.b1::class.java,
            ),
        )
    }

    @Test
    fun `controller that cannot show a sheet is rejected`() {
        // Decoy c1: rows and a tweet, no way to display them -- a view-model. Hooking it would
        // install cleanly and never fire: the 1.2-1.4 failure mode exactly.
        assertFalse(
            "view-model decoy must not match",
            HostResolver.isSheetControllerShape(
                com.twitter.tweet.action.legacy.c1::class.java,
            ),
        )
    }

    @Test
    fun `a non-void FragmentManager method is not the show method`() {
        // Decoy d1: (FragmentManager) -> String is a factory or a lookup, not the command that
        // renders the sheet. Hooking it `before` would fire before the rows are assembled.
        assertFalse(
            "non-void decoy must not match",
            HostResolver.isSheetControllerShape(
                com.twitter.tweet.action.legacy.d1::class.java,
            ),
        )
        assertNull(
            HostResolver.sheetShowMethod(com.twitter.tweet.action.legacy.d1::class.java),
        )
    }

    @Test
    fun `the show method is found by signature not by name`() {
        val m = HostResolver.sheetShowMethod(
            com.twitter.tweet.action.legacy.e0::class.java,
        )
        assertNotNull("show method must resolve", m)
        // The fixture calls it `showSheet`; the host calls it `h`. Asserting the fixture's name here
        // is the point: it proves the lookup is by signature, so the host's `h` is found without
        // this file knowing that letter.
        assertEquals("showSheet", m!!.name)
        assertEquals(Void.TYPE, m.returnType)
    }
}
