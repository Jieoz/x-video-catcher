package com.twitter.tweet.action.legacy;

import androidx.fragment.app.FragmentManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoy: holds a tweet and can show a sheet, but has TWO Lists.
 *
 * Pins the single-List clause. With two candidate lists, appending a row means guessing which one
 * the sheet renders, and a wrong guess is silent: the row goes into a list nothing displays.
 */
public final class a1 {

    public final List<Object> rows = new ArrayList<>();
    public final List<Object> other = new ArrayList<>();
    public final com.twitter.model.core.TweetWrapper tweet = new com.twitter.model.core.TweetWrapper();

    public void showSheet(FragmentManager fm) {
    }
}
