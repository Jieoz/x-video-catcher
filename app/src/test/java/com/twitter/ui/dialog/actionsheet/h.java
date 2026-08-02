package com.twitter.ui.dialog.actionsheet;

import java.util.ArrayList;
import java.util.List;

/**
 * Test-only stand-in for the sheet model (`actionsheet.h` in 12.13.0-release.0).
 *
 * Shape reproduced from the real class: exactly one {@code java.util.List} instance field (the
 * items the adapter renders) alongside several non-List fields. The single-List property is what
 * the resolver uses to recognise a sheet model, so the extra fields are load-bearing: without them
 * "the only List" would be trivially true.
 */
public class h {

    /** The rendered items. Real build: field {@code g}. */
    public List<Object> g = new ArrayList<>();

    public int h;
    public boolean i;
    public boolean j;
    public Object k;
}
