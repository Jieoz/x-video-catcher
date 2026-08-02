package com.twitter.ui.dialog.actionsheet;

import com.twitter.app.common.dialog.ClickContract;

/**
 * Test-only stand-in for the base action-sheet ViewHolder (`actionsheet.f`).
 *
 * Declares the bind method the injector anchors on. The method name here differs from the real
 * build's ({@code n0}) on purpose: the resolver must find it by signature, so a hardcoded name
 * would fail this fixture.
 */
public class f {

    public h boundSheet;
    public ClickContract boundContract;

    /** Real build: {@code n0(actionsheet.h, dialog.o)V}. */
    public void bindSheet(h sheet, ClickContract contract) {
        this.boundSheet = sheet;
        this.boundContract = contract;
    }
}
