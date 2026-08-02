package com.twitter.share.api;

/**
 * Test-only stand-in for the shareable base (`share.api.e`).
 *
 * Carries no tweet field, matching the real class. This is why the tweet has to be read off the
 * runtime instance by walking the class chain: the sheet link's declared parameter type is this
 * base, and looking only at declared fields here finds nothing.
 */
public class e {
    public int kind;
}
