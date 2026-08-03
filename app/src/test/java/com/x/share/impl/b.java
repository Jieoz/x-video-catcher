package com.x.share.impl;

import com.x.dms.components.sharesheet.t;

/**
 * The sheet controller: the second dispatch point, in a different package from the first.
 *
 * Two packages declare dispatch methods on the real build, which is why the resolver searches both.
 * Having a fixture in each proves the search is not accidentally restricted to one.
 */
public final class b {

    private String state = "idle";

    /** The action this controller last received, so a test can prove the hook target really runs. */
    public t lastAction;

    public String getState() {
        return state;
    }

    public void onAction(t action) {
        lastAction = action;
        state = "handled";
    }
}
