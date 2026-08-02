package com.twitter.model.core;

/**
 * Compile-time stand-in for X's tweet wrapper (`com.twitter.model.core.e`), for unit tests only.
 *
 * HostResolver identifies the tweet field by its type's package, so the double must sit in
 * `com.twitter.model.core` for the test to exercise the real matching rule rather than a
 * relaxed variant of it.
 */
public class TweetWrapper {
    public final String body;

    public TweetWrapper(String body) {
        this.body = body;
    }

    public TweetWrapper() {
        this("t");
    }
}
