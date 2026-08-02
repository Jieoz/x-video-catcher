package com.twitter.ui.dialog.actionsheet;

/**
 * Test-only decoy sheet model: no {@code List} field.
 *
 * A bind-shaped method taking this must be rejected, otherwise "second parameter is the click
 * contract" alone would be the whole selector and any two-arg void method would qualify.
 */
public class p {
    public int a;
    public boolean b;
}
