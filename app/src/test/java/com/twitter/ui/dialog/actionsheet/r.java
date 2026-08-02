package com.twitter.ui.dialog.actionsheet;

/**
 * Test-only decoy: two-arg void method whose second parameter is NOT the click contract.
 *
 * Must be rejected, so that "takes a sheet model" alone cannot qualify a method.
 */
public class r {

    public void bindSheet(h sheet, String label) {
        this.marker = label;
    }

    public String marker;
}
