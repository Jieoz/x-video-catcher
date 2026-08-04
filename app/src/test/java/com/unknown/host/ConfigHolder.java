package com.unknown.host;

/**
 * Holds an {@link EndpointConfig}. Not a tweet: nothing it carries has the shape of media.
 *
 * Structurally parallel to {@link FutureTweet} -- one object holding one other object -- so a
 * predicate that accepts this accepts anything.
 */
public class ConfigHolder {
    public EndpointConfig endpoint;
    public String label;
}
