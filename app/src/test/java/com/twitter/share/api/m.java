package com.twitter.share.api;

import com.twitter.model.core.TweetWrapper;

/**
 * Test-only stand-in for the tweet-carrying shareable (`share.api.m`).
 *
 * Real build: {@code extends share.api.e} with field {@code b: com.twitter.model.core.e}. It is the
 * only subclass of the base that carries a tweet, which is what makes the chain walk unambiguous.
 */
public class m extends e {

    /** Real build: field {@code b}. */
    public TweetWrapper b = new TweetWrapper();

    public boolean c;
    public int d;
    public long f;
}
