package com.twitter.tweet.action.legacy;

import androidx.fragment.app.FragmentManager;
import android.content.res.Resources;
import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for the real sheet controller (`legacy.e0` on 12.13.0-release.0).
 *
 * Member names differ from the host's deliberately -- `rows` not `a`, `showSheet` not `h` -- so a
 * resolver that has drifted back to hardcoded names cannot pass this test.
 */
public final class e0 {

    public final List<Object> rows = new ArrayList<>();
    public final com.twitter.model.core.TweetWrapper tweet = new com.twitter.model.core.TweetWrapper();
    public final Resources resources = null;
    public final boolean shown = false;
    public final int count = 0;

    public void showSheet(FragmentManager fm) {
    }
}
