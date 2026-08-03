package com.jiesa.xvideocatcher.hook

/**
 * The marker words the diagnostic log is read by.
 *
 * ## Why these are constants
 *
 * The README's marker table is what a device log gets read against, so a row promising a line the
 * build cannot print sends the reader hunting for output that will never appear. Keeping the table
 * honest by *comparing prose to string literals* was tried three times and failed three times: the
 * checker kept flagging markers the code did emit, because README reader-form (`N row(s)`) and Kotlin
 * template form (`${rows.size} row(s)`) only line up through heuristics, and every heuristic swapped
 * which markers it got wrong rather than reducing the count.
 *
 * The fix is to stop matching text and match an **identifier**. Each marker word is declared here
 * once, the log lines are built from these constants, and the README quotes the same words. A
 * checker then compares `README ∩ ProbeMarkers` -- an exact set relation over identifiers, with no
 * similarity judgement anywhere in it.
 */
internal object ProbeMarkers {

    /** Anchor resolution: one line per anchor, value or `MISS`. */
    const val RESOLVE = "PROBE resolve"

    /** A hook could not be installed. Names which one; the rest still install. */
    const val HOOK_FAILED = "PROBE hook FAILED"

    /** The sheet was built and the module saw the row list. */
    const val ROWS_BUILT = "PROBE rows built:"

    /** Whether the row list tolerates an append. */
    const val LIST_MUTABLE = "PROBE   list mutable="

    /** A tap was dispatched and reached the module. */
    const val ACTION = "PROBE action"

    /** The object a tweet is being looked for on. */
    const val RECEIVER = "receiver="

    /** Tweet candidates were found by the search, with the budget spent. */
    const val CANDIDATES = "candidate(s)"

    /** One candidate: depth, class and the field path walked to reach it. */
    const val CANDIDATE_PATH = "PROBE     ["

    /** The search found nothing from any root. */
    const val NO_CANDIDATE = "NO tweet candidate"

    /** A tweet model was reported to the extractor. */
    const val TWEET_FOUND = "PROBE   TWEET FOUND at"

    /** The production extractor ran over a candidate. */
    const val MEDIA_EXTRACTED = "PROBE   media extracted:"

    /** Legacy chooser entry. Expected to be absent; presence means X changed sheets. */
    const val SHEET_OPENED = "PROBE sheet opened via"

    /** A probe hook threw and was caught. */
    const val PROBE_ERROR = "ERROR probe"

    /** Every marker, for the README cross-check. */
    val ALL: List<String> = listOf(
        RESOLVE, HOOK_FAILED, ROWS_BUILT, LIST_MUTABLE, ACTION, RECEIVER,
        CANDIDATES, CANDIDATE_PATH, NO_CANDIDATE, TWEET_FOUND, MEDIA_EXTRACTED,
        SHEET_OPENED, PROBE_ERROR,
    )
}
