package com.twitter.tweet.action.legacy;

import androidx.fragment.app.FragmentManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoy: everything the controller has, except its FragmentManager method returns a value.
 *
 * Pins the void-return clause. `h` is a command -- it shows the sheet and returns nothing. A
 * (FragmentManager) -> something method is a factory or a lookup, and hooking it `before` would
 * fire at a moment when the row list has not been assembled yet.
 */
public final class d1 {

    public final List<Object> rows = new ArrayList<>();
    public final com.twitter.model.core.TweetWrapper tweet = new com.twitter.model.core.TweetWrapper();

    public String describe(FragmentManager fm) {
        return "not a command";
    }
}
