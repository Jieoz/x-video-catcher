package com.twitter.ui.dialog.actionsheet;

/**
 * Test-only stand-in for the item model (`actionsheet.b`), carrying the
 * {@code (int drawableRes, int actionId, String title)} constructor the module builds entries with.
 */
public class b {

    public final int drawableRes;
    public final int actionId;
    public final String title;

    public b(int drawableRes, int actionId, String title) {
        this.drawableRes = drawableRes;
        this.actionId = actionId;
        this.title = title;
    }
}
