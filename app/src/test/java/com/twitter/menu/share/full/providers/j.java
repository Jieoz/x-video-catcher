package com.twitter.menu.share.full.providers;

import com.twitter.ui.dialog.actionsheet.h;

/**
 * Test-only decoy link: takes a sheet model but no shareable, so it carries no tweet.
 *
 * The mirror image of {@link k}, and the reason both halves of the sheet-link check are needed.
 * Ablation with only {@code k} present left the shareable-package requirement green after deletion:
 * every candidate that had a sheet model happened to have a shareable too, so the second half of the
 * predicate was asserting nothing. Recording a sheet against this class would produce a panel with
 * no tweet, i.e. a tap that reports "no tweet recorded".
 */
public class j {

    public j(h sheet, String label) {
    }
}
