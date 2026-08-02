package com.twitter.ui.dialog.actionsheet;

import com.twitter.app.common.dialog.ClickContract;

/**
 * Test-only decoy: bind-shaped method whose first parameter holds no item list.
 *
 * Must be rejected. Hooking it would install a hook on a host method that has no list to append to,
 * which is silent rather than loud — exactly the failure mode this fixture exists to catch.
 */
public class q {

    public void bindSheet(p notASheet, ClickContract contract) {
        this.marker = notASheet.a;
    }

    public int marker;
}
