package com.twitter.app.common.dialog;

/**
 * Decoy interfaces for the BaseDialogFragment double, mirroring the real build where the fragment
 * implements five interfaces and exactly one is the click contract.
 *
 * Each decoy is a near miss on a *different* axis, so ablating any single check in
 * HostResolver.clickContract lets one of them win and reddens a test. A decoy that fails for more
 * than one reason cannot pin any single check, which is how the first version of these fixtures
 * left three guards asserted by nothing.
 */

/**
 * No void(int) at all, and declared FIRST on the fragment.
 *
 * Pins: "resolve the contract by shape" vs "just take the first interface". Under that ablation
 * this wins, has no void(int), and dispatch resolution returns null.
 */
interface DecoyLifecycle {
    void onPaused();
}

/** Ordinary second interface; keeps the interface list realistic. */
interface DecoyUserScoped {
    Object userIdentifier();
}

/**
 * Full click-contract shape — void(), void(boolean), void(int), two no-arg methods sharing one
 * return type — PLUS one extra method, so it has 6 rather than 5.
 *
 * Pins: the method-count check. The other four predicates only establish a *minimum* of five
 * methods, so without the exact count this interface is accepted and the wrong method name is
 * taken.
 */
interface DecoySixMethods {
    void sixDismissed();

    void sixVisibility(boolean visible);

    void sixDispatch(int actionId);

    Object sixCompletionA();

    Object sixCompletionB();

    /**
     * The extra method that makes the count 6 instead of 5.
     *
     * Deliberately NOT a `void()` — a second no-arg void would trip the "exactly one void()"
     * predicate instead, and then this decoy would be rejected for the wrong reason and the count
     * check would still be asserted by nothing. Same for a no-arg non-void (would break the
     * two-shared-returns predicate) and a void(int) / void(boolean). A `void(long)` is the one
     * shape that leaves every other predicate satisfied.
     */
    void sixExtra(long token);
}

/**
 * Full click-contract shape, but declares an interface constant so `declaredFields` is non-empty.
 *
 * Pins: the no-fields check. Without it this is accepted and `fieldDispatch` is hooked instead of
 * the real dispatch.
 */
interface DecoyFielded {
    /** Interface constant — this is what makes declaredFields non-empty. */
    String TAG = "dispatcher";

    void fieldDismissed();

    void fieldVisibility(boolean visible);

    void fieldDispatch(int actionId);

    Object fieldCompletionA();

    Object fieldCompletionB();
}
