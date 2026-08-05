package com.jiesa.xvideocatcher.hook

/**
 * Names of the host classes and fields this module reaches into, plus how they were derived.
 *
 * X ships R8-obfuscated: package names survive, class and member names do not. A hard-coded name is
 * therefore only valid for the exact build it was read from, so nothing here is trusted on its own.
 * Class names are verified structurally by [HostResolver] and fields by shape via [HostShapes], and
 * a host update degrades into "download entry missing" rather than a crash inside X.
 *
 * ## Why the action-sheet anchors are gone
 *
 * Versions 1.2–1.4 aimed at `com.twitter.ui.dialog.actionsheet` and `com.twitter.app.share.ui`.
 * Instruction-level cross-referencing of 12.13.0-release.0 (walking every `invoke-*` in all 16 dex
 * files) shows why nothing ever fired:
 *
 *  | target                                        | call sites in the whole APK |
 *  |-----------------------------------------------|-----------------------------|
 *  | `com.twitter.app.share.ShareSheetDialogFragment` | 0                        |
 *  | `com.twitter.app.share.ui.d.n0` (1.4.0 anchor)   | 0                        |
 *  | `com.twitter.share.chooser.j` (holds ComposeView)| 160                      |
 *
 * That View-based sheet is dead code in this build: the classes exist and match their recorded
 * shapes, which is exactly why resolution reported success while the panel stayed inert. Shape
 * verification cannot catch this — a dead class has the right shape — so anchors are now chosen by
 * *reachability*, not just structure.
 *
 * The live sheet is Compose, reached by `chooser.j.J0` attaching a `ComposeView` to the Activity's
 * decor view. It has no View hierarchy to inject into, so the module works on the data instead: the
 * row list, and the action a tap dispatches. Both are resolved by shape in [HostResolver].
 */
internal object HostClasses {

    const val HOST_PACKAGE = "com.twitter.android"

    /** Build this module's anchors were read from. Logged so a mismatch is visible in logcat. */
    const val VERIFIED_HOST_VERSION = "12.13.0-release.0"

    /**
     * The one host name used verbatim, and the only one that can be.
     *
     * X instantiates this fragment by name, so R8 has to keep it. It is retained because it still
     * anchors the media model lookups below; the share sheet itself no longer routes through it.
     */
    const val DIALOG_FRAGMENT = "com.twitter.app.common.dialog.BaseDialogFragment"

    /** Tweet wrapper (`Parcelable`). Field `a` is the tweet body, field `c` a nested quote. */
    const val TWEET_WRAPPER = "com.twitter.model.core.e"

    /**
     * Media entity. `p` = media type enum, `r` = video info, url strings live on the parent.
     *
     * `c0`, not `b0`. Every release up to 1.10.0 carried the **beta** name here: the anchors were
     * read from a 12.13.0-beta.0 bundle while the device runs 12.13.0-release.0, and R8 obfuscates
     * the two channels independently. That single wrong letter is what the device log reported as
     * `com.twitter.model.core.entity.b0 not found` -- not a version bump, not a bad predicate.
     */
    const val MEDIA_ENTITY = "com.twitter.model.core.entity.c0"
    const val MEDIA_TYPE_FIELD = "p"
    const val MEDIA_VIDEO_INFO_FIELD = "r"

    /**
     * Media-type enum. Constant *names* are not obfuscated (they are reachable via `Enum.name()`),
     * which is why matching on the name is safe here while matching on a class name is not.
     */
    const val MEDIA_TYPE_ENUM = "com.twitter.model.core.entity.c0\$d"
    const val TYPE_VIDEO = "VIDEO"
    const val TYPE_ANIMATED_GIF = "ANIMATED_GIF"
    const val TYPE_IMAGE = "IMAGE"

    /** Video info: `(float aspectW, float aspectH, List<variant>)`. */
    const val VIDEO_INFO = "com.twitter.media.av.model.z"
    const val VIDEO_INFO_VARIANTS_FIELD = "c"

    /**
     * One playable rendition. Two constructors exist; `(String url, String contentType,
     * int bitrate)` mirrors the JSON model `JsonMediaVideoVariant(a=url, b=content_type,
     * c=bitrate)`, which is how the field roles were pinned down.
     */
    const val VIDEO_VARIANT = "com.twitter.media.av.model.a0"

    // ---- Compose share sheet (the live one) --------------------------------
    //
    // Packages only. Every class inside is located by shape at runtime, since R8 renames within a
    // package but does not move classes between packages.

    /** Where the sheet is attached to the window: `chooser.j.J0`. */
    const val CHOOSER_PACKAGE = "com.twitter.share.chooser"

    /** Where the row list is built from `PackageManager`: `share.impl.c.a`. */
    const val SHARE_IMPL_PACKAGE = "com.x.share.impl"

    /** Where one row lives: `models.share.a`. */
    const val SHARE_ROW_PACKAGE = "com.x.models.share"

    /** Where tap actions and the sheet state live: `sharesheet.t$g`, `sharesheet.r.h`. */
    const val SHARESHEET_PACKAGE = "com.x.dms.components.sharesheet"

    // ---- tweet action sheet (where the download row is injected) ------------

    /**
     * The tweet action sheet's controller package: `legacy.e0` holds the rows in `a` and the tweet
     * in `b`, and `e0.h(FragmentManager)` renders them.
     *
     * This is the path 1.11.0 injects into, and it is the answer to the question versions 1.5-1.10
     * could not settle by graph search: the controller *holds* the tweet, so there is nothing to
     * search for. Cleared for reachability with a disassembler before any code was written against
     * it -- 3 direct call sites on `h`, and 57 classes outside this package entering the cluster.
     */
    const val TWEET_ACTION_PACKAGE = "com.twitter.tweet.action.legacy"
}
