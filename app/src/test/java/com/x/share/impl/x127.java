package com.x.share.impl;

import com.x.dms.components.sharesheet.t;

/**
 * X 12.27.1 dispatch owner. The sheet property accessor is no longer named {@code getState}:
 * R8 renamed it to a single letter while it still takes no arguments and returns a state object.
 * A resolver that requires the literal name finds zero points and suppresses the download row.
 */
public final class x127 {

    private final Object state = "idle";
    public t lastAction;

    public Object h() {
        return state;
    }

    public void a(t action) {
        lastAction = action;
    }
}
