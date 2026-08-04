package com.twitter.model.core;

/**
 * Compile-time stand-in for X's tweet wrapper (`com.twitter.model.core.e`), for unit tests only.
 *
 * Holds a media entity because the real model does. An earlier version had only a `String body`,
 * making it structurally identical to {@link ThinHolder} -- so every test that "proved" the tweet
 * predicate worked was really only proving a package prefix matched.
 */
public class TweetWrapper {
    public final String body;

    /** The media this tweet carries. Its absence is what made this fixture unfaithful before. */
    public com.twitter.model.core.entity.MediaEntity media;

    public TweetWrapper(String body) {
        this.body = body;
    }

    public TweetWrapper() {
        this("t");
    }
}
