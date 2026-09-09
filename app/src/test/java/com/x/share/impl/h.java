package com.x.share.impl;

import android.content.Context;
import android.content.pm.PackageManager;
import java.util.ArrayList;

/**
 * The row-list provider as host 12.20.5 declares it: same class role, different parameter list.
 *
 * The 20260828 21:56 device log reported `holder c pmGetter=true` followed by
 * `no (String)-> method declared`, i.e. the PackageManager-owning class in `com.x.share.impl` is
 * still identifiable but its row builder no longer takes a bare `String`. This fixture reproduces
 * exactly that: a `Context` field, a `PackageManager` getter, and a builder whose argument is an
 * `int` instead of the mime-type `String`.
 *
 * Its generic return type is `ArrayList<com.x.models.share.a>`, so the third tier can confirm the
 * element type rather than accepting any list-returning method. `debugLabels` returns a list of
 * something else, which is what forces that confirmation to be used: without it the tier would have
 * two candidates and refuse.
 *
 * The method name is `collectTargets`, unlike both the real build's `a` and the 12.13 fixture's
 * `buildTargets`, so no hardcoded name can pass.
 */
public final class h {

    private final Context context;

    public h(Context context) {
        this.context = context;
    }

    public PackageManager getPackageManager() {
        return context == null ? null : context.getPackageManager();
    }

    /** Builds the rows for a mime type passed as an ordinal rather than a String. */
    public ArrayList<com.x.models.share.a> collectTargets(int mimeOrdinal) {
        ArrayList<com.x.models.share.a> out = new ArrayList<>();
        out.add(new com.x.models.share.a("com.whatsapp", "com.whatsapp.Share", "WhatsApp", null, false));
        out.add(new com.x.models.share.a("com.tencent.mm", "com.tencent.mm.Share", "WeChat", null, false));
        return out;
    }

    /** Not a row builder: returns a list of something else entirely. */
    public ArrayList<String> debugLabels() {
        return new ArrayList<>();
    }
}
