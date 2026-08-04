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

    /**
     * The axis the old predicate could not pass, and the reason this one exists.
     *
     * `PhotoVariant` sits in a package no X build has shipped, so a whitelist cannot admit it.
     * Its shape is a real media variant's: a URL plus dimensions. If the predicate ever returns
     * to matching package names, this is what goes red -- none of the fixtures in the sections
     * above can, because they all live in packages the old whitelist named.
     */
    @Test
    fun `accepts media in a package no host build has used`() {
        assertTrue(HostResolver.isTweetModel(RenamedTweet::class.java))
    }

    /**
     * The same, for media reached through a collection.
     *
     * Erasure hides the element type, so this only passes if the generic signature is read and
     * the recovered type is shape-tested. A whitelist scan of the signature string cannot match
     * a package it does not know.
     */
    @Test
    fun `accepts renamed media arriving as a list`() {
        assertTrue(HostResolver.isTweetModel(RenamedListTweet::class.java))
    }

    // ---- false positives --------------------------------------------------

    /**
     * A URL with no numbers is not media.
     *
     * This is the load-bearing half of the shape test. Without the numeric requirement the
     * predicate degenerates into "declares a String named url", which matches endpoint configs,
     * avatar holders and analytics payloads -- a large share of a running app's heap -- and the
     * probe would report the first settings object it met as a tweet.
     */
    @Test
    fun `rejects a holder whose url carries no dimensions`() {
        assertFalse(HostResolver.isTweetModel(com.unknown.host.ConfigHolder::class.java))
    }

    /**
     * Numbers with no URL are not media either.
     *
     * The mirror of the case above: geometry, timing and counter classes are everywhere, and
     * admitting them would make the predicate fire on framework objects.
     */
    /**
     * Two numbers beside a String that is not a URL is not media.
     *
     * The field *name* carries the signal: a media variant's String is a playback address, and a
     * codec name or a mime type is not. Without that check the predicate reduces to "declares a
     * String and two numbers", which is the shape of codec configs, buffer settings, cache entries
     * and window metrics -- and the probe would report the first one it met as a tweet.
     *
     * Added after an ablation dropped `looksLikeUrlField` and the entire suite stayed green.
     */
    @Test
    fun `rejects two numbers beside a string that is not a url`() {
        assertFalse(HostResolver.isTweetModel(CodecConfigHolder::class.java))
    }

    /**
     * A URL with one number beside it is not a media variant.
     *
     * The floor is two because a real variant needs a pair -- width and height, or a bitrate and a
     * duration. One number admits `{String url, int retryCount}` and every other String+int pair in
     * an app's model layer, which is most of it.
     *
     * Added after an ablation dropped the floor from 2 to 1 and the entire suite stayed green: the
     * threshold was a constant no test was pinning.
     */
    @Test
    fun `rejects a url with a single number alongside it`() {
        assertFalse(HostResolver.isTweetModel(SingleNumberHolder::class.java))
    }

    @Test
    fun `rejects a holder of numbers without a url`() {
        assertFalse(HostResolver.isTweetModel(NumbersOnlyTweet::class.java))
    }



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

    // ---- fixtures in packages the host has never used ---------------------
    //
    // Deliberately declared here rather than under a host-shaped package: these exist to show the
    // verdict does not depend on where the class lives, and an inline declaration puts that in
    // front of the reader at the point of use.

    /** A media variant under an arbitrary name: playback URL plus the numbers used to pick it. */
    private class RenamedVariant {
        @JvmField var deliveryUrl: String? = null
        @JvmField var pixelWidth: Int = 0
        @JvmField var pixelHeight: Int = 0
    }

    /** A tweet holding one [RenamedVariant] directly. */
    private class RenamedTweet {
        @JvmField var attachment: RenamedVariant? = null
        @JvmField var body: String? = null
    }

    /** A tweet holding [RenamedVariant]s through a collection, so the element type is erased. */
    private class RenamedListVariants {
        @JvmField var items: List<RenamedVariant>? = null
    }

    /** A tweet whose media arrives as a list, under an arbitrary package. */
    private class RenamedListTweet {
        @JvmField var media: List<RenamedVariant>? = null
        @JvmField var body: String? = null
    }

    /**
     * A String plus two numbers, where the String is not an address.
     *
     * Structurally indistinguishable from a media variant except for the field name, which is the
     * whole point: this is what `looksLikeUrlField` exists to refuse.
     */
    private class CodecConfig {
        @JvmField var codecName: String? = null
        @JvmField var bufferMs: Int = 0
        @JvmField var sampleRate: Int = 0
    }

    /** Holds a [CodecConfig]. Not a tweet: playback settings are not media. */
    private class CodecConfigHolder {
        @JvmField var decoder: CodecConfig? = null
    }

    /** A URL with exactly one number: the shape of a retry policy, not of a media variant. */
    private class SingleNumber {
        @JvmField var url: String? = null
        @JvmField var retryCount: Int = 0
    }

    /** Holds a [SingleNumber]. Not a tweet: one number is not a pair of dimensions. */
    private class SingleNumberHolder {
        @JvmField var endpoint: SingleNumber? = null
    }

    /** Numbers without a URL: the shape of geometry and timing classes, which are not media. */
    private class NumbersOnly {
        @JvmField var width: Int = 0
        @JvmField var height: Int = 0
    }

    /** Holds [NumbersOnly]. Not a tweet: nothing it carries is media. */
    private class NumbersOnlyTweet {
        @JvmField var box: NumbersOnly? = null
    }

}
