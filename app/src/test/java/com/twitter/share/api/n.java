package com.twitter.share.api;

/**
 * Test-only shareable that declares no fields of its own.
 *
 * Exists to prove the tweet lookup walks superclasses: the tweet is two levels up, so a
 * declaredFields-only implementation returns null here and the tap would report "no tweet".
 */
public class n extends m {
}
