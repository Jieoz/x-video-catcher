package com.jiesa.xvideocatcher.hook

/**
 * Names of the host classes and fields this module reaches into, plus how they were derived.
 *
 * X ships R8-obfuscated, so `com.twitter.ui.dialog.actionsheet.ActionSheetItem` is compiled to
 * `com.twitter.ui.dialog.actionsheet.b`. Package names survive, class and member names do not.
 * That means a hard-coded name is only valid for the exact build it was read from.
 *
 * The values below were read out of X 12.13.0-beta.0 (versionCode 312130100) by walking the dex
 * tables directly. They are *candidates only* — never trusted on their own. Class names are
 * verified structurally by [HostResolver] and fields by shape via [HostShapes], so a host update
 * degrades into "download entry missing" rather than a crash inside X.
 *
 * Why a name alone is never enough, measured rather than assumed: in 12.13.0-release.0
 * (versionCode 312130000) the controller is `com.twitter.tweet.action.legacy.e0`, while the name
 * recorded from beta — `…legacy.h0` — is a *different class that still exists* (1 field, no show
 * method). `loadClass` on it succeeds, so a name-only lookup logged "controller resolved" and then
 * failed on the missing method. Both FATAL lines users saw came from that single wrong name.
 *
 * Verification anchors — string constants in the host that identify a class regardless of its
 * obfuscated name. These are what make re-derivation possible, and they are stable because they
 * are debug/telemetry strings the obfuscator does not touch:
 *
 *  | anchor                             | identifies                        |
 *  |------------------------------------|-----------------------------------|
 *  | `ActionSheetItem(drawableRes=`     | the share-sheet item model        |
 *  | `timeline_selected_caret_position` | the share-sheet controller        |
 *  | `share_menu_click`                 | the share-sheet open/click event  |
 *  | `MODEL3D`                          | the media-type enum               |
 */
internal object HostClasses {

    const val HOST_PACKAGE = "com.twitter.android"

    /** Build this module's anchors were read from. Logged so a mismatch is visible in logcat. */
    const val VERIFIED_HOST_VERSION = "12.13.0-beta.0"

    /**
     * Share-sheet item model — `ActionSheetItem`.
     *
     * Field order is fixed by its own `toString()`, which spells out the names in declaration
     * order: `ActionSheetItem(drawableRes=, actionId=, title=, subtitle=, color=, hasDivider=,
     * iconColor=, titleContentDescription=, subtitleContentDescription=, bceLabel=,
     * titleAccessibilityAction=)`.
     *
     * The 3-arg constructor `(int drawableRes, int actionId, String title)` is the one used to
     * build an injected entry.
     */
    const val ACTION_SHEET_ITEM = "com.twitter.ui.dialog.actionsheet.b"

    /**
     * The one host name used verbatim, and the only one that can be.
     *
     * X instantiates this fragment by name, so R8 has to keep it. Every other class the injector
     * needs is reached *from* here by shape — the click-contract interface, the bind method, the
     * sheet model — which is why no controller name appears in this file any more.
     *
     * Deleted along with the controller path: `SHARE_SHEET_CONTROLLER`, `SHARE_SHEET_ITEMS_FIELD`
     * and `SHARE_SHEET_TWEET_FIELD`. Versions 1.2 and 1.3 hooked that controller
     * (`com.twitter.tweet.action.legacy.e0`) and its `show(FragmentManager)`; device logs showed
     * both hooks installing and never firing, because that class drives the **tweet action sheet**
     * and not the share panel the user actually opens. Keeping the constants around as a fallback
     * would only preserve a path proven not to reach the target.
     */
    const val DIALOG_FRAGMENT = "com.twitter.app.common.dialog.BaseDialogFragment"

    /** Tweet wrapper (`Parcelable`). Field `a` is the tweet body, field `c` a nested quote. */
    const val TWEET_WRAPPER = "com.twitter.model.core.e"

    /** Media entity. `p` = media type enum, `r` = video info, url strings live on the parent. */
    const val MEDIA_ENTITY = "com.twitter.model.core.entity.b0"
    const val MEDIA_TYPE_FIELD = "p"
    const val MEDIA_VIDEO_INFO_FIELD = "r"

    /**
     * Media-type enum. Constant *names* are not obfuscated (they are enum names, reachable via
     * `Enum.name()`), which is why matching on the name is safe here while matching on a class
     * name is not.
     */
    const val MEDIA_TYPE_ENUM = "com.twitter.model.core.entity.b0\$d"
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
}
