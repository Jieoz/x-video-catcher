package com.x.models.share;

import android.graphics.drawable.Drawable;

/**
 * The share-row model: what one row in X's Compose share sheet is made of.
 *
 * Shape read off 12.13.0-release.0: three Strings (package, activity, label), a Drawable (icon), and
 * a boolean flag. The resolver matches that shape, so this fixture reproduces it exactly — and the
 * field *names* here are deliberately meaningful (`packageName`, not `a`) to prove nothing depends
 * on obfuscated member names.
 *
 * Kotlin data-class methods are declared by hand because the resolver requires them: they are what
 * distinguishes a model from an arbitrary 5-field class.
 */
public final class a {

    public final String packageName;
    public final String activityName;
    public final String label;
    public final Drawable icon;
    public final boolean direct;

    public a(String packageName, String activityName, String label, Drawable icon, boolean direct) {
        this.packageName = packageName;
        this.activityName = activityName;
        this.label = label;
        this.icon = icon;
        this.direct = direct;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof a)) {
            return false;
        }
        a o = (a) other;
        return packageName.equals(o.packageName)
                && activityName.equals(o.activityName)
                && label.equals(o.label)
                && direct == o.direct;
    }

    @Override
    public int hashCode() {
        return packageName.hashCode() * 31 + activityName.hashCode();
    }

    @Override
    public String toString() {
        return "ShareTarget(" + packageName + "/" + activityName + ")";
    }
}
