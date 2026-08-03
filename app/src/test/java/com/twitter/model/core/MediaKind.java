package com.twitter.model.core;

/**
 * Enum in the tweet package, for unit tests only.
 *
 * X's media-type enum sits in this package tree. An enum satisfies the package predicate but is not
 * a tweet, so the search has to exclude enums explicitly or it would stop at the first one it meets.
 */
public enum MediaKind {
    VIDEO,
    IMAGE
}
