package com.twitter.share.chooser;

import android.app.Activity;

/**
 * Decoy: an Activity plus a `(X) -> boolean` method, but no ComposeView.
 *
 * Pins the ComposeView clause of the sheet-open predicate. "Something holding an Activity with a
 * boolean-returning one-arg method" is a shape that launchers, permission helpers and deep-link
 * handlers all match, so without this decoy that clause would assert nothing and the module could
 * anchor its one unconditional "panel opened" record to a method that has nothing to do with the
 * sheet — reintroducing exactly the ambiguity this build exists to remove.
 */
public final class k {

    public final Activity activity;

    public k(Activity activity) {
        this.activity = activity;
    }

    public boolean canHandle(Object intent) {
        return intent != null;
    }
}
