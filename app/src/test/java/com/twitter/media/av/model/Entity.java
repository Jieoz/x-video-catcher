package com.twitter.media.av.model;

/**
 * Test double for an AV media variant.
 *
 * A playback URL plus the numbers the host uses to pick between renditions. Those numbers are
 * the half of the shape that separates media from every other URL-bearing object.
 */
public class Entity {
    public String url;
    public int bitrate;
    public long durationMs;
}
