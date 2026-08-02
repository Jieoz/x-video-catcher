package com.jiesa.xvideocatcher.hook

import com.twitter.app.common.dialog.ClickContract
import com.twitter.model.core.TweetWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the bind-point anchor that replaced the controller hook.
 *
 * ## What broke, and what these assert
 *
 * 1.3.0 hooked a tweet-action controller and its `show(FragmentManager)`. Device logs showed both
 * hooks installing and never firing when the share panel opened, because that controller drives a
 * different surface. The anchor is now the bind method every sheet must pass through.
 *
 * The property that makes this subtle, and the reason the fixtures look the way they do: the share
 * panel's ViewHolder (`app.share.ui.d`) **overrides the bind method without calling super** — read
 * off the real bytecode with a disassembler. So a resolver returning a single bind point, or an
 * injector hooking only the base class, reproduces the shipped bug while looking correct. The
 * fixture `d` extends `f` and omits the super call precisely so that mistake fails here.
 *
 * Fixtures live in the host's real packages as test-only Java sources, because the resolver matches
 * fully-qualified names; doubles declared in the test package would assert a weaker rule than the
 * one that ships. Their *method* names differ from the real build's (`bindSheet` vs `n0`) so a
 * hardcoded name cannot pass.
 */
class BindPointTest {

    private val loader = javaClass.classLoader!!

    // ---- bind point resolution --------------------------------------------

    @Test
    fun `finds both declaring classes, not just the base`() {
        val points = HostResolver.bindPoints(loader)
        val owners = points.map { it.method.declaringClass.name }.toSet()

        assertTrue(
            "base ViewHolder must be found, got $owners",
            owners.contains("com.twitter.ui.dialog.actionsheet.f"),
        )
        assertTrue(
            "share panel override must be found too — hooking only the base is the 1.3.0 bug, " +
                "got $owners",
            owners.contains("com.twitter.app.share.ui.d"),
        )
    }

    @Test
    fun `bind method is found by signature not by name`() {
        val points = HostResolver.bindPoints(loader)
        assertTrue("expected at least one bind point", points.isNotEmpty())
        for (p in points) {
            assertEquals("bindSheet", p.method.name)
            assertEquals(2, p.method.parameterTypes.size)
            assertEquals(ClickContract::class.java, p.method.parameterTypes[1])
        }
    }

    @Test
    fun `resolved sheet model is the type holding the item list`() {
        val points = HostResolver.bindPoints(loader)
        for (p in points) {
            assertEquals("com.twitter.ui.dialog.actionsheet.h", p.sheetModel.name)
            assertNotNull(
                "sheet model must expose an item list",
                HostResolver.listFieldOf(p.sheetModel),
            )
        }
    }

    @Test
    fun `rejects a two arg void method whose first param holds no list`() {
        // Fixture q.bindSheet(p, contract): p has no List field. Accepting it would install a hook
        // on a method with nothing to append to — a silent failure, not a loud one.
        val owners = HostResolver.bindPoints(loader).map { it.method.declaringClass.name }
        assertTrue(
            "decoy q must be rejected, got $owners",
            !owners.contains("com.twitter.ui.dialog.actionsheet.q"),
        )
    }

    @Test
    fun `rejects a two arg void method whose second param is not the contract`() {
        // Fixture r.bindSheet(h, String): takes a real sheet model, wrong callback type.
        val owners = HostResolver.bindPoints(loader).map { it.method.declaringClass.name }
        assertTrue(
            "decoy r must be rejected, got $owners",
            !owners.contains("com.twitter.ui.dialog.actionsheet.r"),
        )
    }

    @Test
    fun `accepts exactly the two real binders and no decoy`() {
        val owners = HostResolver.bindPoints(loader).map { it.method.declaringClass.name }.toSet()
        assertEquals(
            "gate must be unique, not merely permissive",
            setOf("com.twitter.ui.dialog.actionsheet.f", "com.twitter.app.share.ui.d"),
            owners,
        )
    }

    @Test
    fun `the share panel override really does not call super`() {
        // Guards the fixture itself. If someone "tidies" d.bindSheet by adding a super call, the
        // multi-hook requirement stops being tested and this suite would go green while the real
        // host still needs two hooks.
        val sheet = com.twitter.ui.dialog.actionsheet.h()
        val panel = com.twitter.app.share.ui.d()
        panel.bindSheet(sheet, null)

        assertTrue("override must have run", panel.ownBindRan)
        assertNull(
            "override must NOT delegate to the base — that is why both need hooking",
            panel.boundSheet,
        )
    }

    // ---- item list --------------------------------------------------------

    @Test
    fun `item list field is found among non-list fields`() {
        val f = HostResolver.listFieldOf(com.twitter.ui.dialog.actionsheet.h::class.java)
        assertNotNull(f)
        assertEquals("g", f!!.name)
    }

    @Test
    fun `class without a list field yields none`() {
        assertNull(HostResolver.listFieldOf(com.twitter.ui.dialog.actionsheet.p::class.java))
    }

    @Test
    fun `resolved list field is writable so an entry can be appended`() {
        val sheet = com.twitter.ui.dialog.actionsheet.h()
        val field = HostResolver.listFieldOf(sheet.javaClass)!!
        val item = HostResolver.entryConstructor(
            HostResolver.actionSheetItem(loader)!!,
        )!!.newInstance(0, 0x5EED_0001, "Download")

        @Suppress("UNCHECKED_CAST")
        val before = field.get(sheet) as List<Any>
        field.set(sheet, before + item)

        @Suppress("UNCHECKED_CAST")
        val after = field.get(sheet) as List<Any>
        assertEquals(1, after.size)
        assertEquals(
            0x5EED_0001,
            (after.single() as com.twitter.ui.dialog.actionsheet.b).actionId,
        )
    }

    // ---- sheet -> tweet link ----------------------------------------------

    @Test
    fun `finds the ctor that pairs a sheet model with a shareable`() {
        val links = HostResolver.sheetLinks(loader, com.twitter.ui.dialog.actionsheet.h::class.java)
        val owners = links.map { it.declaringClass.name }.toSet()
        assertEquals(
            setOf("com.twitter.menu.share.full.providers.l"),
            owners,
        )
    }

    @Test
    fun `rejects a ctor taking a sheet model but no shareable`() {
        // Fixture providers.j(h, String): a panel with no tweet attached. Ablation showed that
        // without this decoy the shareable half of the check was asserting nothing — every
        // candidate carrying a sheet model happened to carry a shareable too.
        val owners = HostResolver.sheetLinks(
            loader,
            com.twitter.ui.dialog.actionsheet.h::class.java,
        ).map { it.declaringClass.name }
        assertTrue(
            "decoy j must be rejected, got $owners",
            !owners.contains("com.twitter.menu.share.full.providers.j"),
        )
    }

    @Test
    fun `rejects a ctor taking a shareable but no sheet model`() {
        // Fixture providers.k(e, String): cannot associate a tweet with a panel, so recording from
        // it would attribute a tweet to nothing.
        val owners = HostResolver.sheetLinks(
            loader,
            com.twitter.ui.dialog.actionsheet.h::class.java,
        ).map { it.declaringClass.name }
        assertTrue(
            "decoy k must be rejected, got $owners",
            !owners.contains("com.twitter.menu.share.full.providers.k"),
        )
    }

    // ---- tweet lookup -----------------------------------------------------

    @Test
    fun `tweet is found on the concrete shareable`() {
        val f = HostResolver.tweetFieldIn(com.twitter.share.api.m::class.java)
        assertNotNull(f)
        assertEquals("b", f!!.name)
        assertEquals(TweetWrapper::class.java, f.type)
    }

    @Test
    fun `tweet lookup walks superclasses`() {
        // n declares nothing; the tweet is on its superclass. The declared parameter type of a
        // sheet link is the shareable *base*, so the runtime instance is a subclass and a
        // declaredFields-only lookup would report "no tweet" on every tap.
        val f = HostResolver.tweetFieldIn(com.twitter.share.api.n::class.java)
        assertNotNull("must walk up the chain, not just declaredFields", f)
        assertEquals("b", f!!.name)
    }

    @Test
    fun `shareable base carries no tweet`() {
        // Pins why the walk is necessary at all rather than being incidental.
        assertNull(HostResolver.tweetFieldIn(com.twitter.share.api.e::class.java))
    }

    @Test
    fun `resolved tweet field actually reads the tweet off an instance`() {
        val shareable = com.twitter.share.api.m()
        val f = HostResolver.tweetFieldIn(shareable.javaClass)!!
        assertNotNull(f.get(shareable))
        assertTrue(f.get(shareable) is TweetWrapper)
    }
}
