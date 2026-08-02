package com.jiesa.xvideocatcher.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for resolving the item model — the entry the module constructs and hands to the host.
 *
 * The host renders a row by reading fields off its own item type, so an injected entry has to *be*
 * one of those objects; the `(int drawableRes, int actionId, String title)` constructor is the
 * minimal shape that produces a complete row.
 *
 * The controller tests that used to live here are gone with the controller path itself. 1.2 and 1.3
 * resolved a tweet-action controller by shape, correctly, and the share panel still showed no entry:
 * the class was never on the share panel's path. Bind-point resolution is covered in
 * [BindPointTest].
 */
class HostResolverTest {

    private val loader = javaClass.classLoader!!

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

    // ---- item model resolution --------------------------------------------

    @Test
    fun `item model resolves from the host package`() {
        val cls = HostResolver.actionSheetItem(loader)
        assertNotNull("item model must resolve", cls)
        assertEquals("com.twitter.ui.dialog.actionsheet.b", cls!!.name)
    }

    // ---- constructor resolution -------------------------------------------

    @Test
    fun `entry constructor is found by parameter shape`() {
        val c = HostResolver.entryConstructor(HostResolver.actionSheetItem(loader)!!)
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
        val c = HostResolver.entryConstructor(HostResolver.actionSheetItem(loader)!!)!!
        assertEquals(Int::class.javaPrimitiveType, c.parameterTypes[0])
        assertEquals(Int::class.javaPrimitiveType, c.parameterTypes[1])
        assertEquals(String::class.java, c.parameterTypes[2])
    }

    @Test
    fun `resolved constructor actually builds an instance`() {
        val c = HostResolver.entryConstructor(HostResolver.actionSheetItem(loader)!!)!!
        val obj = c.newInstance(0, 0x5EED_0001, "Download")
        assertTrue(obj is com.twitter.ui.dialog.actionsheet.b)
        assertEquals(0x5EED_0001, (obj as com.twitter.ui.dialog.actionsheet.b).actionId)
    }
}
