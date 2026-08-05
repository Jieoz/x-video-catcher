package com.twitter.tweet.action.legacy;

import androidx.fragment.app.FragmentManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoy: one List and a FragmentManager method, but no tweet model.
 *
 * Pins the tweet clause. This is the shape of any dialog helper in the app; without the tweet
 * requirement the resolver would match several and the injector would hook a sheet that has no
 * tweet to download.
 */
public final class b1 {

    public final List<Object> rows = new ArrayList<>();
    public final String title = "";

    public void showSheet(FragmentManager fm) {
    }
}
