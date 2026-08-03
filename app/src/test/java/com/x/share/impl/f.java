package com.x.share.impl;

import android.content.Context;
import java.util.ArrayList;

/**
 * Decoy: a Context field and `(String) -> ArrayList`, but no PackageManager getter.
 *
 * Pins the PackageManager clause on its own. Ablation showed it was asserting nothing: decoy `d`
 * lacks *both* the Context and the PackageManager, so whichever clause survived still rejected it and
 * deleting either one left the suite green. This class satisfies the Context half and must still be
 * refused — enumerating shareable apps is what makes a class the row provider, and something that
 * merely holds a Context is a cache, a formatter, or anything at all.
 */
public final class f {

    private final Context context;

    public f(Context context) {
        this.context = context;
    }

    public ArrayList<String> recentLabels(String key) {
        return new ArrayList<>();
    }
}
