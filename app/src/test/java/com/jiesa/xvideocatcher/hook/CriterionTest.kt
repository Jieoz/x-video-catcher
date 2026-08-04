package com.jiesa.xvideocatcher.hook

import com.twitter.model.core.MediaKind
import com.twitter.model.core.ThinHolder
import com.twitter.model.core.TweetWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the widened `isTweetModel`, which the existing 129 tests did not touch at all -- the suite
 * count was identical before and after the predicate changed, which is the signature of a change no
 * gate is watching.
 *
 * The risk this class exists for is **false positives**. Requiring one exact package made wrongly
 * accepting something nearly impossible and wrongly rejecting everything easy (that is precisely how
 * 12.13.0-release.0 failed). Adding a shape rule trades one failure mode for the other: if any class
 * holding a media-ish field counts as a tweet, the search returns junk and `TweetMedia` gets handed
 * objects it cannot read. So most of these assertions are negative.
 */
class CriterionTest {

    // ---- accepted because of shape, in any package ------------------------

    @Test
    fun `accepts a model in the legacy package`() {
        assertTrue(HostResolver.isTweetModel(TweetWrapper::class.java))
    }

    @Test
    fun `accepts a model in the current x package`() {
        assertTrue(HostResolver.isTweetModel(com.x.models.XTweet::class.java))
    }

    /**
     * The regression that shipped in 1.7.0-probe: on 12.13.0-release.0 the model is not under
     * `com.twitter.model.core`, so a prefix predicate rejected it and the search reported "no
     * candidate" from graphs that contained a tweet. Pins that the verdict no longer depends on
     * where the class lives.
     */
    @Test
    fun `package name does not decide the verdict`() {
        val tweet = com.x.models.XTweet::class.java
        assertFalse(
            "fixture must sit outside the legacy package for this test to mean anything",
            tweet.name.startsWith("com.twitter.model.core."),
        )
        assertTrue(HostResolver.isTweetModel(tweet))
    }

    // ---- the shape half ---------------------------------------------------

    @Test
    fun `accepts an unknown package that holds media entities`() {
        assertTrue(HostResolver.isTweetModel(com.unknown.host.FutureTweet::class.java))
    }

    @Test
    fun `accepts a class whose media arrives as a list`() {
        assertTrue(HostResolver.isTweetModel(com.unknown.host.ListTweet::class.java))
    }

    // ---- false positives --------------------------------------------------

    @Test
    fun `rejects an enum even inside a model package`() {
        assertFalse(HostResolver.isTweetModel(MediaKind::class.java))
    }

    /**
     * The assertion that drove the predicate rewrite. `ThinHolder` sits in a real model package and
     * holds only an id; the old package-prefix rule admitted it. Handing an id holder to `TweetMedia`
     * yields "no media" for a tweet that has media -- indistinguishable in the log from a genuine
     * text-only post.
     */
    @Test
    fun `rejects a plain holder inside a model package`() {
        assertTrue(
            "fixture must be in a model package or this proves nothing",
            ThinHolder::class.java.name.startsWith("com.twitter.model.core."),
        )
        assertFalse(HostResolver.isTweetModel(ThinHolder::class.java))
    }

    @Test
    fun `rejects ordinary framework and library classes`() {
        // Every one of these was walked on the device. Accepting any of them would flood the
        // candidate list and starve the real tweet of the nearest-first ordering.
        listOf(
            String::class.java,
            java.util.LinkedHashMap::class.java,
            java.util.LinkedHashSet::class.java,
            Any::class.java,
        ).forEach {
            assertFalse("must not accept ${it.name}", HostResolver.isTweetModel(it))
        }
    }

    @Test
    fun `rejects a class that merely mentions media in its own name`() {
        // Name similarity is not shape. This is the trap a substring check would fall into.
        assertFalse(HostResolver.isTweetModel(com.unknown.host.MediaPlayerConfig::class.java))
    }

    @Test
    fun `rejects primitives and arrays`() {
        assertFalse(HostResolver.isTweetModel(Int::class.javaPrimitiveType!!))
        assertFalse(HostResolver.isTweetModel(Array<String>::class.java))
    }

    /**
     * The predicate answers "is this a tweet", not "can a tweet be reached from here". A class one
     * hop above a tweet must be rejected, or every root object qualifies and the search collapses.
     */
    @Test
    fun `rejects a container that only points at a tweet`() {
        assertFalse(HostResolver.isTweetModel(com.unknown.host.TweetContainer::class.java))
    }
}
