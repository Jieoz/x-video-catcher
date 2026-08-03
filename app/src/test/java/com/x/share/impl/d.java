package com.x.share.impl;

import java.util.ArrayList;

/**
 * Decoy: has the `(String) -> ArrayList` method, but no Context and no PackageManager.
 *
 * Pins the "owns a Context and can hand out a PackageManager" half of the provider predicate.
 * `(String) -> ArrayList` alone is a common shape — any parser or cache lookup matches it — so
 * without this decoy that clause would be asserting nothing, and the module could hook a method that
 * has nothing to do with the share sheet.
 */
public final class d {

    public ArrayList<String> parse(String csv) {
        ArrayList<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            out.add(part.trim());
        }
        return out;
    }
}
