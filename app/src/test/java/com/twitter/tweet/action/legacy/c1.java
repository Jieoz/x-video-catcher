package com.twitter.tweet.action.legacy;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoy: one List and a tweet, but no (FragmentManager) -> void method.
 *
 * Pins the show-method clause. A class holding rows and a tweet but unable to display them is a
 * view-model; hooking it would install cleanly and never fire, which is precisely the 1.2-1.4
 * failure this predicate exists to avoid repeating.
 */
public final class c1 {

    public final List<Object> rows = new ArrayList<>();
    public final com.twitter.model.core.TweetWrapper tweet = new com.twitter.model.core.TweetWrapper();
}
