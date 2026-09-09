package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Guards the miss diagnostic that has to carry the next anchor fix.
 *
 * The 20260828 1.45 device log is the reason this exists. It printed:
 *
 * ```
 * row provider: 0 candidates in com.x.share.impl
 * row model:    0 candidates in com.x.models.share
 * sheet open:   0 candidates in com.twitter.share.chooser
 *   package com.twitter.share.chooser declares no classes on this host
 * ```
 *
 * Three misses, one "declares no classes" line. So `com.x.share.impl` and `com.x.models.share` *do*
 * exist on host 12.20.5 and it was the shape predicates that rejected every class in them — and the
 * log said nothing about what those classes look like, leaving the next step to guesswork again.
 * These tests assert the two properties that make the log actionable instead.
 */
class MissDiagnosticTest {

    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        HostDex.resetForTest()
        lines.clear()
        DiagLog.resetForTest()
        DiagLog.setEnabled(true)
        DiagLog.bindForTest()
        DiagLog.setWriterForTest { batch -> lines.addAll(batch); true }
        HostResolver.resetCensusForTest()
    }

    /**
     * A populated-but-unmatched package must dump shapes. This is the 12.20.5 case: without it the
     * log proves only that the predicate failed, which was already known from `0 candidates`.
     */
    @Test
    fun `a package that exists but matched nothing reports its class shapes`() {
        HostDex.seedForTest(listOf(ShapeFixture::class.java.name))

        HostResolver.reportMiss(
            javaClass.classLoader!!,
            ShapeFixture::class.java.`package`!!.name,
            "nothing-matches-this",
        )
        DiagLog.flushNow()

        val body = lines.joinToString("\n")
        // The dumped name is the JVM binary name, so a nested fixture appears as Outer$ShapeFixture.
        assertTrue("expected a shape line, got:\n$body", body.contains("ShapeFixture ["))
        // The signature is the actionable part: the predicate consumes field types, so the log has to
        // carry field types.
        assertTrue("expected field types in the shape line, got:\n$body", body.contains("String"))
        assertTrue(body.contains("boolean"))
    }

    /** An absent package must say so, so "moved" stays distinguishable from "reshaped". */
    @Test
    fun `a package with no classes is reported as absent`() {
        HostDex.seedForTest(listOf("com.x.somewhere.else.a"))

        HostResolver.reportMiss(javaClass.classLoader!!, "com.x.gone", "somewhere")
        DiagLog.flushNow()

        assertTrue(lines.any { it.contains("com.x.gone declares no classes") })
    }

    /**
     * The census must print on the populated branch too.
     *
     * 1.45 returned early when the package existed, which is exactly why the 20260828 log named no
     * alternative package for the two misses that mattered. A restructure can leave the old package
     * populated with unrelated classes while the row model lives in a sibling, and that has to be
     * visible in the same log.
     */
    @Test
    fun `census prints even when the recorded package still has classes`() {
        HostDex.seedForTest(
            listOf(ShapeFixture::class.java.name, "com.x.sharesheet.v2.a", "com.x.sharesheet.v2.b"),
        )

        HostResolver.reportMiss(
            javaClass.classLoader!!,
            ShapeFixture::class.java.`package`!!.name,
            "sharesheet",
        )
        DiagLog.flushNow()

        assertTrue(
            "expected a candidate package line, got:\n${lines.joinToString("\n")}",
            lines.any { it.contains("candidate package com.x.sharesheet.v2 (2 classes)") },
        )
    }

    /** Four resolvers share the "share" needle; the census must not repeat four times. */
    @Test
    fun `census for one needle is printed once per session`() {
        HostDex.seedForTest(listOf("com.x.sharesheet.v2.a"))
        val cl = javaClass.classLoader!!

        HostResolver.reportMiss(cl, "com.x.gone.one", "sharesheet")
        HostResolver.reportMiss(cl, "com.x.gone.two", "sharesheet")
        DiagLog.flushNow()

        assertEquals(1, lines.count { it.contains("candidate package com.x.sharesheet.v2") })
    }

    @Test
    fun `type names are shortened but arrays and primitives stay readable`() {
        assertEquals("String", HostResolver.simpleTypeName(String::class.java))
        assertEquals("boolean", HostResolver.simpleTypeName(Boolean::class.javaPrimitiveType!!))
        assertEquals("String[]", HostResolver.simpleTypeName(Array<String>::class.java))
        assertEquals("int[]", HostResolver.simpleTypeName(IntArray::class.java))
    }

    /** Stand-in for a host class the predicates reject. Fields are what the shape line must show. */
    @Suppress("unused")
    private class ShapeFixture {
        val label: String = ""
        val flag: Boolean = false
    }
}
