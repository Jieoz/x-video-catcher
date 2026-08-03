package com.x.models.share;

import android.graphics.drawable.Drawable;

/**
 * Decoy: the exact field shape of a row, but no data-class methods.
 *
 * Pins the equals/hashCode/toString clause. This is the decoy that matters most, because "5 fields of
 * these types" is a shape plain holders and builders hit by accident; being a value type is what
 * distinguishes the model the sheet actually renders. Ablating that clause must turn this class into
 * a second match and fail resolution.
 */
public final class c {

    public final String one;
    public final String two;
    public final String three;
    public final Drawable icon;
    public final boolean flag;

    public c(String one, String two, String three, Drawable icon, boolean flag) {
        this.one = one;
        this.two = two;
        this.three = three;
        this.icon = icon;
        this.flag = flag;
    }
}
