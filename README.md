# X Video Catcher

An LSPosed module for the X (Twitter) Android client. It adds a **下载视频 / Download video** entry
to X's native share sheet; tapping it saves the post's video or photos at the highest quality X
offers, into `Movies/XVideoCatcher/` and `Pictures/XVideoCatcher/`.

Everything runs inside X's process. There is no activity, no service, no background process, and
no launcher icon — the APK exists only to be loaded into X by LSPosed. Installing it and opening
it does nothing by design; the entry appears in X.

> ### 1.8.0-probe adds no entry, on purpose
>
> **The current build is diagnostic. It watches the share sheet and changes nothing in it, so there
> is no download entry to look for and no download to trigger.** What it does instead is record what
> X actually does, to the log described under Reading the log.
>
> Versions 1.2, 1.3 and 1.4 each resolved their anchors, installed their hooks, logged success, and
> then did nothing on the device. Those anchors were real classes of the right shape with **zero call
> sites** in the shipped app. No static check catches that, because dead code has the correct shape.
> `tools/verify_host_anchors.py` now proves reachability against the host APK, and injection returns
> once a device confirms these anchors sit on the path a tap really takes.

## Target

| Item | Value |
| --- | --- |
| Host app | `com.twitter.android` 12.13.0-release.0 (the build the device runs; anchors are resolved at runtime, not read from an APK) |
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
| `SharePathProbe` | Observes sheet open, row list and tap dispatch; adds nothing to the UI |
| `TweetSearch` | Bounded breadth-first search for the tweet model, over hook receivers, the Decompose component tree and the foreground activity |
| `HostActivity` | Tracks the foreground activity via `Application.registerActivityLifecycleCallbacks` |
| `TweetMedia` | walks the live tweet object graph, picks the best rendition per item |
| `HostDownloader` | fetches on a small pool, saves via MediaStore, reports by toast |

`SharePathProbe` replaced `ShareSheetInjector`, which targeted the action-sheet family the
reachability gate has since shown to be dead. `TweetMedia` and `HostDownloader` are unchanged and
still gated by CI: the probe deliberately runs the *production* extractor rather than a probe-local
copy, so a probe that reports media cannot mean something different from a build that downloads it.

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

**1.8.0-probe changes nothing you can see.** No entry appears in the sheet; that is the expected
result, not a failure. Open a video post's share sheet, tap any row, then read the log below — that
is the whole test. Nothing happens when you open the module itself either; it has no UI.

## Diagnosing it from the phone

Everything the module does is written to a log file on the device:

```
Download/XVideoCatcher/xvc-diag-YYYYMMDD.txt
```

Open it with any file manager, or long-press to share it. No computer, no adb, no logcat.

The file is written by X's process, not by this module's app. That is forced by how the module runs:
its code executes under X's UID, so there is nowhere else it can write that is readable afterwards
without root. The consequence to know about is that **the module's own app cannot read this file** —
a non-media file in `Download/` is visible only to the app that created it, and `READ_MEDIA_*` does
not cover documents. Any in-app "view log" button could only ever show zero records, which is why
there isn't one.

The name ends `.txt`, and that is load-bearing rather than cosmetic. The row is created with
`text/plain`, and MediaStore does not accept a display name whose extension contradicts the MIME
type — it silently renames. In 1.5.0-probe the name was `.log`, so every append queried a name the
store had already rewritten, missed, and inserted again: one session arrived as **32 numbered
fragments**, none complete, at a path that did not exist as advertised.

The log answers the questions worth asking, in order:

| What you see | What the log says |
| --- | --- |
| Nothing at all, no file | Module never loaded — not enabled in LSPosed, scope missing, or X not force-stopped |
| `=== module attached ===` | Hook is live; the line after it gives module and host versions |
| `NOTE host version differs…` | X updated past the build these anchors came from — first thing to suspect |
| `PROBE resolve` …`=MISS` | That anchor was not found at all. Names which one, so it is actionable |
| `PROBE resolve dispatch=0 point(s)` | Tap dispatch has no anchor; a tap could never be caught |
| `PROBE hook FAILED <name>: …` | That one hook could not be installed. Names which, and the others still went on — in 1.5.0-probe a single unhookable method aborted `install()` and took the rest with it |
| **`PROBE rows built: N row(s)`** | **A key line.** The sheet was built and this module saw it, with the row count and contents |
| `PROBE   list mutable=true` | The list tolerates an append, so injection has a viable insertion point |
| **`PROBE action …`** | **A key line.** A tap was dispatched and reached this module, naming the row chosen |
| `PROBE   <where> receiver=` … | The class the tweet is being looked for on, at each live hook |
| `PROBE     census <pkg>=<n>` | Package histogram of the objects a failed walk examined. Printed only when nothing was found, and it is what separates "the predicate recognised nothing" from "the tweet is not reachable from here" |
| `PROBE     pruned <pkg>=<n>` | Objects refused as dependency-injection plumbing. The 1.8.0 device log spent roughly a quarter of its visit budget inside `dagger.internal`, so a full census with an empty `pruned` report means the refusal is not matching what the host actually holds |
| `PROBE   sweep <where> …` | Verdict from the background deep sweep. The UI-thread search must fit in a share tap (~500ms), so it always stopped on budget and `exhausted=true` — which proves nothing about whether the tweet is reachable. This runs the same traversal off-thread at 300k visits and depth 25, where a miss is evidence |
| `sweep NO tweet in graph` — full line `PROBE   sweep <where> sweep NO tweet in graph (visits=… exhausted=… depth<=…)` | The sweep found nothing. Only means absence when `exhausted=false`; if the budget ran out a second line says so explicitly, because this line reads like an absence proof and that misreading already cost a release |
| `sweep TWEET FOUND` — full line `PROBE   sweep <where> sweep TWEET FOUND (…)` | The sweep reached a tweet the UI-thread search could not. Each candidate's depth, class and full field path follow: that path is the route a targeted lookup would take, which is what replaces searching |
| `PROBE   root <name> = <class>` | One search root and its concrete class. `roots=3` alone never said *which* activity was captured, which made a wrong starting point indistinguishable from a starved budget |
| `PROBE   TWEET FOUND at …` | Emitted per candidate by the reporter, after the search names it. On 1.6.0-probe this never appeared (`TWEET FOUND: 0`), which is what motivated searching beyond one level |
| **`candidate(s)`** — full line `PROBE   <where> N candidate(s) visits=… exhausted=…` | **A key line.** Tweet models are reachable from a hook that fires, with the search cost. `exhausted=true` means the visit budget ran out, which is a different finding from a clean miss and must not be read as one |
| **`PROBE     [`** — full line `PROBE     [n] depth=… <class> @ <path>` | **The payload.** One line per candidate, naming the field path walked to reach it. This is what lets the shipping build read the tweet directly instead of searching on every tap |
| `NO tweet candidate` — full line `PROBE   <where> NO tweet candidate (visits=… exhausted=… roots=…)` | Nothing reachable from either root. Every field of the receiver is dumped, so the next step needs no second trip to the device |
| `PROBE   media extracted: N item(s)` | The production extractor ran and found media — downloading would have worked |
| `PROBE sheet opened via …` | **Expected to be absent.** `chooser.j.J0` belongs to the legacy chooser, proven off the tweet-share path (see below). If this appears, X has switched sheet implementations and every anchor needs rechecking |
| `ERROR probe` … failed: … | A probe hook threw. It is caught, because a throw inside a host callback surfaces as X crashing |

The markers matter more than their contents. Earlier diagnosis was ambiguous because "the hook never
fired" and "the log never landed" both presented as an absence — identical evidence for opposite
causes. Every hook logs on entry, before any condition, so `rows built` and `action` separate them: a
missing marker names which stage was never reached.

That is what 1.5.0-probe settled, at the cost of two defects of its own. `chooser.j.J0` — hooked
since 1.2 as the sheet-open anchor — was installed successfully and **never fired once**. Its call
sites are page-level entries plus one in the legacy `chooser` package; the tweet share sheet is
driven by `com.x.share.impl` and `com.x.dms.components.sharesheet` instead. The reachability gate
passed it because it is genuinely reachable in the call graph, which is a different property from
being on the path a user's tap takes, and no static check can settle the second one. Tweet lookup
has moved onto the two hooks the device proved live.

The previous release's `ENTRY SKIPPED` lines came from the same lesson. Five causes produced one
symptom — a sheet with no download entry — and none said anything. That was the real defect: not the
missing feature, but that its failure was unreportable.

Records are queued and written by a low-priority daemon thread, and the queue is flushed once at
attach so the file proves attachment before you touch anything. Records that cannot be written stay
queued rather than being dropped; the earliest ones are produced before a `Context` exists, and
those are precisely the ones that prove the module loaded.

`XposedBridge.log` still receives the fatal lines, for anyone who prefers LSPosed's own log viewer.
It is a second copy, not the primary path.

## Limits

- **Anchored to X 12.13.0-beta.0.** Shape lookups absorb renames; a structural redesign of the
  share sheet would need new anchors. The install log makes that case visible.
- **`ANIMATED_GIF` is handled as video** (X serves GIFs as MP4). `MODEL3D` and unknown types are
  skipped rather than guessed at.
- **Highest bitrate, not highest resolution.** For X's progressive renditions these coincide;
  a host that decoupled them would need the selection revisited.
