package com.twitter.app.share.ui;

import com.twitter.app.common.dialog.ClickContract;
import com.twitter.ui.dialog.actionsheet.f;
import com.twitter.ui.dialog.actionsheet.h;

/**
 * Test-only stand-in for the share panel's ViewHolder (`app.share.ui.d`).
 *
 * Reproduces the property that made 1.3.0 inert: it extends the base ViewHolder and overrides the
 * bind method **without calling super**, verified against the real bytecode with a disassembler.
 * A resolver that returns only one bind point, or an injector that hooks only the base class, is
 * caught here — this override is the code path the share panel actually takes.
 */
public class d extends f {

    public boolean ownBindRan;

    @Override
    public void bindSheet(h sheet, ClickContract contract) {
        // Deliberately no super call: this mirrors the host.
        this.ownBindRan = true;
    }
}
