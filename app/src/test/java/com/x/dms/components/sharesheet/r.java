package com.x.dms.components.sharesheet;

/**
 * A sheet view model that receives actions: one of the three dispatch points on the real build.
 *
 * The module hooks *every* class declaring `(t) -> void` alongside a no-arg `getState()`. On
 * 12.13.0-release.0 there are three, and the reason all must be hooked is the same one that made 1.3.0
 * inert: an implementation that does not delegate to the one you hooked is its own entry point.
 *
 * `getState` keeps its real name here because that name survives obfuscation — it is a Kotlin property
 * accessor, so the JVM naming convention fixes it rather than R8 choosing it. The dispatch method is
 * named `onAction` rather than the real build's `h`, so a hardcoded name cannot pass.
 */
public final class r {

    private String state = "idle";

    public String getState() {
        return state;
    }

    public void onAction(t action) {
        state = "handled:" + action.getClass().getSimpleName();
    }
}
