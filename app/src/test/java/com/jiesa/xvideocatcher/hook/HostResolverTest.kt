package com.jiesa.xvideocatcher.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.fragment.app.FragmentManager
import com.twitter.model.core.TweetWrapper

/**
 * Reproduces the 12.13.0-release.0 layout that broke the module, using local stand-ins.
 *
 * The failure was not "a method got renamed". The recorded controller name pointed at a class that
 * still exists in the release build but is something else entirely — one field, no show method — so
 * `loadClass` succeeded and the module trusted it. These tests encode the distinguishing property
 * (structure, not name) and assert the decoy is rejected in favour of the real class.
 *
 * Stand-ins mirror the real shapes read out of the APK:
 *   decoy   `…legacy.h0` -> 1 field, no void(FragmentManager)
 *   real    `…legacy.e0` -> 15 fields incl. exactly one List + one com.twitter.model.core.* field
 */
class HostResolverTest {

    // ---- host stand-ins ----------------------------------------------------
    //
    // The doubles for FragmentManager and the tweet wrapper live in the host's real packages
    // (`androidx.fragment.app`, `com.twitter.model.core`) as test-only Java sources. HostResolver
    // matches those two by fully-qualified type name, so a double declared here in the test
    // package would never match — and the test would then be asserting a weaker rule than the one
    // that ships.

    /** Shape of the real controller: one List, one tweet field, void show(FragmentManager). */
    @Suppress("unused")
    class RealController {
        @JvmField val items: MutableList<Any> = mutableListOf()
        @JvmField val tweet: TweetWrapper = TweetWrapper()
        @JvmField val flag: Boolean = false
        fun show(fm: FragmentManager) { require(true) { fm } }
    }

    /** Shape of the decoy the old code latched onto: no show method, no list. */
    @Suppress("unused")
    class DecoyController {
        @JvmField val delegate: Any = Any()
        fun accept(t: TweetWrapper) { require(true) { t } }
    }

    /** Second class with void(FragmentManager)+List but no tweet field — must be rejected. */
    @Suppress("unused")
    class UnrelatedDialog {
        @JvmField val items: MutableList<Any> = mutableListOf()
        fun show(fm: FragmentManager) { require(true) { fm } }
    }

    /** Item model stand-in: has the (int, int, String) constructor. */
    @Suppress("unused", "UNUSED_PARAMETER")
    class ItemModel(drawableRes: Int, actionId: Int, title: String) {
        @JvmField val actionId: Int = actionId
    }

    /** Item-model look-alike without the 3-arg ctor — must be rejected. */
    @Suppress("unused", "UNUSED_PARAMETER")
    class NotAnItem(title: String)

    /**
     * Three-arg constructor with the wrong parameter *types*.
     *
     * Needed because a class with too few arguments only proves the arity check works. Ablation
     * showed that relaxing the resolver to "any 3-arg constructor" left the whole suite green,
     * i.e. the type half of the check was asserting nothing until this double existed.
     */
    @Suppress("unused", "UNUSED_PARAMETER")
    class WrongTypesItem(a: String, b: String, c: Int)

    /** Three String parameters — same arity, no int at all. */
    @Suppress("unused", "UNUSED_PARAMETER")
    class AllStringsItem(a: String, b: String, c: String)

    // ---- show-method resolution -------------------------------------------

    @Test
    fun `finds show method by signature not by name`() {
        val m = HostResolver.showMethodOf(RealController::class.java)
        assertNotNull("void(FragmentManager) must be found regardless of its name", m)
        assertEquals("show", m!!.name)
    }

    @Test
    fun `decoy controller yields no show method`() {
        // This is exactly what happened on the device: the class loads, then has no show method.
        assertNull(HostResolver.showMethodOf(DecoyController::class.java))
    }

    @Test
    fun `show method lookup ignores methods with a wrong parameter type`() {
        // accept(TweetWrapper) is void and 1-arg but not a FragmentManager -> must not match.
        assertNull(HostResolver.showMethodOf(DecoyController::class.java))
    }

    // ---- field resolution -------------------------------------------------

    @Test
    fun `list field is found on the real controller`() {
        val f = HostResolver.listField(RealController::class.java)
        assertNotNull(f)
        assertEquals("items", f!!.name)
    }

    @Test
    fun `list field is absent on the decoy`() {
        assertNull(HostResolver.listField(DecoyController::class.java))
    }

    @Test
    fun `tweet field is found by package not by name`() {
        val f = HostResolver.tweetField(RealController::class.java)
        assertNotNull(f)
        assertEquals("tweet", f!!.name)
    }

    @Test
    fun `unrelated dialog has no tweet field so it is not the controller`() {
        // The real APK's other void(FragmentManager)+List class is BaseConversationActionsDialog.
        // The tweet-field requirement is what keeps the resolver from accepting it.
        assertNotNull(HostResolver.showMethodOf(UnrelatedDialog::class.java))
        assertNotNull(HostResolver.listField(UnrelatedDialog::class.java))
        assertNull(
            "tweet field must be the property that disambiguates",
            HostResolver.tweetField(UnrelatedDialog::class.java),
        )
    }

    // ---- constructor resolution -------------------------------------------

    @Test
    fun `entry constructor is found by parameter shape`() {
        val c = HostResolver.entryConstructor(ItemModel::class.java)
        assertNotNull(c)
        assertEquals(3, c!!.parameterTypes.size)
    }

    @Test
    fun `class without the three arg constructor is rejected`() {
        assertNull(HostResolver.entryConstructor(NotAnItem::class.java))
    }

    @Test
    fun `three arg constructor with wrong parameter types is rejected`() {
        // Right arity, wrong types: (String, String, Int) must not be mistaken for
        // (int drawableRes, int actionId, String title). Passing 0, id, "Download" to it would
        // throw inside X's UI thread.
        assertNull(HostResolver.entryConstructor(WrongTypesItem::class.java))
    }

    @Test
    fun `three string constructor is rejected`() {
        assertNull(HostResolver.entryConstructor(AllStringsItem::class.java))
    }

    @Test
    fun `constructor match requires both int params before the string`() {
        // Pins the exact order the host declares, not merely "two ints and a String somewhere".
        val c = HostResolver.entryConstructor(ItemModel::class.java)!!
        assertEquals(Int::class.javaPrimitiveType, c.parameterTypes[0])
        assertEquals(Int::class.javaPrimitiveType, c.parameterTypes[1])
        assertEquals(String::class.java, c.parameterTypes[2])
    }

    @Test
    fun `resolved constructor actually builds an instance`() {
        val c = HostResolver.entryConstructor(ItemModel::class.java)!!
        val obj = c.newInstance(0, 0x5EED_0001, "Download")
        assertTrue(obj is ItemModel)
        assertEquals(0x5EED_0001, (obj as ItemModel).actionId)
    }

    // ---- the full gate ----------------------------------------------------

    /**
     * The whole point: a candidate must pass show + list + tweet together. Applying the three
     * checks in combination is what separates the real controller from both the decoy (which the
     * old code accepted) and the unrelated dialog.
     */
    private fun passesControllerGate(cls: Class<*>): Boolean =
        HostResolver.showMethodOf(cls) != null &&
            HostResolver.listField(cls) != null &&
            HostResolver.tweetField(cls) != null

    @Test
    fun `only the real controller passes the combined gate`() {
        assertTrue("real controller must pass", passesControllerGate(RealController::class.java))
        assertTrue(
            "decoy must be rejected — accepting it is the shipped bug",
            !passesControllerGate(DecoyController::class.java),
        )
        assertTrue(
            "unrelated dialog must be rejected",
            !passesControllerGate(UnrelatedDialog::class.java),
        )
    }

    @Test
    fun `gate is satisfied by exactly one of the three candidates`() {
        val candidates = listOf(
            RealController::class.java,
            DecoyController::class.java,
            UnrelatedDialog::class.java,
        )
        val passing = candidates.filter { passesControllerGate(it) }
        assertEquals("gate must be unique, not merely permissive", 1, passing.size)
        assertSame(RealController::class.java, passing.single())
    }

    @Test
    fun `resolved list field is writable so an entry can be appended`() {
        val controller = RealController()
        val f = HostResolver.listField(RealController::class.java)!!
        @Suppress("UNCHECKED_CAST")
        val list = f.get(controller) as MutableList<Any>
        val item = HostResolver.entryConstructor(ItemModel::class.java)!!
            .newInstance(0, 0x5EED_0001, "Download")
        f.set(controller, list + item)
        @Suppress("UNCHECKED_CAST")
        val after = f.get(controller) as List<Any>
        assertEquals(1, after.size)
        assertEquals(0x5EED_0001, (after.single() as ItemModel).actionId)
    }
}
