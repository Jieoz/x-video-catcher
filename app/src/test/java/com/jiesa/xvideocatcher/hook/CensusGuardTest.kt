package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import com.twitter.model.core.TweetWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two fixes this round that the ablation proved nothing was watching: the walk census and
 * the Application guard in [HostActivity].
 *
 * Both were written to remove an ambiguity from device logs, and both would have shipped as
 * decoration -- the ablation removed each one and all 140 tests stayed green.
 */
class CensusGuardTest {

    // ---- census -----------------------------------------------------------

    /**
     * The census exists so that "the predicate recognises nothing" and "the graph holds no tweet" stop
     * looking identical in a log. It is therefore worthless unless it is populated on the path where
     * nothing was found, which is the case this asserts.
     */
    @Test
    fun `a failed walk still reports what it walked`() {
        val outcome = TweetSearch.find(listOf("root" to NoTweetHere()))

        assertTrue("nothing should be found here", outcome.candidates.isEmpty())
        assertFalse("census must not be empty after a real walk", outcome.census.isEmpty())
    }

    @Test
    fun `census counts the packages of objects actually visited`() {
        val outcome = TweetSearch.find(listOf("root" to NoTweetHere()))
        val byPrefix = outcome.census.toMap()

        // The fixture's own package must appear -- if the census keyed on something else, this fails.
        assertTrue(
            "expected this test's package among ${byPrefix.keys}",
            byPrefix.keys.any { NoTweetHere::class.java.name.startsWith(it) },
        )
    }

    @Test
    fun `census is ordered by count then name so logs are comparable between runs`() {
        val counts = mapOf("com.a" to 3, "com.b" to 9, "com.c" to 3)

        assertEquals(
            listOf("com.b" to 9, "com.a" to 3, "com.c" to 3),
            packageCensus(counts),
        )
    }

    @Test
    fun `census is bounded so a dump cannot flood the log`() {
        val counts = (1..40).associate { "com.pkg$it" to it }

        assertEquals(12, packageCensus(counts).size)
        assertEquals(5, packageCensus(counts, limit = 5).size)
    }

    /**
     * Three segments, not two: `com.x` would merge `com.x.models` with `com.x.dms`, and distinguishing
     * those is the entire reason the census exists on this host.
     */
    @Test
    fun `package prefix keeps three segments`() {
        assertEquals("com.x.models", packagePrefix("com.x.models.share.a"))
        assertEquals("com.twitter.model", packagePrefix("com.twitter.model.core.e"))
        assertEquals("com.x.dms", packagePrefix("com.x.dms.components.sharesheet.j"))
        // Degenerate names must not throw or produce an empty key.
        assertEquals("Simple", packagePrefix("Simple"))
        assertEquals("a.b.C", packagePrefix("a.b.C"))
    }

    /**
     * An early exit must still report its census.
     *
     * Uses the candidate cap rather than the visit budget, because the cap is the early exit real
     * input reaches -- a tweet with more than eight media items, or one model reachable by several
     * paths. Two attempts to force `exhausted=true` failed first: `MAX_DEPTH` makes chain length
     * irrelevant, and `MAX_FANOUT` caps elements per container at 64, so `MAX_VISITS = 4000` needs a
     * shape built specifically to defeat both guards. That shape has no device counterpart.
     */
    @Test
    fun `census survives an early exit at the candidate cap`() {
        val outcome = TweetSearch.find(listOf("root" to ManyTweets(TweetSearch.MAX_CANDIDATES + 4)))

        assertEquals(
            "fixture must hit the cap for this test to mean anything",
            TweetSearch.MAX_CANDIDATES,
            outcome.candidates.size,
        )
        assertFalse("census must be reported on the early exit too", outcome.census.isEmpty())
    }

    /** A normal walk must not claim exhaustion; a false positive would send me tuning a budget. */
    @Test
    fun `a small walk does not report exhaustion`() {
        val outcome = TweetSearch.find(listOf("root" to NoTweetHere()))

        assertFalse(outcome.exhausted)
        assertTrue("sanity: the walk did visit something", outcome.visits > 0)
    }

    // ---- Application guard ------------------------------------------------

    /**
     * 1.7.0-probe passed `Application.attach`'s *argument* (a ContextImpl) to `track`, so the device
     * logged `NoSuchMethodException: android.app.ContextImpl.registerActivityLifecycleCallbacks` and
     * the activity root was silently absent for the whole session. The guard names the caller's
     * mistake instead of letting a reflective miss look like a platform restriction.
     */
    /**
     * Asserts the *diagnostic*, not the registration flag.
     *
     * `isRegisteredForTest() == false` cannot distinguish the guard from its absence: without the
     * guard the reflective lookup throws and `runCatching` leaves `registered` false anyway. That
     * indistinguishability is the shipped bug -- 1.7.0-probe logged
     * `NoSuchMethodException: android.app.ContextImpl.registerActivityLifecycleCallbacks`, which reads
     * as a platform restriction rather than as the caller passing the wrong object. What the guard
     * adds is an accurate message, so that is what gets pinned.
     */
    @Test
    fun `track names the caller's mistake instead of reporting a reflective miss`() {
        HostActivity.resetForTest()
        val log = captureDiag { HostActivity.track(NotAnApplication()) }

        assertTrue(
            "message must say an Application was required, got: $log",
            log.any { it.contains("needs an Application") },
        )
        assertTrue(
            "message must name the class actually received, got: $log",
            log.any { it.contains(NotAnApplication::class.java.name) },
        )
        assertTrue(
            "must not surface as a reflective failure, got: $log",
            log.none { it.contains("NoSuchMethodException") },
        )
        assertFalse(HostActivity.isRegisteredForTest())
        assertEquals("no foreground can have been recorded", null, HostActivity.current())
    }

    @Test
    fun `track rejects a Context that is not an Application`() {
        HostActivity.resetForTest()
        // Shaped like the real failure: Application.attach's argument is a ContextImpl.
        val log = captureDiag { HostActivity.track(FakeContextImpl()) }

        assertTrue(
            "must name the rejected class, got: $log",
            log.any { it.contains(FakeContextImpl::class.java.name) },
        )
        assertFalse(HostActivity.isRegisteredForTest())
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Lines [body] writes to the diagnostic log.
     *
     * Uses the same seam as `DiagLogTest` (writer swap + `bindForTest`) so there is one way to read
     * the log in tests, not two. Restores prior state so ordering between tests cannot matter.
     */
    private fun captureDiag(body: () -> Unit): List<String> {
        val captured = mutableListOf<String>()
        val previous = DiagLog.writer
        DiagLog.resetForTest()
        DiagLog.writer = { lines -> captured.addAll(lines); true }
        DiagLog.bindForTest()
        try {
            body()
            DiagLog.flushNow()
        } finally {
            DiagLog.writer = previous
            DiagLog.resetForTest()
        }
        return captured
    }

    // ---- fixtures ---------------------------------------------------------

    private class NotAnApplication

    private class FakeContextImpl {
        @Suppress("unused")
        val packageName: String = "com.twitter.android"
    }

    /** A small graph with no tweet in it, so a walk completes and finds nothing. */
    private class NoTweetHere {
        @Suppress("unused")
        val label: String = "x"

        @Suppress("unused")
        val nested: Nested = Nested()

        class Nested {
            @Suppress("unused")
            val items: List<String> = listOf("a", "b")
        }
    }

    /**
     * More tweet models than the candidate cap allows, each a distinct instance.
     *
     * Distinct matters: the walk skips objects already seen by identity, so one repeated instance
     * would count once and the cap would never be reached.
     */
    private class ManyTweets(n: Int) {
        @Suppress("unused")
        val tweets: List<Any> = List(n) { TweetWrapper("t$it") }
    }
}
