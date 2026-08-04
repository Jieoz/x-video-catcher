package com.jiesa.xvideocatcher.hook

import com.twitter.model.core.TweetWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that containers do not consume a level of [TweetSearch.MAX_DEPTH].
 *
 * ## The bug these pin down
 *
 * `childrenOf` used to enqueue a field's `List` as a node of its own, so an
 * `object -> List -> object` chain cost two levels per model hop. Measured against the real search,
 * a tweet four object-hops from the root came back **not found** with MAX_DEPTH = 6, because the
 * walk had really only travelled three model hops.
 *
 * This matters on the device: the share receiver reaches a tweet through exactly that alternating
 * shape (screen state -> items list -> item -> tweet), and no visit budget can fix a node the walk
 * structurally cannot reach.
 */
class ContainerDepthTest {

    private class Holder(@JvmField val items: List<Any>)
    private class Item(@JvmField val tweet: Any?)
    private class Sectioned(@JvmField val sections: List<List<Any>>)

    /** `n` object hops, each through a List, ending at the tweet. */
    private fun chain(n: Int, tweet: Any): Any {
        var node: Any = tweet
        repeat(n) { node = Holder(listOf(node)) }
        return node
    }

    @Test
    fun `reaches a tweet four object hops down a list chain`() {
        // Measured at found=0 before containers became transparent.
        val tweet = TweetWrapper("shared")

        val outcome = TweetSearch.find(listOf("root" to chain(4, tweet)))

        assertEquals(tweet, outcome.candidates.singleOrNull()?.value)
    }

    @Test
    fun `reaches a tweet at the full model depth`() {
        // MAX_DEPTH model hops must be reachable, which is the point of the constant. With
        // containers eating a level this needed 2 * MAX_DEPTH and was impossible.
        val tweet = TweetWrapper("shared")

        val outcome = TweetSearch.find(
            listOf("root" to chain(TweetSearch.MAX_DEPTH - 1, tweet)),
        )

        assertEquals(
            "a tweet within MAX_DEPTH model hops must be found",
            tweet,
            outcome.candidates.singleOrNull()?.value,
        )
    }

    @Test
    fun `a container does not appear as a step in the reported path`() {
        // The path is the thing a human reads off the device log to find the real field route. A
        // list is not a field the host declared, so it must not masquerade as one.
        val tweet = TweetWrapper("shared")

        val outcome = TweetSearch.find(listOf("vm" to Holder(listOf(Item(tweet)))))

        val path = outcome.candidates.single().path
        assertTrue("path should name host fields, got: $path", path.startsWith("vm.items"))
        assertTrue("path should reach the tweet field, got: $path", path.endsWith("tweet"))
    }

    @Test
    fun `flattens nested containers into a single level`() {
        // Asserting DEPTH, not just reachability. Reachability cannot tell the difference: a
        // container that is not flattened gets enqueued and flattened on the next level anyway, so
        // the tweet is found either way -- one level later. Measured, `sections[0][0].tweet` sits
        // at depth 2 when nesting collapses and depth 3 when it does not.
        val tweet = TweetWrapper("shared")

        val outcome = TweetSearch.find(
            listOf("root" to Sectioned(listOf(listOf(Item(tweet))))),
        )

        val candidate = outcome.candidates.single()
        assertEquals(tweet, candidate.value)
        assertEquals(
            "the list-of-lists is one storage shape, so it must cost one level, not two",
            2,
            candidate.depth,
        )
        assertEquals("root.sections[0][0].tweet", candidate.path)
    }

    @Test
    fun `nesting deeper than the collapse bound is still reachable`() {
        // Regression guard. Bounding the collapse must not DELETE a path: a first version returned
        // empty past the bound, and a five-deep nesting that the pre-transparency walk found became
        // unreachable (measured found=0 vs found=1). Past the bound the container is handed to the
        // walk instead, so it is reached a level later rather than lost.
        val tweet = TweetWrapper("deep")
        val nested = listOf(listOf(listOf(listOf(listOf(Item(tweet))))))

        val outcome = TweetSearch.find(listOf("root" to nested))

        assertEquals(
            "over-nested containers must be enqueued, never discarded",
            tweet,
            outcome.candidates.singleOrNull()?.value,
        )
    }

    @Test
    fun `still respects the per-container element cap`() {
        // Transparency must not become unbounded fanout: a long timeline is still capped, so a
        // tweet past the cap is not expected to be found.
        val tweet = TweetWrapper("late")
        val padded = (1..200).map { Item(null) } + Item(tweet)

        val outcome = TweetSearch.find(listOf("root" to Holder(padded)))

        assertTrue(
            "elements past MAX_FANOUT are not walked, so this must not be found",
            outcome.candidates.isEmpty(),
        )
    }
}
