package com.x.models.share;

import android.graphics.drawable.Drawable;

/**
 * Decoy: a row's exact field shape *plus* one extra field of an unrelated type.
 *
 * Pins the total-field-count clause, which ablation proved was otherwise asserting nothing: decoy `b`
 * breaks the String count as well as the total, so the exact per-type counts alone rejected it and
 * deleting the count check left the suite green. A class with 3 Strings, a Drawable, a boolean *and*
 * an int is the only thing that isolates the clause — it passes every per-type check and must still be
 * refused, because the row the sheet renders has exactly five fields and a sixth means this is some
 * other model.
 */
public final class d {

    public final String packageName;
    public final String activityName;
    public final String label;
    public final Drawable icon;
    public final boolean direct;
    public final int rank;

    public d(String p, String a, String l, Drawable i, boolean dir, int rank) {
        this.packageName = p;
        this.activityName = a;
        this.label = l;
        this.icon = i;
        this.direct = dir;
        this.rank = rank;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof d && ((d) other).packageName.equals(packageName);
    }

    @Override
    public int hashCode() {
        return packageName.hashCode();
    }

    @Override
    public String toString() {
        return "DecoyExtraField";
    }
}
