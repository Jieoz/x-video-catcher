package com.twitter.menu.share.full.providers;

import com.twitter.share.api.e;
import com.twitter.ui.dialog.actionsheet.h;

/**
 * Test-only stand-in for the sheet/tweet link (`menu.share.full.providers.l`).
 *
 * The real constructor is {@code (share.api.e, actionsheet.h, providers.a, carousel.j, carousel.q)}
 * — it receives the shareable and the sheet model together, which is what lets the injector pair a
 * panel with its tweet exactly instead of guessing from call ordering.
 */
public class l {

    public final e shareable;
    public final h sheet;

    public l(e shareable, h sheet, Object provider, Object carousel, Object config) {
        this.shareable = shareable;
        this.sheet = sheet;
    }
}
