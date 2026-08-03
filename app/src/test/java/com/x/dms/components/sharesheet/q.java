package com.x.dms.components.sharesheet;

/**
 * A concrete implementor of the dispatch interface: the hookable dispatch point.
 *
 * Mirrors `sharesheet.j` on the real build, which implements the interface and carries a real body.
 * Named `q` here because the stub `j` is the no-`getState` decoy, and both shapes have to coexist:
 * the resolver must return this one and reject both the abstract declaration on [r] and the stateless
 * decoy.
 *
 * Together with [r] this is what makes the abstract filter load-bearing. Before `r` became an
 * interface, every dispatch candidate in the fixture was concrete, so dropping the filter changed
 * nothing and the guard could not be shown to do any work.
 */
public final class q implements r {

    private String state = "idle";

    public String getState() {
        return state;
    }

    public void onAction(t action) {
        state = "handled:" + action.getClass().getSimpleName();
    }
}
