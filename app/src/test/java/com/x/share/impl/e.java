package com.x.share.impl;

import android.content.Context;
import android.content.pm.PackageManager;
import java.util.List;

/**
 * Decoy: a Context and a PackageManager getter, but returns `List` rather than `ArrayList`.
 *
 * Pins the concrete-return-type clause. A `List` return may be an immutable or unmodifiable view, and
 * appending to one throws inside X's UI thread — precisely the failure mode the module must never
 * produce. So "returns ArrayList" is a correctness requirement, not a stylistic match, and this decoy
 * is what proves the resolver enforces it.
 */
public final class e {

    private final Context context;

    public e(Context context) {
        this.context = context;
    }

    public PackageManager getPackageManager() {
        return context == null ? null : context.getPackageManager();
    }

    public List<com.x.models.share.a> immutableTargets(String mimeType) {
        return java.util.Collections.emptyList();
    }
}
