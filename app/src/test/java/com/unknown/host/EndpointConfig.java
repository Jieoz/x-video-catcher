package com.unknown.host;

/**
 * A URL with no numbers: the shape of every endpoint, avatar and analytics holder in an app.
 *
 * The negative half of the media shape. Without it the predicate degenerates into "declares a
 * String named url", which matches much of the heap and would report a settings object as a
 * tweet. `MediaPlayerConfig` covers the mirror case of numbers without a URL.
 */
public class EndpointConfig {
    public String url;
    public String name;
}
