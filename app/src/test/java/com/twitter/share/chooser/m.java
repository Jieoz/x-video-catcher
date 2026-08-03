package com.twitter.share.chooser;

import androidx.compose.ui.platform.ComposeView;

/**
 * Decoy: holds a ComposeView and has `(X) -> boolean`, but no Activity.
 *
 * Pins the Activity clause on its own. Ablation showed it was asserting nothing: decoy `k` has no
 * ComposeView *and* no Activity, so the ComposeView clause alone still rejected it. A Compose surface
 * with no Activity cannot be the sheet — the sheet attaches to the Activity's decor view, and that
 * reference is what the module would need to reach a context. So this must be refused even though it
 * is genuinely Compose.
 */
public final class m {

    public final ComposeView composeView;

    public m(ComposeView composeView) {
        this.composeView = composeView;
    }

    public boolean render(Object model) {
        return model != null;
    }
}
