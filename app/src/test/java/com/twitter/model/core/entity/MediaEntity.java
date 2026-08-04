package com.twitter.model.core.entity;

/**
 * Test double for a media entity in the legacy package.
 *
 * Carries a URL and pixel dimensions because a real media variant cannot not have them: the
 * host's player chooses a rendition by size. Holding only `url` -- as this did while the
 * predicate matched on package names -- made it indistinguishable from a config holder.
 */
public class MediaEntity {
    public String url;
    public int width;
    public int height;
}
