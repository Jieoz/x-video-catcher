package com.jiesa.xvideocatcher.hook

import com.twitter.model.core.TweetWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for refusing dependency-injection plumbing during the reachability search.
 *
 * ## Why these are separate from [TweetSearchTest]
 *
 * [TweetSearchTest] asserts the search's contract on graphs that contain a findable tweet. These
 * assert on *what the search declines to enter*, which is a different property and was the actual
 * device failure: the 20260804 log ended every one of its 28 searches at `visits=4001
 * exhausted=true`, with `dagger.internal` accounting for roughly a quarter of the budget
 * (846 + 108 of 4001 on the dispatch path, 968 + 140 on the rows-provider path).
 *
 * The existing budget test could not catch that. It asserts the search *reports* exhaustion, which
 * the broken build did faithfully -- so it stayed green through the entire failure. A test that only
 * checks "did it notice it ran out" cannot distinguish running out on the answer's subtree from
 * running out on plumbing.
 */
class InjectionPruneTest {

    /** A node that would cost the whole budget if entered: mirrors a singleton graph hub. */
    private class Expensive(@JvmField val children: List<Any>)

    /**
     * A subtree large enough that walking it is unmistakable in the visit count.
     *
     * Distinct objects at every slot: `seen` is an identity set, so a builder that reuses one child
     * object collapses under dedup. Measured, that mistake turned a nominally 266k-node tree into
     * **196 visits** and let this file pass against a search with the prune deleted.
     *
     * Measured with the prune active, the wrapper holding two of these costs a total of
     * **6 visits** -- the subtree is never entered. Delete the prune and the same graph costs
     * hundreds, which is what [`refusing plumbing keeps its subtree out of the walk`] pins down.
     */
    private fun junkSubtree(depth: Int = 3, fanout: Int = 8): Expensive =
        if (depth == 0) Expensive(emptyList())
        else Expensive((1..fanout).map { junkSubtree(depth - 1, fanout) })

    @Test
    fun `refusing plumbing keeps its subtree out of the walk`() {
        // Asserting the directly observable effect, not a starvation scenario. With the prune
        // active the wrapper's subtree is never walked, so it cannot contribute visits -- measured
        // at 6 for this graph. Without the prune the same graph costs hundreds.
        //
        // Earlier versions of this test tried to prove the point by starving a sibling tweet of
        // budget. That is unobservable from a passing baseline: the junk sits behind the wrapper
        // the fix refuses to enter, so a working prune means the starvation never happens and
        // deleting the prune changed nothing the assertion could see.
        val tweet = TweetWrapper("shared")
        val plumbing = dagger.internal.DoubleCheck(junkSubtree(), junkSubtree())
        val root = Expensive(listOf(plumbing, Expensive(listOf(tweet))))

        val outcome = TweetSearch.find(listOf("root" to root))

        assertTrue(
            "walking the wrapper's subtree would cost hundreds of visits; got ${outcome.visits}",
            outcome.visits < 40,
        )
        assertEquals(
            "the wrapper itself is reported as refused",
            1,
            outcome.pruned.toMap()["dagger.internal.DoubleCheck"],
        )
        assertEquals(
            "pruning must not cost the search a tweet that is reachable without the wrapper",
            tweet,
            outcome.candidates.singleOrNull()?.value,
        )
    }

    @Test
    fun `reports which plumbing packages it refused`() {
        // A prune that silently does nothing is indistinguishable from one that works, which is
        // why the outcome carries the counter rather than the test inferring it from visits.
        val root = Expensive(
            listOf(
                dagger.internal.DoubleCheck(junkSubtree(), null),
                dagger.internal.InstanceFactory(junkSubtree()),
                javax.inject.Holder(junkSubtree()),
            ),
        )

        val outcome = TweetSearch.find(listOf("root" to root))

        // Keys are three package segments deep (see `packagePrefix`), which is exactly why the
        // device log reports `dagger.internal.d` and `dagger.internal.f` as separate lines: two
        // R8-renamed wrapper classes in one package, counted apart. These fixtures are only two
        // segments deep, so each keys under its own full class name -- same split, readable names.
        val refused = outcome.pruned.toMap()
        assertEquals(1, refused["dagger.internal.DoubleCheck"])
        assertEquals(1, refused["dagger.internal.InstanceFactory"])
        assertEquals(1, refused["javax.inject.Holder"])
        assertEquals("three wrappers refused, three counted", 3, refused.values.sum())
    }

    @Test
    fun `a pruned wrapper still counts as visited`() {
        // Refusing to descend is not the same as not looking: the node was examined, and hiding
        // that would make the visit budget under-report what the walk actually touched.
        val outcome = TweetSearch.find(
            listOf("root" to dagger.internal.DoubleCheck(junkSubtree(), null)),
        )

        assertEquals(1, outcome.visits)
        assertTrue(outcome.candidates.isEmpty())
    }

    @Test
    fun `a tweet held directly by a wrapper is still not entered`() {
        // Deliberate and worth stating: DI singletons do not hold request state, so this shape
        // does not occur, and admitting an exception here would reopen the whole subtree. If a
        // future host build really does hand a tweet to a provider, this test is the place that
        // records the decision being reversed.
        val tweet = TweetWrapper("in-plumbing")

        val outcome = TweetSearch.find(
            listOf("root" to dagger.internal.InstanceFactory(tweet)),
        )

        assertTrue(outcome.candidates.isEmpty())
    }

    @Test
    fun `pruning does not fire on host or framework classes`() {
        // The prune must key on the DI library's own packages. Matching anything named "Provider"
        // or "Factory" would hit X's own R8-renamed classes and cut real paths.
        val tweet = TweetWrapper("shared")
        val outcome = TweetSearch.find(listOf("vm" to Expensive(listOf(Expensive(listOf(tweet))))))

        assertTrue("nothing to refuse in a host-only graph", outcome.pruned.isEmpty())
        assertEquals(tweet, outcome.candidates.single().value)
    }
}
