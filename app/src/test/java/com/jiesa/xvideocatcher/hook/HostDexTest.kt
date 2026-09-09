package com.jiesa.xvideocatcher.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Guards the fix for the 20260828 device report: on host `12.20.5-prod.01` every share anchor
 * resolved to `0 candidates`, so no download row was injected at all.
 *
 * `0 candidates` is the *load* count, not a shape-test result — the generated `a`..`z9` names loaded
 * nothing, so no predicate ever ran. These tests cover reading the host's real class names instead,
 * plus the miss diagnostic that names where the host actually keeps that code.
 */
class HostDexTest {

    @Before
    fun reset() {
        HostDex.resetForTest()
    }

    // ---- exact-package filter ---------------------------------------------

    @Test
    fun `classesIn returns classes declared directly in the package`() {
        val all = listOf(
            "com.x.share.impl.a",
            "com.x.share.impl.c",
            "com.x.share.impl.nested.d",
            "com.x.share.other.e",
        )

        assertEquals(
            listOf("com.x.share.impl.a", "com.x.share.impl.c"),
            HostDex.namesIn(all, "com.x.share.impl"),
        )
    }

    /**
     * A sub-package must not count as the package itself. Without this a host that moved its share
     * code one level down would look present while every shape test still failed — exactly the
     * ambiguity this change exists to remove.
     */
    @Test
    fun `classesIn excludes sub-packages`() {
        assertTrue(HostDex.namesIn(listOf("com.x.share.impl.nested.d"), "com.x.share.impl").isEmpty())
    }

    @Test
    fun `classesIn does not match a package that merely shares a prefix`() {
        assertTrue(HostDex.namesIn(listOf("com.x.share.implementation.a"), "com.x.share.impl").isEmpty())
    }

    // ---- miss diagnostic --------------------------------------------------

    /**
     * The census is the actionable half of a miss: it must name the packages the host really has, so
     * the next anchor is read off the device instead of guessed a fifth time.
     */
    @Test
    fun `census names host packages matching the needle`() {
        val all = listOf(
            "com.x.share.impl.a",
            "com.x.share.impl.b",
            "com.x.share.impl.c",
            "com.x.sharesheet.v2.a",
            "com.x.timeline.a",
        )

        assertEquals(
            listOf("com.x.share.impl" to 3, "com.x.sharesheet.v2" to 1),
            HostDex.censusOf(all, "share"),
        )
    }

    @Test
    fun `census is empty when nothing matches so the log can say so explicitly`() {
        assertTrue(HostDex.censusOf(listOf("com.x.timeline.a"), "share").isEmpty())
    }

    @Test
    fun `census is bounded so a dump cannot flood the log`() {
        val all = (1..80).map { "com.x.share.p$it.a" }

        assertEquals(HostDex.CENSUS_LIMIT, HostDex.censusOf(all, "share").size)
        assertEquals(5, HostDex.censusOf(all, "share", limit = 5).size)
    }

    @Test
    fun `census ignores names with no package`() {
        assertTrue(HostDex.censusOf(listOf("ShareThing"), "share").isEmpty())
    }

    // ---- enumeration failure ----------------------------------------------

    /**
     * A loader that is not a `BaseDexClassLoader` must yield an empty list, not an exception: this
     * runs on X's own thread during `Application.attach`.
     */
    @Test
    fun `enumeration of an unsupported loader returns empty instead of throwing`() {
        assertTrue(HostDex.classNames(object : ClassLoader() {}).isEmpty())
    }
}
