package com.x.dms.components.sharesheet;

/**
 * Decoy: receives actions but declares no `getState()`.
 *
 * Pins the `getState()` clause of the dispatch predicate. `(t) -> void` on its own matches loggers,
 * scribes and analytics forwarders — classes that see every action but do not own the sheet's state, so
 * hooking them can observe a tap while being unable to suppress it. The real build's dispatch points
 * all own state; this decoy proves the resolver requires that.
 */
public final class j {

    public void onAction(t action) {
        // Telemetry only: sees the action, owns nothing.
    }
}
