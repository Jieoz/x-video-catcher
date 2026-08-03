package com.x.share.impl;

import android.content.Context;
import android.content.pm.PackageManager;
import java.util.ArrayList;

/**
 * Decoy: a PackageManager getter and `(String) -> ArrayList`, but no Context *field*.
 *
 * Pins the Context clause on its own — the mirror of decoy `f`. It gets its PackageManager from a
 * parameter rather than holding a Context, so it satisfies the PackageManager half and must still be
 * refused. Together with `f`, deleting either clause now turns exactly one test red instead of both
 * clauses leaning on the same over-broad decoy.
 */
public final class g {

    private final PackageManager packageManager;

    public g(PackageManager packageManager) {
        this.packageManager = packageManager;
    }

    public PackageManager getPackageManager() {
        return packageManager;
    }

    public ArrayList<String> queryLabels(String mimeType) {
        return new ArrayList<>();
    }
}
