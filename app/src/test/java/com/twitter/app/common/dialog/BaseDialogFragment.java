package com.twitter.app.common.dialog;

/**
 * Test-only stand-in for X's `BaseDialogFragment`, the class the resolver anchors on by name.
 *
 * Three properties of the real class are reproduced deliberately, because without them the tests
 * pass against a resolver that is really just guessing:
 *
 *  1. **Two void(int) methods** (`dispatchAction` and `setStyleRes`, mirroring `u` and `R0`).
 *     That is what makes "the only void(int)" an invalid selector.
 *  2. **Five interfaces**, matching the real build's count. With one interface, "pick the first
 *     interface" and "find the one with the contract shape" are indistinguishable.
 *  3. **The real contract declared last**, behind three decoys that each miss on a different
 *     axis, so a first-match or under-constrained implementation picks a decoy and fails.
 */
public class BaseDialogFragment
        implements DecoyLifecycle, DecoyUserScoped, DecoySixMethods, DecoyFielded, ClickContract {

    // ---- ClickContract: the one the resolver must find ---------------------

    @Override
    public void onDismissed() {}

    @Override
    public void onVisibilityChanged(boolean visible) {}

    /** The click dispatch. Real build: `u(I)V`. */
    @Override
    public void dispatchAction(int actionId) {}

    @Override
    public Object completionA() {
        return null;
    }

    @Override
    public Object completionB() {
        return null;
    }

    /** Unrelated void(int) that must NOT be hooked. Real build: `R0(I)V`. */
    public void setStyleRes(int styleRes) {}

    // ---- decoy implementations --------------------------------------------

    @Override
    public void onPaused() {}

    @Override
    public Object userIdentifier() {
        return null;
    }

    @Override
    public void sixDismissed() {}

    @Override
    public void sixVisibility(boolean visible) {}

    @Override
    public void sixDispatch(int actionId) {}

    @Override
    public Object sixCompletionA() {
        return null;
    }

    @Override
    public Object sixCompletionB() {
        return null;
    }

    @Override
    public void sixExtra(long token) {}

    @Override
    public void fieldDismissed() {}

    @Override
    public void fieldVisibility(boolean visible) {}

    @Override
    public void fieldDispatch(int actionId) {}

    @Override
    public Object fieldCompletionA() {
        return null;
    }

    @Override
    public Object fieldCompletionB() {
        return null;
    }
}
