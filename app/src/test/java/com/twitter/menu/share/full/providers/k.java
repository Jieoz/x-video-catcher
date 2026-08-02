package com.twitter.menu.share.full.providers;

import com.twitter.share.api.e;

/**
 * Test-only decoy link: takes a shareable but no sheet model, so it cannot associate the two.
 *
 * Must be rejected, otherwise the injector would record a tweet against nothing.
 */
public class k {

    public k(e shareable, String label) {
    }
}
