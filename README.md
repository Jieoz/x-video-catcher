# X Video Catcher

An LSPosed module for the X (Twitter) Android client. It adds a **下载视频 / Download video** entry
to X's native share sheet; tapping it saves the post's video or photos at the highest quality X
offers, into `Movies/XVideoCatcher/` and `Pictures/XVideoCatcher/`.

Everything runs inside X's process. There is no activity, no service, no background process, and
no launcher icon — the APK exists only to be loaded into X by LSPosed. Installing it and opening
it does nothing by design; the entry appears in X.

## Target

| Item | Value |
| --- | --- |
| Host app | `com.twitter.android` 12.13.0-beta.0 (versionCode 312130100) |
| Module | `com.jiesa.xvideocatcher` |
| minSdk / targetSdk | 28 / 35 |
| Framework | LSPosed (Xposed API 82 floor) |

## How it works

The share sheet is already holding everything needed. When a user opens it on a media post, X has
parsed the tweet, and the media list, rendition URLs and bitrates are all live objects in memory.
So there is no API call, no network interception, and no tweet-id round trip — the module reads the
object X already built.

Four pieces, in the order they run:

| Class | Job |
| --- | --- |
| `XVideoCatcherModule` | entry point; installs hooks once a host `Application` context exists |
| `ShareSheetInjector` | appends the entry to the sheet's item list, catches the tap |
| `TweetMedia` | walks the live tweet object graph, picks the best rendition per item |
| `HostDownloader` | fetches on a small pool, saves via MediaStore, reports by toast |

Hooks are installed from `Application.attach`, not at package load. `HostShapes` resolves host
fields by loading host classes, and doing that before the host classloader is fully set up returns
a partially initialised view of the app.

Only the main process is hooked. X runs several (notifications and so on); the share sheet exists
only in the main one, so hooking the rest costs startup time for nothing.

## Surviving obfuscation

X ships R8-obfuscated under pairip integrity protection. Package names survive; class and member
names do not. `ActionSheetItem` compiles down to `com.twitter.ui.dialog.actionsheet.b`, and that
name is valid only for the exact build it was read from.

So hard-coded names are a fast path, never the contract. Two mechanisms make a host update degrade
into "the download entry is missing" rather than a crash inside X:

**String anchors.** Debug and telemetry strings the obfuscator leaves alone identify a class
regardless of its compiled name:

| Anchor | Identifies |
| --- | --- |
| `ActionSheetItem(drawableRes=` | the share-sheet item model |
| `timeline_selected_caret_position` | the share-sheet controller |
| `share_menu_click` | the sheet open/click event |
| `MODEL3D` | the media-type enum |

That first anchor also pinned down field *order*: `ActionSheetItem.toString()` spells out its own
field names in declaration order, so the 3-arg constructor `(drawableRes, actionId, title)` could
be identified without guessing.

**Shape lookups.** `HostShapes` states a structural property and searches for it. The renditions
list stays "the only `List` on the video-info class" across builds while its name cycles through
`c`, `b`, `f`. Video info is "two floats and exactly one `List`" — the aspect ratio plus the
renditions. That shape was cross-checked against the 2023 TwiFucker snapshot for Twitter 10.x and
still matches X 12.13; a shape that held for three years across renames is a better bet than any
field name.

Enum constants are the one exception where names *are* safe: `Enum.name()` and `valueOf` are API
surface, so R8 leaves them intact. That is why the media type is matched on `VIDEO` / `IMAGE`
rather than on its class.

Reading one rendition avoids field names entirely — the URL is the `https://` string that is not a
MIME type, and the bitrate is the class's only int. Every resolver returns null rather than
throwing.

## Design decisions that are not the obvious ones

**The tap is caught by sentinel id, not by hooking a click listener.** The listener class is
obfuscated and, unlike the item model, has no stable string anchor left in 12.13. The injected item
instead carries action id `0x5EED0001`, chosen far outside the host's resource-compiler id space,
and any item dispatched with that id is ours. A fragile lookup traded for a value we control.

**The entry is appended to a list, not inflated as a view.** Hooking the controller's show method
means adding one item to a list that is about to be rendered. No view inflation, no layout
inspection, no window of our own — the entry looks native because it is native.

**The entry is absent when there is nothing to download.** `TweetMedia.extract` runs first, and a
text-only post gets the sheet exactly as X built it. A permanent entry that reports "nothing here"
just teaches the user to distrust it.

**Progressive MP4 only, never the HLS playlist.** A rendition list mixes MP4s with an `.m3u8`.
Picking by bitrate alone would save a few hundred bytes of playlist text as if it were a video.

**Photos are rewritten to `name=4096x4096`, not `name=orig`.** Every guide recommends `orig`; it
returns **404** whenever `format` does not match how the image is stored, and the stored format is
not knowable from the URL. `4096x4096` answered 200 for every captured photo in both formats and
was byte-identical to `orig` where `orig` worked. `format` is preserved rather than forced to jpg —
forcing jpg onto a PNG re-encodes it — with `webp` the one exception, since it is a display-time
transcode.

**"Already saved" counts as success.** Names carry CDN identity (`x_<mediaId>_1080x1920.mp4`,
`x_<photoKey>.jpg`), so a re-tap resolves to the existing file instead of writing a second copy.
Reporting failure there would send the user looking for a problem that is not there.

**Files go to `Movies/` and `Pictures/`, not `Downloads/`.** This code runs as X, so an app-private
write lands in X's sandbox where the gallery cannot reach it. MediaStore makes the writing process
the owner, so no runtime permission is needed on API 29+. `Downloads/` is worse than useless:
Android shows a non-media file there only to its creator. Writes are bracketed with `IS_PENDING`,
so a half-written file never appears, and a failed write deletes its row rather than leaving a
0-byte "video".

**Strings are inlined, not loaded from module resources.** A module's `R` class is unreachable
inside the host unless its APK is added to the host asset path first. That call works, but it
mutates host state to read a handful of short strings and fails silently on some host/LSPosed
combinations, leaving blank labels in the sheet. Labels are localised against the host's own locale
instead.

**No permissions are declared.** Downloads use X's own `INTERNET`; MediaStore needs none for media
the caller creates. Declaring them would grant this APK capabilities it never exercises, since it
has no runnable component.

**Failures name their stage.** A bare "下载失败" cannot distinguish an expired URL from a storage
problem. Every hook body is also wrapped: a throw inside a host UI callback surfaces to the user as
X crashing, which is worse than a missing entry.

## Signing and upgrades

Builds are signed with a **fixed** key from CI secrets (`XVC_KEYSTORE_B64` and friends), not the
per-machine debug key Gradle generates by default. That default is regenerated on every CI runner,
so consecutive builds had different signatures and Android refused to install one over another —
and each forced uninstall wiped the LSPosed scope selection with it.

Two gates make that unrepeatable: CI **fails** if the keystore secret is missing, and it asserts
the built APK's certificate digest equals the pinned value. A silent fallback to a throwaway debug
key cannot ship. Changing the key later would again require uninstalling everywhere, so treat the
pinned digest in the workflow as frozen.

## Build

Cloud CI only — `.github/workflows/build.yml`. Local builds are for tests; they cannot produce a
distributable APK, because the signing key lives only in CI.

The workflow runs unit tests and reports real per-class counts (Gradle is silent on success, so a
green step alone would not prove any test ran), builds the APK, verifies the signing key, then
asserts the module contract against the built artifact rather than the source:

- `assets/xposed_init` names the entry class and `xposedminversion` is present
- no provider, service, activity or receiver authority is declared
- the Xposed API is **not** bundled into the APK (a bundled copy breaks hook dispatch)
- the URL-parsing, host-reflection, download and share-sheet layers are all present at the dex level
- the dex checker passes a **negative self-test**: the build fails if the checker accepts a
  fabricated method name, so the gates cannot pass vacuously

Do not add `android.aapt2FromMavenOverride` to `gradle.properties` — it is a host-specific path,
and the Maven aapt2 is x86_64-only. CI strips it defensively.

`tools/verify_photo_urls.py` replays a capture through the photo rules and fetches every rewritten
URL from the live CDN, comparing decoded image dimensions rather than byte counts (a larger image
can re-encode smaller):

```
python3 tools/verify_photo_urls.py path/to/capture.jsonl
```

It is deliberately not a CI step: it depends on the public CDN, so an outage would fail an
unrelated commit. Unit tests pin the rewrite's output shape; this proves that shape resolves. Only
unit tests caught neither the `name=orig` 404 nor the webp downgrade — both needed a real request.

## Install

1. Install the APK and enable the module in LSPosed. Scope is pre-selected to X via
   `xposedscope`.
2. Force-stop X so it restarts with the module attached.
3. Open a post with video or photos, tap share.

The **下载视频** entry appears in the share sheet. Nothing happens when you open the module itself —
it has no UI.

If the entry does not appear, check logcat for `XVC:`. The module logs its install line with both
the running host version and the version its anchors were read from, so a host update that moved
the sheet controller is visible there rather than silent.

## Limits

- **Anchored to X 12.13.0-beta.0.** Shape lookups absorb renames; a structural redesign of the
  share sheet would need new anchors. The install log makes that case visible.
- **`ANIMATED_GIF` is handled as video** (X serves GIFs as MP4). `MODEL3D` and unknown types are
  skipped rather than guessed at.
- **Highest bitrate, not highest resolution.** For X's progressive renditions these coincide;
  a host that decoupled them would need the selection revisited.
