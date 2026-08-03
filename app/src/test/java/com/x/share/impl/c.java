package com.x.share.impl;

import android.content.Context;
import android.content.pm.PackageManager;
import java.util.ArrayList;

/**
 * The row-list provider: enumerates shareable apps and returns the list the sheet renders.
 *
 * Shape read off 12.13.0-release.0 (`share.impl.c.a`): holds a Context, exposes a PackageManager, and
 * has a `(String) -> ArrayList` that builds the rows. The mime type is the String argument.
 *
 * `ArrayList` rather than `List` is the host's own declaration and is load-bearing for the real
 * build: a concrete mutable type is what allows a row to be appended in place, without replacing an
 * object the caller already holds.
 *
 * The method name here is `buildTargets`, not the real build's `a`, so a hardcoded name cannot pass.
 */
public final class c {

    private final Context context;

    public c(Context context) {
        this.context = context;
    }

    public PackageManager getPackageManager() {
        return context == null ? null : context.getPackageManager();
    }

    /** Builds the rows for a mime type. */
    public ArrayList<com.x.models.share.a> buildTargets(String mimeType) {
        ArrayList<com.x.models.share.a> out = new ArrayList<>();
        out.add(new com.x.models.share.a("com.whatsapp", "com.whatsapp.Share", "WhatsApp", null, false));
        out.add(new com.x.models.share.a("com.tencent.mm", "com.tencent.mm.Share", "WeChat", null, false));
        return out;
    }
}
