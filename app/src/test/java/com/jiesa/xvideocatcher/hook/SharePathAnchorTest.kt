package com.jiesa.xvideocatcher.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- row provider -----------------------------------------------------

    @Test
    fun `row provider resolves to the string to arraylist method`() {
        val m = HostResolver.rowProvider(loader)
        assertNotNull("provider must resolve", m)
        assertEquals("com.x.share.impl.c", m!!.declaringClass.name)
        assertEquals("buildTargets", m.name)
        assertEquals(ArrayList::class.java, m.returnType)
    }

    @Test
    fun `provider without a context or package manager is rejected`() {
        // Decoy d: (String) -> ArrayList and nothing else. That signature alone is generic — any
        // parser matches it.
        assertProviderRejects("com.x.share.impl.d")
    }

    @Test
    fun `provider holding a context but no package manager is rejected`() {
        // Decoy f: Context field, no PackageManager getter. Isolates the PackageManager clause;
        // ablation showed it was a no-op while decoy d was the only fixture, since d fails both
        // clauses at once and either surviving clause still rejected it.
        assertProviderRejects("com.x.share.impl.f")
    }

    @Test
    fun `provider exposing a package manager but holding no context is rejected`() {
        // Decoy g: the mirror of f — PackageManager getter, no Context field. Isolates the Context
        // clause, which ablation exposed for the same reason.
        assertProviderRejects("com.x.share.impl.g")
    }

    @Test
    fun `provider returning an immutable list is rejected`() {
        // Decoy e: Context and PackageManager present, returns List. Appending to an unmodifiable
        // view throws inside X's UI thread, so the concrete return type is a correctness requirement.
        assertProviderRejects("com.x.share.impl.e")
    }

    /**
     * Asserts the provider predicate refuses [className] specifically.
     *
     * Checks the named class is *not* what resolved, rather than only that resolution stayed unique.
     * The weaker form passes whenever exactly one class matches, whichever one it is, which is how
     * two of these clauses first went green while asserting nothing.
     */
    private fun assertProviderRejects(className: String) {
        val m = HostResolver.rowProvider(loader)
        assertNotNull("provider must still resolve", m)
        assertTrue(
            "$className must be rejected, but resolution picked ${m!!.declaringClass.name}.${m.name}",
            m.declaringClass.name != className,
        )
        assertEquals("com.x.share.impl.c", m.declaringClass.name)
    }

    @Test
    fun `resolved provider really returns a mutable list of rows`() {
        // Runs the resolved method rather than trusting its signature: the real build appends here,
        // so "resolves" is not the claim that matters — "resolves to something appendable" is.
        val m = HostResolver.rowProvider(loader)!!
        val provider = com.x.share.impl.c(null)
        @Suppress("UNCHECKED_CAST")
        val rows = m.invoke(provider, "video/mp4") as ArrayList<Any>
        assertEquals(2, rows.size)

        val row = HostResolver.rowClass(loader)!!
        assertTrue("provider must return row-typed elements", row.isInstance(rows[0]))

        val before = rows.size
        rows.add(rows[0])
        assertEquals("list must accept an append", before + 1, rows.size)
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

    @Test
    fun `dispatch finds every declaring class, not just one`() {
        val points = dispatchPoints()
        val owners = points.map { it.method.declaringClass.name }.toSet()
        assertEquals(
            "all dispatch points must be hooked — hooking one and assuming coverage is the 1.3.0 bug",
            setOf("com.x.share.impl.b", "com.x.dms.components.sharesheet.r"),
            owners,
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

    // ---- sheet open -------------------------------------------------------

    @Test
    fun `sheet open resolves the compose attach point`() {
        val m = HostResolver.sheetOpen(loader)
        assertNotNull("sheet-open anchor must resolve", m)
        assertEquals("com.twitter.share.chooser.j", m!!.declaringClass.name)
        assertEquals("showSheet", m.name)
        assertEquals(Boolean::class.javaPrimitiveType, m.returnType)
    }

    @Test
    fun `sheet open rejects a class without a compose view`() {
        // Decoy chooser.k: an Activity field and a (X) -> boolean method, but no ComposeView.
        // "(X) -> boolean on something holding an Activity" is a shape ordinary launchers and
        // permission helpers match.
        assertSheetOpenRejects("com.twitter.share.chooser.k")
    }

    @Test
    fun `sheet open rejects a compose class holding no activity`() {
        // Decoy chooser.m: a genuine ComposeView, no Activity. Isolates the Activity clause, which
        // ablation exposed as a no-op — decoy k lacks both, so the ComposeView clause alone rejected
        // it and deleting the Activity check changed nothing. The sheet attaches to the Activity's
        // decor view, so that field is what makes this the attach point rather than any Compose host.
        assertSheetOpenRejects("com.twitter.share.chooser.m")
    }

    /** Asserts the sheet-open predicate refuses [className] specifically, not merely that it is unique. */
    private fun assertSheetOpenRejects(className: String) {
        val m = HostResolver.sheetOpen(loader)
        assertNotNull("sheet-open anchor must still resolve", m)
        assertTrue(
            "$className must be rejected, but resolution picked ${m!!.declaringClass.name}",
            m.declaringClass.name != className,
        )
        assertEquals("com.twitter.share.chooser.j", m.declaringClass.name)
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
}
