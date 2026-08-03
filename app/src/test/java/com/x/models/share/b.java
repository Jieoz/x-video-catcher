package com.x.models.share;

import android.graphics.drawable.Drawable;

/**
 * Decoy: right field types, but four Strings instead of three.
 *
 * Pins the field-count clause of the row predicate. Without it, "3 Strings + Drawable + boolean"
 * could be read as "at least", and a neighbouring model with an extra String would resolve as the
 * row — appending to the wrong list, silently.
 */
public final class b {

    public final String packageName;
    public final String activityName;
    public final String label;
    public final String subtitle;
    public final Drawable icon;
    public final boolean direct;

    public b(String p, String a, String l, String s, Drawable i, boolean d) {
        this.packageName = p;
        this.activityName = a;
        this.label = l;
        this.subtitle = s;
        this.icon = i;
        this.direct = d;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof b && ((b) other).packageName.equals(packageName);
    }

    @Override
    public int hashCode() {
        return packageName.hashCode();
    }

    @Override
    public String toString() {
        return "Decoy4Strings";
    }
}
