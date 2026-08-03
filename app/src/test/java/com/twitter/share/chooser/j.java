package com.twitter.share.chooser;

import android.app.Activity;
import androidx.compose.ui.platform.ComposeView;

/**
 * The Compose share sheet's attach point: what actually puts the panel on screen.
 *
 * This is the class the module was missing for three releases. On 12.13.0-release.0 it is
 * `chooser.j`, with **160 call sites** — main activity, tweet detail, profile header — while the
 * View-based sheet the module had been hooking had zero. It holds a `ComposeView` and the `Activity`
 * it attaches to, and `J0` returns boolean.
 *
 * The method is named `showSheet` here rather than the real `J0`, so a hardcoded name cannot pass.
 */
public final class j {

    public final ComposeView composeView;
    public final Activity activity;

    public j(ComposeView composeView, Activity activity) {
        this.composeView = composeView;
        this.activity = activity;
    }

    /** Shows the sheet for a share subject. Returns whether it was displayed. */
    public boolean showSheet(Object subject) {
        return subject != null;
    }
}
