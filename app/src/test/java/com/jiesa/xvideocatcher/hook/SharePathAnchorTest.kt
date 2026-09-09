package com.jiesa.xvideocatcher.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the Compose share-sheet anchors that replaced the action-sheet ones.
 *
 * ## What went wrong three times, and what these tests actually protect
 *
 * Versions 1.2, 1.3 and 1.4 each hooked a different class in the action-sheet family. Every release
 * resolved its anchor, installed its hooks, logged success — and the share panel stayed inert.
 * Instruction-level cross-referencing of the shipped APK explained it: the 1.4.0 anchor
 * `com.twitter.app.share.ui.d.n0` has **zero call sites in the whole application**, as does
 * `ShareSheetDialogFragment`. That entire sheet is dead code in 12.13; the live one is Compose.
 *
 * So the failure was never a bad shape match — the shapes matched perfectly. It was that *shape
 * cannot detect unreachability*: dead code has the right shape. Reachability is established with a
 * disassembler over the real APK (`tools/verify_host_anchors.py`, wired into CI), and these tests
 * cover the other half: that each predicate identifies its target **uniquely**, and that every clause
 * in it is load-bearing.
 *
 * ## Why each decoy exists
 *
 * A resolver that accepts the right class proves little; one that rejects near-misses proves the
 * predicate has content. Each decoy breaks exactly one clause, so deleting that clause from the
 * resolver must turn the corresponding test red. These were verified by ablation, not assumed —
 * three earlier guards in this project shipped as no-ops because the fixture was too weak to
 * distinguish them.
 *
 * Fixtures live in the host's real packages as test-only Java sources, because the resolver searches
 * by package. Their *member* names deliberately differ from the real build's (`buildTargets` vs `a`,
 * `onAction` vs `h`) so a hardcoded name cannot pass.
 */
class SharePathAnchorTest {

    private val loader = javaClass.classLoader!!

    /**
     * Seeds the class census the resolvers now search.
     *
     * Resolution stopped enumerating `pkg.a`, `pkg.b`, ... inside one recorded package and now filters
     * [HostDex]'s list of every class the host declares. That is what lets an anchor survive X moving
     * a package, but [HostDex] reads the dex files through `BaseDexClassLoader`, which a JVM unit test
     * does not have -- so on this JVM the census is empty, every resolver sees an empty search space,
     * and each anchor test fails with an NPE that says nothing about the predicate under test.
     *
     * Seeding the fixture names supplies the census a device provides for real. Nested names are listed
     * explicitly because the action model is a nested class (`t$g`) and dex enumeration reports nested
     * classes as ordinary entries, which a package-walk in this test would not.
     */
    @Before
    fun seedCensus() {
        HostDex.resetForTest()
        HostDex.seedForTest(FIXTURES)
    }

    // ---- row model --------------------------------------------------------

    @Test
    fun `row model resolves uniquely in its package`() {
        val cls = HostResolver.rowClass(loader)
        assertNotNull("row model must resolve", cls)
        assertEquals("com.x.models.share.a", cls!!.name)
    }

    @Test
    fun `row with four strings is rejected`() {
        // Decoy b: right types, one String too many. Pins the per-type String count.
        assertTrue(HostResolver.isRowShape(com.x.models.share.a::class.java))
        assertTrue(
            "4-String decoy must not match",
            !HostResolver.isRowShape(com.x.models.share.b::class.java),
        )
    }

    @Test
    fun `row without a drawable is rejected`() {
        // Decoy e: five fields, full value-type methods, no Drawable. Isolates the per-type counts —
        // ablation showed that clause was a no-op, because every other decoy also differed in total
        // field count and so was rejected by the count check regardless.
        assertTrue(
            "no-Drawable decoy must not match",
            !HostResolver.isRowShape(com.x.models.share.e::class.java),
        )
    }

    @Test
    fun `row with an extra field of another type is rejected`() {
        // Decoy d: 3 Strings, Drawable, boolean — and an int. Passes every per-type count, so this is
        // the only fixture that isolates the *total* field-count clause.
        //
        // Added because ablation caught that clause as a no-op: deleting `fields.size != 5` left the
        // suite green, since decoy b was already rejected by its String count. A gate that cannot
        // fail is not a gate, which is the mistake this project has now made four times.
        assertTrue(
            "6-field decoy must not match",
            !HostResolver.isRowShape(com.x.models.share.d::class.java),
        )
    }

    @Test
    fun `row without data class methods is rejected`() {
        // Decoy c: the exact field shape, no equals/hashCode/toString. The clause that matters most —
        // 5 fields of those types is a shape plain holders hit by accident, and being a value type is
        // what marks the model the sheet renders.
        assertTrue(
            "non-value-type decoy must not match",
            !HostResolver.isRowShape(com.x.models.share.c::class.java),
        )
    }

    @Test
    fun `row predicate is unique, not merely permissive`() {
        // The decoys share the row's package, so if the predicate were loose, resolution would find
        // more than one and refuse. This asserts the whole gate end to end.
        val cls = HostResolver.rowClass(loader)
        assertEquals("com.x.models.share.a", cls!!.name)
    }

    // ---- action model -----------------------------------------------------

    @Test
    fun `action model is the subtype carrying a row`() {
        val row = HostResolver.rowClass(loader)!!
        val action = HostResolver.actionClass(loader, row)
        assertNotNull("action model must resolve", action)
        assertEquals("com.x.dms.components.sharesheet.t\$g", action!!.name)
    }

    @Test
    fun `action model with reversed field order is rejected`() {
        // Decoy t$f: same two types, opposite order. Pins that the predicate checks field order, not
        // just the type set — otherwise two classes match and resolution refuses.
        val row = HostResolver.rowClass(loader)!!
        val action = HostResolver.actionClass(loader, row)!!
        assertEquals("com.x.dms.components.sharesheet.t\$g", action.name)
    }

    @Test
    fun `action model is derived from the resolved row, not guessed separately`() {
        // Passing an unrelated type must find nothing: proves the action lookup is anchored to the
        // already-verified row rather than matching "a String and some object".
        val none = HostResolver.actionClass(loader, String::class.java)
        assertNull("must not match when the row type does not fit", none)
    }

    // ---- dispatch ---------------------------------------------------------

    /**
     * The 1.12 device failure: injector passed the concrete action subtype (`t$g`) to
     * [HostResolver.dispatchPoints], which looks for `(actionRoot)->void`. Live methods take the
     * sealed parent (`t`). Same process logged `FATAL no dispatch (g)->void found` from the
     * injector while the probe, using `action.superclass`, reported two live points.
     *
     * Passing the subtype must find nothing; the superclass must find the real points. That is the
     * whole load-bearing distinction — if both find the same set, the 1.12 bug is unmeasurable.
     */
    @Test
    fun `dispatch root is the sealed parent, not the concrete action subtype`() {
        val row = HostResolver.rowClass(loader)!!
        val action = HostResolver.actionClass(loader, row)!!
        val subtypeHits = HostResolver.dispatchPoints(loader, action)
        val parentHits = HostResolver.dispatchPoints(loader, action.superclass!!)
        assertTrue(
            "concrete subtype must not match dispatch signatures — that is the 1.12 injector miss",
            subtypeHits.isEmpty(),
        )
        assertTrue(
            "sealed parent must resolve the live dispatch points the probe already proved",
            parentHits.isNotEmpty(),
        )
        assertEquals(
            "both helper and direct parent lookup must agree",
            parentHits.map { it.method.declaringClass.name }.toSet(),
            dispatchPoints().map { it.method.declaringClass.name }.toSet(),
        )
    }

    @Test
    fun `dispatch finds every declaring class, not just one`() {
        val points = dispatchPoints()
        val owners = points.map { it.method.declaringClass.name }.toSet()
        assertEquals(
            "all dispatch points must be hooked — hooking one and assuming coverage is the 1.3.0 bug",
            setOf("com.x.share.impl.b", "com.x.dms.components.sharesheet.q"),
            owners,
        )
    }

    /**
     * An abstract declaration must not be returned as a dispatch point.
     *
     * The 1.5.0-probe device failure: `sharesheet.r.h` is abstract on the real build, and
     * `XposedBridge.hookMethod` throws `IllegalArgumentException` on an abstract method. That threw
     * straight out of `install()`, taking the remaining hooks and the flush with it -- partial
     * instrumentation presenting as total silence.
     *
     * The fixture could not express this until `r` became an interface here, matching the host. While
     * every candidate in it was concrete, this filter was unfalsifiable: removing it changed no
     * result. Implementors are still returned, so the interface being skipped costs no coverage.
     */
    @Test
    fun `dispatch skips abstract declarations`() {
        val points = dispatchPoints()
        assertTrue(
            "an abstract method cannot be hooked; returning it aborted install() in 1.5.0-probe",
            points.none { java.lang.reflect.Modifier.isAbstract(it.method.modifiers) },
        )
        assertTrue(
            "the concrete implementor must still be found, or skipping the interface loses coverage",
            points.any { it.method.declaringClass.name == "com.x.dms.components.sharesheet.q" },
        )
    }

    @Test
    fun `dispatch searches both packages`() {
        // The real build declares dispatch in two packages. Asserting both are represented proves the
        // search is not accidentally narrowed to one.
        val pkgs = dispatchPoints().map { it.method.declaringClass.`package`!!.name }.toSet()
        assertEquals(setOf("com.x.share.impl", "com.x.dms.components.sharesheet"), pkgs)
    }

    @Test
    fun `dispatch rejects a class without getState`() {
        // Decoy sharesheet.j: takes (t) -> void but owns no state. Telemetry forwarders match that
        // signature; hooking one observes a tap but cannot suppress it.
        val owners = dispatchPoints().map { it.method.declaringClass.name }
        assertTrue(
            "stateless telemetry decoy must be rejected, got $owners",
            !owners.contains("com.x.dms.components.sharesheet.j"),
        )
    }

    @Test
    fun `dispatch is found by signature not by name`() {
        for (p in dispatchPoints()) {
            assertEquals("onAction", p.method.name)
            assertEquals(1, p.method.parameterTypes.size)
            assertEquals(Void.TYPE, p.method.returnType)
        }
    }

    @Test
    fun `dispatch parameter is the sealed root so any row type arrives`() {
        val row = HostResolver.rowClass(loader)!!
        val action = HostResolver.actionClass(loader, row)!!
        val root = action.superclass!!
        assertEquals("com.x.dms.components.sharesheet.t", root.name)
        for (p in dispatchPoints()) {
            assertEquals(root, p.method.parameterTypes[0])
        }
    }

    @Test
    fun `resolved dispatch really receives the action`() {
        // Invokes the resolved method with a real action, so the anchor is proven to be the thing
        // that carries a tap rather than merely a method with a matching signature.
        val point = dispatchPoints().single { it.method.declaringClass.name == "com.x.share.impl.b" }
        val controller = com.x.share.impl.b()
        val row = com.x.models.share.a("com.whatsapp", "Share", "WhatsApp", null, false)
        val action = com.x.dms.components.sharesheet.t.g("session-1", row)

        point.method.invoke(controller, action)

        assertEquals(action, controller.lastAction)
        assertEquals("handled", controller.state)
    }

    // ---- tweet lookup -----------------------------------------------------
    //
    // Still production code: the probe reads the shared tweet off the sheet-open argument through
    // it. These moved here when the action-sheet suite was deleted, rather than being dropped with
    // it — the path they cover did not go away.

    @Test
    fun `tweet is found on the concrete shareable`() {
        val f = HostResolver.tweetFieldIn(com.twitter.share.api.m::class.java)
        assertNotNull(f)
        assertEquals("b", f!!.name)
        assertEquals(com.twitter.model.core.TweetWrapper::class.java, f.type)
    }

    @Test
    fun `tweet lookup walks superclasses`() {
        // n declares nothing; the tweet is on its superclass. A declaredFields-only lookup would
        // report "no tweet" for every share whose subject is a subclass.
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
        assertTrue(f.get(shareable) is com.twitter.model.core.TweetWrapper)
    }

    private fun dispatchPoints(): List<HostResolver.DispatchPoint> {
        val row = HostResolver.rowClass(loader)!!
        val action = HostResolver.actionClass(loader, row)!!
        return HostResolver.dispatchPoints(loader, action.superclass!!)
    }

    private companion object {
        /** Every share-related fixture, standing in for what dex enumeration reports on a device. */
        val FIXTURES = listOf(
            "com.x.models.share.a",
            "com.x.models.share.b",
            "com.x.models.share.c",
            "com.x.models.share.d",
            "com.x.models.share.e",
            "com.x.share.impl.b",
            "com.x.share.impl.c",
            "com.x.share.impl.d",
            "com.x.share.impl.e",
            "com.x.share.impl.f",
            "com.x.share.impl.g",
            "com.x.share.impl.h",
            "com.x.dms.components.sharesheet.j",
            "com.x.dms.components.sharesheet.q",
            "com.x.dms.components.sharesheet.r",
            "com.x.dms.components.sharesheet.t",
            "com.x.dms.components.sharesheet.t${'$'}a",
            "com.x.dms.components.sharesheet.t${'$'}f",
            "com.x.dms.components.sharesheet.t${'$'}g",
            "com.twitter.share.api.e",
            "com.twitter.share.api.m",
            "com.twitter.share.api.n",
        )
    }
}
