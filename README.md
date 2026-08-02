# X Video Catcher

An LSPosed module targeting the X (Twitter) Android client. It downloads videos and photos
from X, adding a **下载视频 / 下载图片** entry to the overflow ("more") menu on the viewer
screen. Saved files land in `Movies/XVideoCatcher/` and `Pictures/XVideoCatcher/`.

The probe layer that made this possible is still present and still writes its log: every
design rule below was measured on-device rather than assumed, and three of them contradict
what the obvious implementation would do.

## Target

| Item | Value |
| --- | --- |
| Host app | `com.twitter.android` 12.11.1-release.0 |
| Device | Android 14 (API 34) |
| Framework | LSPosed (legacy Xposed API 82) |

## Why a probe first

X ships an R8-obfuscated release build wrapped in pairip integrity protection. App class
and method names change between versions and carry no stable meaning, so hooking them by
name is not maintainable. Every hook here therefore attaches to a boundary that *cannot*
be renamed:

| Layer | Hook point | Status on X 12.11.1 |
| --- | --- | --- |
| A | `java.net.URL(String)` | **active** — carried 85 of 86 captured hits |
| B | `MediaPlayer.setDataSource(String)` | active, no hits (X uses its own player) |
| C | `org.chromium.net.CronetEngine.newUrlRequestBuilder` | **absent** — no Cronet in this build |
| D | `okhttp3.Request$Builder.url` | active — OkHttp names survived obfuscation |

Each layer attaches independently. A missing class is recorded in the summary line and
skipped; only an unexpected failure gets a stack trace, so a launch does not look like
it errored just because this build of X lacks Cronet.

Only URLs that look like media are recorded. The path must look like media *and* the
host must be known — matching the host alone logged `video.twimg.com/robots.txt` on
every launch. Filtering happens before any stack trace is materialised, since these
callbacks run on the app's network hot path.

## What the probe found

From three real captures on the target device (574 media records, 488 distinct URLs, 33
videos, all via OkHttp → `java.net.URL`). The playlist bodies fetched from those URLs are
committed under `app/src/test/resources/fixtures/` and are what the parser tests run
against:

```
https://video.twimg.com/<kind>/<id>/pl/<key>.m3u8                     master playlist
https://video.twimg.com/<kind>/<id>/pl/avc1/1920x1080/<key>.m3u8      video variant
https://video.twimg.com/<kind>/<id>/pl/mp4a/32000/<key>.m3u8          audio variant
https://video.twimg.com/<kind>/<id>/vid/avc1/0/0/1920x1080/<k>.mp4    video init segment
https://video.twimg.com/<kind>/<id>/vid/avc1/0/3000/1920x1080/<k>.m4s video segment
https://video.twimg.com/<kind>/<id>/aud/mp4a/0/3000/32000/<k>.m4s     audio segment
```

Consequences for the download stage:

- **Audio and video are separate tracks.** Fetching video segments alone yields a silent
  file; both tracks have to be taken and muxed.
- **The master playlist is the only URL worth keeping**, and it cannot be reconstructed.
  Every resolution and the audio track are reachable from it. An earlier version of this
  document called for "reconstructing a master URL from a variant" when the master was not
  captured; that was measured on the third capture (488 distinct URLs, 33 videos) and is
  false. Playlist keys are random and unrelated across levels: of 26 captured masters,
  none shared its key with any of its own variants or segments, and of 43
  variant/segment pairs, likewise none. The key is a 16-character token, so there is
  nothing to derive it from.

  Consequence: **26 of 33 videos are downloadable, 7 are not fully downloadable.**
  `DownloadPlan` returns `NeedsMaster` for those 7 rather than guessing a URL. 5 of them
  captured a video variant playlist and can be fetched at reduced quality; the remaining 2
  captured only segments and cannot be assembled at all, because each segment carries its
  own random key and the missing ones cannot be enumerated.
- **Quality must be selected from the master, never from the capture.** What the player
  fetched is not what X offers. On 8 of 10 checked videos the app had only ever requested
  the 32000 audio track while the master advertised 64000 and 128000 — picking the best
  *captured* audio therefore ships the worst *available* one. `HlsPlaylist.Master.bestAudio`
  deliberately ignores the `AUDIO=` group the chosen video variant points at, since X pairs
  its lowest video rung with `audio-32000`.
- **The player requests several resolutions while adapting**, so "what was playing" is
  not "the best available". `MediaUrls.highestResolution` ranks by pixel count, not
  height — one capture contained both 720x1280 and 1280x720 (identical area, different
  orientation), and comparing height would rank the portrait clip higher.
- **Resolutions are not a fixed ladder.** `606x1078` appeared alongside the usual
  320/480/720/1080 steps, so quality selection has to compare captured values rather
  than match a whitelist of known sizes.
- **Segment boundaries are not always 3000-aligned** — a final segment `0/2237` was
  captured. Range arithmetic must not assume fixed-length chunks.
- Audio is served at 32000, 64000 and 128000. Masters typically advertise all three while
  the player fetches only 32000, which is why selection reads the master (above).
- **An audio playlist is also a playlist.** `/pl/mp4a/<bitrate>/<key>.m3u8` satisfies the
  generic playlist test, so classifying with `isManifest` before `isAudioTrack` labelled all
  26 captured audio playlists `variant` — hiding the audio ladder and allowing a silent
  audio-only playlist to be picked as the best video rendition. `isAudioTrack` now matches
  the `/pl/mp4a/` form as well as `/aud/`, and the probe reports `audio-playlist` for it.
- **`ext_tw_video` (user uploads) inserts a `pu/` segment** between the id and the track:
  `/ext_tw_video/<id>/pu/pl/<key>.m3u8`. A master pattern demanding `<id>/pl/` classified
  all three captured user-upload masters as variants. `tweet_video` (GIFs) is still
  unconfirmed — no capture has produced one.

### Photos

Confirmed on-device: 78 photo records across `/media/` (40), `/amplify_video_thumb/` (32)
and `/ext_tw_video_thumb/` (6), with zero avatars, emoji, or card previews slipping
through the filter.

Photos are on a **different host with a different quality mechanism**, which is why
`MediaUrls` splits selection in two. X stores one image and resizes on request, so
quality is a query parameter rather than a path element:

```
https://pbs.twimg.com/media/<key>?format=jpg&name=tiny        X's own thumbnail request
https://pbs.twimg.com/media/<key>?format=jpg&name=large       X's own full-view request
https://pbs.twimg.com/media/<key>?format=jpg&name=4096x4096   the full image
https://pbs.twimg.com/media/<key>.jpg                         extension form (posters)
```

`MediaUrls.highestQualityPhoto` rewrites any of these to `name=4096x4096`, preserving the
format. It is idempotent and returns non-photo URLs unchanged, so callers can apply it
blindly.

Two counter-intuitive rules, both measured against the captured photos rather than
assumed — the obvious version of this function was wrong on both counts:

- **Not `name=orig`.** Every guide recommends it, and it returns **404** whenever
  `format` does not match how the image is stored: three captured PNG photos 404'd for
  `format=jpg&name=orig` while answering 200 for `format=png&name=orig`. The stored format
  is not knowable from the URL. `4096x4096` returned 200 for every captured photo in both
  formats, and was byte-identical to `orig` on the 8 JPEGs where `orig` worked.
- **`format` is preserved, never forced to jpg, and never omitted.** Forcing jpg onto a
  PNG re-encodes it (358430 → 29817 bytes on a captured photo). Omitting it entirely also
  404s. The one exception is `format=webp`, a display-time transcode X requests for
  thumbnails: it is replaced with jpg, which comes back 45% larger for the same photo
  (275762 → 360662 bytes).

Verified live against the CDN, not just in unit tests: all 34 distinct rewritten photo
URLs from the captures return 200, none loses pixels versus the size X itself requested,
and 23 gain (one went 239244 → 701081 bytes, so `large` genuinely is not enough). The
verifier decodes image dimensions rather than comparing byte counts — a larger image can
re-encode smaller, and a captured poster does exactly that (675x1200/50119B →
720x1280/48498B), which a byte comparison reports as a regression.

Filtering is stricter here than for video. A timeline scroll fetches hundreds of avatars,
emoji, and card previews from the same host; those are excluded by path
(`profile_images`, `profile_banners`, `emoji`, `card_img`, `semantic_core_img`) while
`media` and the video-poster paths are kept. Without that split the log fills with noise
and the photos the user asked for become unfindable — the same failure as the
`robots.txt` case above.

Both hooks see photos: 39 records arrived via `java.net.URL` and 39 via
`okhttp3.Request.Builder.url`, i.e. the same requests observed at two layers.

## Getting the log out

The hooks run inside X's process, under X's UID. They cannot write into this module's
storage, and anything written into X's own private dirs needs root to retrieve. So the
host process writes the log itself, into shared storage:

```
X process
  hook -> ProbeLog (queue) -> ProbeSink -> Download/XVideoCatcher/xvc-probe-YYYYMMDD.jsonl
```

- **Queued, not synchronous.** A file write per URL on the network hot path is not
  acceptable, so records are batched by a low-priority daemon thread. The queue is
  bounded and drops on overflow: losing records under a flood is fine, stalling X is not.
- **No provider, by design.** An earlier version routed records to a `ContentProvider`
  in this module. That cannot work: since Android 11, a process can only resolve a
  `content://` authority it declares in its own `<queries>`, and X's manifest is not
  ours to change — so every insert failed to resolve and all records were dropped
  silently. A CI check now fails the build if the APK declares any provider authority.
- **The writing app owns the file**, so no storage permission is involved on API 29+.
  One file per day keeps growth bounded.
- **The module UI cannot read the log.** Android only shows a non-media file in Downloads
  to the app that created it, and X is the writer. The UI therefore reports status and
  the exact path instead of pretending to export.

To send a log: open `Download/XVideoCatcher/`, long-press the file, share.

Quick check without a file manager, if adb is handy:

```
adb shell ls -l /sdcard/Download/XVideoCatcher/
```

## Signing

Builds are signed with a **fixed** key held in CI secrets (`XVC_KEYSTORE_B64` and
friends), not the per-machine debug key Gradle generates by default. That default is
regenerated on every CI runner, so consecutive builds had different signatures and
Android refused to install one over another — every update meant uninstalling first,
losing the LSPosed scope selection with it.

CI **fails** if the keystore secret is missing and asserts the built APK's certificate
digest equals the pinned value, so a silent fallback to a throwaway debug key cannot
ship. Changing the key later would again require uninstalling everywhere; treat the
pinned digest in the workflow as frozen.

> Builds before `0.3.0-probe` used a throwaway debug key. Upgrading from `0.1.0` or
> `0.2.0` requires one uninstall; from `0.3.0` on, in-place upgrade works.

## Build

Cloud CI only — see `.github/workflows/build-x-video-catcher.yml`. The workflow runs the
unit tests, reports real per-class test counts (Gradle is silent on success, so a green
step alone would not prove any test ran), builds the APK, verifies the signing key, then
asserts the module contract on the built artifact: `assets/xposed_init` names the entry
class, `xposedminversion` is present, no provider authority is declared, and the Xposed
API is *not* bundled into the APK (a bundled copy breaks hook dispatch).

Tests cover the JSONL line contract (an unescaped newline splits a record and corrupts
the log), the URL filter's pass and drop sets, and — under Robolectric, against a real
Android context — `ProbeSink` writing, appending without truncation, and the advertised
path matching where it actually writes. Those assert on bytes on disk rather than a
mocked resolver: a mocked sink is precisely what would have reported success while
dropping every record.

Do not add `android.aapt2FromMavenOverride` to `gradle.properties` — that is a
host-specific path and the Maven aapt2 is x86_64-only.

`tools/verify_photo_urls.py` replays a capture through the photo rules and **fetches every
rewritten URL from the live CDN**, comparing decoded image dimensions. Run it locally
after changing anything in the photo path:

```
python3 tools/verify_photo_urls.py path/to/xvc-probe-*.jsonl
```

It is deliberately not a CI step: it depends on the public CDN, so wiring it into the
build would let an outage fail an unrelated commit. Unit tests pin the rewrite's output
shape; this proves that shape actually resolves. Only unit tests caught neither the
`name=orig` 404 nor the webp downgrade — both needed a real request.

Note: `scripts/cloud_build_register_project.py` regenerates this README and the workflow
from templates. Re-apply the unit-test and APK-contract steps after re-registering.

## Usage

1. Install the APK, enable the module in LSPosed, and set its scope to X.
2. Force-stop X so it restarts with the module attached.
3. Check `Download/XVideoCatcher/` — the log file is written on attach, before you play
   anything.
4. Play a video, then share the file.

If the folder is absent, check the LSPosed log for `XVideoCatcher` before concluding the
module did not load. Every record goes to both the file and the LSPosed log, so the
LSPosed side still shows the attach line if the file path itself is what broke — which is
exactly what happened in `0.3.0` and below, where records produced before the host
Application existed were discarded and the folder never appeared on a working module.

The launcher activity reports whether the module is active in *its own* process (the
framework rewrites `ModuleStatus.isModuleActive()`, so "not active" is a real reading,
not a stored flag). It cannot confirm the hook attached inside X — the log file existing
is what proves that, which is why it is written on attach rather than on first URL.

## Downloading

The download path is seven small pieces, each with one job:

| Class | Role |
| --- | --- |
| `MediaRegistry` | remembers the masters and photos most recently fetched, so the menu has a target |
| `DownloadPlan` | picks renditions from a master, or reports that none can be used |
| `Http` | `HttpURLConnection` fetches with retry |
| `TrackDownloader` | one HLS track → one playable fMP4 file |
| `Muxer` | video track + audio track → one MP4, via `MediaMuxer` |
| `MediaSaver` | writes into shared storage through MediaStore |
| `MenuInjector` | the menu entry, the tap handler, and the result toast |

### Why the tracks are muxed and not concatenated

X serves **fragmented MP4**, confirmed by reading real segment bytes: an init segment is
`ftyp`/`free`/`moov` and a media segment is `styp`/`moof`/`mdat`. Two consequences, and they
pull in opposite directions:

- *Within* one track, `init + segments…` concatenated **is** a valid file. Verified against
  a live capture: the assembled 1080x1920 h264 track and the 128kbps aac track both parsed,
  with matching durations (24.017s / 24.009s). Dropping the `#EXT-X-MAP` init segment yields
  sample data with no track description — unplayable everywhere, and it looks like a corrupt
  download rather than a missing box.
- *Across* tracks, concatenation is **wrong**. The result holds two unrelated `moov` headers
  and players read the first only, so "video with sound" comes out silent while the download
  reports success. `Muxer` therefore remuxes both tracks into one container with
  `MediaMuxer` — sample copying, no re-encode, no quality loss, no codec dependency.

Timestamps are copied from the extractor rather than generated: the measured audio and video
durations do not divide evenly, so synthesised timing drifts audio out of sync.

End-to-end verified on two real videos, master → tracks → mux → probe: `1080x1920 / 45.7s /
13MB` and `1080x1596 / 24min / 371MB` (484 video + 484 audio segments), both h264+aac. Both
selected the **128000** audio track — X's own player had only ever requested 32000.

### Where files go, and why not Downloads

MediaStore under `Movies/` and `Pictures/`, not `Downloads/`. This code runs inside **X's**
process, so an app-private write lands in X's sandbox where neither this module's UI nor the
gallery can reach it. MediaStore makes the writing process the owner, so no runtime
permission is needed on API 29+. `Downloads/` is worse than useless here: Android shows a
non-media file there only to its creator, so a video saved there would be invisible to the
gallery while the download reported success. Writes are bracketed with `IS_PENDING` so a
half-written file never appears, and a failed write deletes its row rather than leaving a
0-byte "video".

Names carry CDN identity (`x_<mediaId>_1080x1920.mp4`, `x_<photoKey>.jpg`), which is what
makes a re-tap resolve to "already saved" instead of a second copy under a new timestamp.
Photo extension and MIME come from the stored `format`, never a guess — forcing jpg onto a
PNG re-encodes it.

### The menu entry

Hooks target `Activity.onCreateOptionsMenu` / `onPrepareOptionsMenu` / `onOptionsItemSelected`
— framework names, because X's own are R8-obfuscated and pairip-wrapped and change every
release. Both creation methods are hooked with a `findItem` duplicate guard, since some
screens build the menu once and others rebuild it as the viewed item changes.

`onOptionsItemSelected` sets `param.result = true` **only** for our own item ids; every other
item falls through to X untouched. Swallowing all menu taps is the kind of breakage that
looks like the module broke the app.

The entry appears only when `MediaRegistry` holds something downloadable. On a media post the
playlist is necessarily fetched before anything can display, so the registry is populated by
the time the menu opens; a permanent "下载" that reports "nothing here" on a text post just
teaches the user to distrust it.

Video and photo are tracked in **separate** slots. A video post also fetches a poster from
`pbs.twimg.com` *after* the playlist, so a single "latest media" slot would hand over a
thumbnail every time a video was requested. Poster paths are rejected on the way into the
photo slot for the same reason.

Downloads run on a daemon thread and report by toast. Tapping download must return
immediately — downloading on the UI thread freezes X and trips its own ANR watchdog. Failures
name the stage (`master` / `video` / `audio` / `mux` / `save`), because "下载失败" alone cannot
distinguish an expired playlist from a storage problem from a container problem.

### Photo quality is not free

`highestQualityPhoto` rewrites to `name=4096x4096`, and it matters: re-measured live against
the captures, one photo went 239244 → 701081 bytes (2.93x) versus the `name=large` X itself
requested, while three were already at full size and stayed byte-identical. Saving the URL as
captured silently saves a downscaled copy.

## Status

Video and photo download implemented, with the menu entry, muxing, and MediaStore output.
84 unit tests (10 fail on ARM only — Robolectric has no aarch64 conscrypt; they pass on CI
x86_64). CI gates every download class at the dex level, with a self-test that fails the
build if the checker accepts a fabricated method name.

Known limits, measured rather than assumed:

- **7 of 33 captured videos are not fully downloadable.** 5 captured only a video variant
  (downloadable at reduced quality); 2 captured only segments and cannot be assembled,
  because each segment key is random and the missing ones cannot be enumerated. Both cases
  report `NoRenditions` instead of guessing a URL.
- **The download target is inferred** from the most recently fetched media, because X's view
  model is not readable under obfuscation. In practice the viewed item is the last fetched
  one; a post opened without its media loading has nothing to offer.
- **`tweet_video` (GIFs) is still unconfirmed** — no capture has produced one.
