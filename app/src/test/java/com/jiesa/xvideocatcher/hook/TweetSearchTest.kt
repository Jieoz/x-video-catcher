package com.jiesa.xvideocatcher.hook

import com.twitter.model.core.MediaKind
import com.twitter.model.core.ThinHolder
import com.twitter.model.core.TweetWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the reachability search added in 1.7.0-probe.
 *
 * Fixture shapes come from the 1.6.0-probe device log rather than from convenience: the share
 * sheet's row provider holds a Context, a coroutine object and the share URL string and no tweet, so
 * a search that only looked at the hook receiver had to come back empty. Every "found" case here
 * reaches the tweet through several hops, which is the behaviour under test.
 *
 * The search deliberately does not decide which candidate is the shared tweet -- that is what the
 * device log is for -- so these assert on candidate *paths and order*, not on a single winner.
 */
class TweetSearchTest {

    // ---- doubles, shaped after the real objects --------------------------

    /** The row provider as logged on 20260804: Context, impl object, coroutine field. No tweet. */
    private class ShareProvider(
        @JvmField val a: Any?,
        @JvmField val b: Any?,
        @JvmField val c: Any?,
    )

    private class DetailViewModel(@JvmField val state: Any?)
    private class ScreenState(@JvmField val items: List<Any?>)
    private class TimelineItem(@JvmField val tweet: Any?)
    private class Cyclic {
        @JvmField var self: Any? = null
        @JvmField var tweet: Any? = null
    }

    @Test
    fun `finds a tweet several hops from the root`() {
        val tweet = TweetWrapper("shared")
        val vm = DetailViewModel(ScreenState(listOf(TimelineItem(tweet))))

        val outcome = TweetSearch.find(listOf("vm" to vm))

        assertEquals(1, outcome.candidates.size)
        assertEquals(tweet, outcome.candidates[0].value)
        // The path is the diagnostic payload: it is what lets the next build reach the tweet
        // directly instead of searching, so it has to name every hop.
        //
        // `items[0]` rather than `items.[0]`: a container is transparent to the walk, so the index
        // is part of the field it indexes rather than a hop of its own. The depth below drops by
        // one for the same reason -- the list no longer costs a level of MAX_DEPTH.
        assertEquals("vm.state.items[0].tweet", outcome.candidates[0].path)
        assertEquals(3, outcome.candidates[0].depth)
    }

    @Test
    fun `reports nothing on the shape the device actually reported`() {
        val provider = ShareProvider(a = Any(), b = Any(), c = "https://x.com/i/status/123")

        val outcome = TweetSearch.find(listOf("rows-provider" to provider))

        assertTrue(outcome.candidates.isEmpty())
        assertTrue(outcome.visits > 0)
    }

    @Test
    fun `reports the nearest candidate first`() {
        // A detail screen shows the tweet and its quote. Nearest-first ordering is what makes the
        // log readable and what lets the next build trust candidate 0: the shared tweet is the one
        // held closest to the sheet, the quote hangs deeper off the item.
        //
        // The field order matters for this test to mean anything. `deep` is declared first, so a
        // depth-first walk descends it to the quoted tweet before it ever looks at `shallow` and
        // reports the quote as candidate 0. Breadth-first reports `shallow` first. An earlier
        // version of this fixture put the two candidates at depths 1 and 4 with the shallow one
        // declared last, which every traversal order gets right -- the test passed under a
        // deliberately depth-first implementation, so it was asserting nothing.
        val shared = TweetWrapper("shared")
        val quoted = TweetWrapper("quoted")
        val root = object {
            @JvmField val deep = DetailViewModel(ScreenState(listOf(TimelineItem(quoted))))
            @JvmField val shallow = TimelineItem(shared)
        }

        val outcome = TweetSearch.find(listOf("root" to root))

        assertEquals("both tweets must be reachable", 2, outcome.candidates.size)
        assertEquals(shared, outcome.candidates[0].value)
        assertEquals("root.shallow.tweet", outcome.candidates[0].path)
        assertEquals(quoted, outcome.candidates[1].value)
        // Depths differ, so ordering is a real claim about traversal rather than about the fixture.
        assertTrue(outcome.candidates[0].depth < outcome.candidates[1].depth)
    }

    @Test
    fun `does not report an id holder from the tweet package`() {
        // An earlier version of this test asserted the opposite, because the predicate was then a
        // package prefix and could not tell these apart -- so it deferred to TweetMedia. That
        // deference manufactured the ambiguity this probe exists to remove: an id holder extracts to
        // "no media", which reads in the log exactly like a text-only tweet.
        val outcome = TweetSearch.find(listOf("holder" to ThinHolder(7L)))

        assertTrue(outcome.candidates.isEmpty())
    }

    @Test
    fun `never reports an enum from the tweet package`() {
        val outcome = TweetSearch.find(listOf("kind" to MediaKind.VIDEO))

        assertTrue(outcome.candidates.isEmpty())
    }

    @Test
    fun `uses the same predicate as HostResolver`() {
        // One definition, two callers. Asserted as agreement rather than as specific verdicts: an
        // earlier version hard-coded the package-prefix era's answers, so changing the predicate
        // broke the test that was supposed to be guarding consistency.
        val samples = listOf<Any>(
            TweetWrapper("t"),
            ThinHolder(7L),
            MediaKind.VIDEO,
            "plain string",
        )

        samples.forEach { sample ->
            val viaSearch = TweetSearch.find(listOf("root" to sample)).candidates.isNotEmpty()
            val viaResolver = HostResolver.isTweetModel(sample.javaClass)
            assertEquals(
                "search and HostResolver disagree about ${sample.javaClass.name}",
                viaResolver,
                viaSearch,
            )
        }
    }

    @Test
    fun `terminates on a cyclic graph`() {
        val c = Cyclic()
        c.self = c
        c.tweet = TweetWrapper("t")

        val outcome = TweetSearch.find(listOf("cyclic" to c))

        assertEquals(1, outcome.candidates.size)
    }

    @Test
    fun `respects the visit budget instead of walking the heap`() {
        // A wide graph with no tweet: the search must give up on budget and say so, rather than
        // freezing the host's UI thread on a tap.
        val wide = (1..200).map { (1..200).map { Any() } }

        val outcome = TweetSearch.find(listOf("wide" to wide))

        assertTrue("should exhaust the budget", outcome.exhausted)
        assertTrue(outcome.visits <= TweetSearch.MAX_VISITS + 1)
    }

    @Test
    fun `reaches a tweet held as a map value`() {
        // Maps are containers: their values must be reachable even though the map's own internals
        // are never entered.
        val tweet = TweetWrapper("t")
        val map = HashMap<String, Any>()
        map["target"] = tweet

        val outcome = TweetSearch.find(listOf("map" to map))

        assertEquals(tweet, outcome.candidates.single().value)
    }

    @Test
    fun `searches several roots under one budget and names the root in the path`() {
        val tweet = TweetWrapper("t")

        val outcome = TweetSearch.find(
            listOf(
                "empty" to Any(),
                "provider" to ShareProvider(null, null, "url"),
                "stack" to DetailViewModel(ScreenState(listOf(TimelineItem(tweet)))),
            )
        )

        assertEquals(tweet, outcome.candidates.single().value)
        assertTrue(outcome.candidates.single().path.startsWith("stack"))
    }

    @Test
    fun `null roots are skipped without error`() {
        val outcome = TweetSearch.find(listOf("a" to null, "b" to null))

        assertTrue(outcome.candidates.isEmpty())
        assertEquals(0, outcome.visits)
    }

    @Test
    fun `caps the number of candidates reported`() {
        val many = (1..40).map { TweetWrapper("t$it") }

        val outcome = TweetSearch.find(listOf("many" to many))

        assertEquals(TweetSearch.MAX_CANDIDATES, outcome.candidates.size)
    }
}
