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

    // ---- Compose share sheet: LOG ANNOTATION ONLY ---------------------------
    //
    // These are not a search space and nothing resolves through them. [HostResolver] searches
    // [HostDex.classesMatching] over a feature word, so a package rename cannot silence it.
    //
    // Why they survive at all: a miss has to distinguish "the shape changed" from "the package
    // moved", and the only way to say "still where it was on 12.13" is to have written down where
    // that was. So each constant is passed to `HostResolver.reportMiss` purely to annotate a log
    // line, and resolution succeeds unchanged when every one of them is wrong — which is asserted
    // directly, because a constant that is only supposed to be decoration is exactly the kind of
    // thing that quietly becomes load-bearing again.
    //
    // `CHOOSER_PACKAGE = "com.twitter.share.chooser"` is deleted rather than demoted. The 20260828
    // log reports it `declares no classes on this host`, so it cannot annotate anything, and its one
    // consumer -- the `sheetOpen` anchor -- was already recorded as confirmed off the live path.

    /** Where one row lived on 12.13 and still lives on 12.20.5: `models.share.a`. */
    const val SHARE_ROW_PACKAGE = "com.x.models.share"

    /** Where the row list was built on 12.13: `share.impl.c.a`. Gone as a method on 12.20.5. */
    const val SHARE_IMPL_PACKAGE = "com.x.share.impl"

    /** Where tap actions and the sheet state live: `sharesheet.s`, `sharesheet.y`. */
    const val SHARESHEET_PACKAGE = "com.x.dms.components.sharesheet"

    // ---- tweet action sheet (where the download row is injected) ------------

}
