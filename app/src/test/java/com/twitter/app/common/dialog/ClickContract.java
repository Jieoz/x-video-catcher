package com.twitter.app.common.dialog;

/**
 * Test-only stand-in for X's dialog click-callback interface (`…common.dialog.o`).
 *
 * Shape matches the real one measured in 12.13.0-release.0: no fields, exactly 5 methods — one
 * void(), one void(boolean), one void(int) and two no-arg methods returning a common type. The
 * method NAMES differ from the real build on purpose: the resolver must derive the dispatch name
 * from this interface, so a hardcoded `u` would fail these tests.
 */
public interface ClickContract {

    /** Real build: `D0()V`. */
    void onDismissed();

    /** Real build: `T(Z)V`. */
    void onVisibilityChanged(boolean visible);

    /** Real build: `u(I)V` — the action id dispatch this module hooks. */
    void dispatchAction(int actionId);

    /** Real build: `Y()Lio/reactivex/b;`. */
    Object completionA();

    /** Real build: `h()Lio/reactivex/b;` — same return type as the above. */
    Object completionB();
}
