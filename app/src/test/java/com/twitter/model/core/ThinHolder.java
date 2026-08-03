package com.twitter.model.core;

/**
 * A thin id holder in the tweet package, for unit tests only.
 *
 * The real package contains types like this alongside the tweet body. The search must not return
 * one: handing TweetMedia an id holder produces "no media" for a tweet that has media, which is
 * indistinguishable in the log from a genuine text-only tweet.
 */
public class ThinHolder {
    public final long id;

    public ThinHolder(long id) {
        this.id = id;
    }
}
