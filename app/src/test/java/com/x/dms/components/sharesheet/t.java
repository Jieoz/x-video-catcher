package com.x.dms.components.sharesheet;

/**
 * Root of the share-sheet action hierarchy — the sealed parent every tap arrives as.
 *
 * On 12.13.0-release.0 this is `sharesheet.t`, with nested subtypes `t$a`..`t$g`. The module finds the
 * subtype carrying a chosen row (`t$g`) by shape and then takes *this* class as the dispatch parameter
 * type, so a tap on any row type is covered by one hook.
 */
public abstract class t {

    /** Nested subtype carrying a chosen row: the one the module recognises. */
    public static final class g extends t {

        public final String sessionId;
        public final com.x.models.share.a target;

        public g(String sessionId, com.x.models.share.a target) {
            this.sessionId = sessionId;
            this.target = target;
        }
    }

    /**
     * Decoy subtype: a String and a row, but in the opposite field order.
     *
     * Pins that the action predicate checks field *order*, not just the set of types. Without it,
     * "fields are a String and a row" would match two classes and resolution would refuse — or worse,
     * pick whichever came first.
     */
    public static final class f extends t {

        public final com.x.models.share.a target;
        public final String sessionId;

        public f(com.x.models.share.a target, String sessionId) {
            this.target = target;
            this.sessionId = sessionId;
        }
    }

    /** Decoy subtype: dismissal, carrying no row. */
    public static final class a extends t {

        public final String sessionId;

        public a(String sessionId) {
            this.sessionId = sessionId;
        }
    }
}
