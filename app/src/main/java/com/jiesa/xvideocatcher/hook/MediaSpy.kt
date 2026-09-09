package com.jiesa.xvideocatcher.hook

import android.net.Uri
import com.jiesa.xvideocatcher.DiagLog
import com.jiesa.xvideocatcher.MediaUrls
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Modifier

/**
 * Captures real media URLs out of the host's own player, in-process.
 *
 * ## Why this exists at all
 *
 * Versions 1.5-1.11 all tried to reach the media through the *tweet object*: hook a share sheet,
 * find the tweet, walk its graph, pull `media_url_https`. Device logs from 1.11 ended that whole
 * family: on the live share path (`com.x.share.impl`) every graph walk reported
 * `media extracted: 0 item(s)`, because the live sheet is handed a **status URL**, not a tweet.
 *
 * The mistake was looking in the wrong place. The host is a video player: to play anything it must
 * itself resolve a real, playable URL and hand it to media3. The URL is therefore already in the
 * process, fully resolved, with no obfuscated model graph in the way. This reads it there, which is
 * what being an in-process hook is for.
 *
 * ## The anchor, and why it is stable
 *
 * `androidx.media3.datasource.DataSpec` -- `androidx.media3.datasource.j` after R8 -- is the request
 * descriptor every media3 data source is opened with. Identified by field layout, not by name:
 *
 *     (Uri, long, int, byte[], Map, long, long, String, int)
 *      a     b     c    d       e    f     g     h       i
 *
 * matching `uri, uriPositionOffset, httpMethod, httpBody, httpRequestHeaders, position, length,
 * key, flags`. Field `a` is the URL.
 *
 * Two properties make this a better anchor than anything in the 1.2-1.11 line:
 *
 *  - **It is a bottleneck, not a branch.** In 12.13.0-release.0 `DataSpec.<init>` has 8 call sites
 *    and *all 8 are inside media3 itself* (`HlsMediaSource`, `hls.playlist.b$b`, `hls.g`, `hls.q`,
 *    `exoplayer.source.m0$b`, `datasource.j$a`, `datasource.f`, `j` itself). Nothing in X's own code
 *    constructs one, so every playback request in the app passes through here. There is no second
 *    path to miss -- which is exactly how 1.11 failed, hooking one of two share sheets.
 *  - **`androidx.media3.*` package names survive R8** in this build (verified: `ExoPlayer`,
 *    `HlsMediaSource`, `HttpDataSource$*` keep their names). The 1.2-1.11 anchors were obfuscated
 *    `com.twitter.*` classes, which is why they moved between host releases.
 *
 * Resolution is still by shape, never by the name `j`, so a rename alone does not break it.
 *
 * ## Why the constructor and not `DataSource.open`
 *
 * `open` is `d.i(DataSpec)` on an interface with 14 implementors, and Xposed cannot hook an
 * interface method -- each implementor would need its own hook, and X picks between them through a
 * Dagger-injected factory (`k$a`). The constructor is one method, upstream of all of them.
 *
 * ## What it keeps
 *
 * Classification is delegated entirely to [MediaUrls], the module's existing rules, which already
 * exclude avatars, emoji, card images and ad payloads, and already distinguish master from variant
 * playlists. Writing a second set of URL rules here would mean two definitions of "tweet media"
 * that could disagree.
 *
 * Nothing is written to disk and nothing leaves the device: URLs are held in memory until the user
 * taps download.
 */
internal object MediaSpy {

    /**
     * A media URL seen by the player.
     *
     * @param url fully-resolved, playable URL
     * @param kind ranking class, see [classify]
     * @param seenAt wall clock, so the most recently played item can win
     */
    data class Seen(val url: String, val kind: Kind, val seenAt: Long)

    /**
     * Ranking classes, in preference order -- see [RANK].
     *
     * [VIDEO_INIT] was called `PROGRESSIVE_MP4` through 1.18 and that name was the bug: the URL it
     * matches (`/vid/avc1/0/0/<WxH>/<k>.mp4`) is an fMP4 initialisation segment, not a complete
     * file. It is kept as a class only so the log still shows it being seen; it is never
     * downloadable. See [Hls] for the measurement.
     */
    enum class Kind { VIDEO_INIT, HLS_MASTER, HLS_VARIANT, PHOTO }

    fun install(classLoader: ClassLoader) {
        val spec = resolveDataSpec(classLoader)
        if (spec == null) {
            DiagLog.line("$MARK DataSpec MISS -- no media capture this session")
            DiagLog.flushNow()
            return
        }
        val ctor = spec.declaredConstructors.firstOrNull { it.parameterTypes.size == CTOR_ARITY }
        if (ctor == null) {
            DiagLog.line("$MARK ${spec.name} has no $CTOR_ARITY-arg constructor")
            DiagLog.flushNow()
            return
        }

        runCatching {
            XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // Never let a capture failure propagate: this runs on the host's playback path,
                    // and an exception here would break video for the user to save a diagnostic.
                    runCatching {
                        val uri = param.args.getOrNull(0) as? Uri ?: return
                        record(uri.toString())
                    }
                }
            })
            DiagLog.line("$MARK armed on ${spec.name}.<init>")
        }.onFailure {
            DiagLog.line("$MARK hook failed on ${spec.name}: $it")
        }
        DiagLog.flushNow()
        installOkHttp(classLoader)
    }

    private fun installOkHttp(classLoader: ClassLoader) {
        val reqBuilder = runCatching {
            classLoader.loadClass("okhttp3.Request\$Builder")
        }.getOrNull()
        if (reqBuilder == null) {
            DiagLog.line("$MARK Request.Builder MISS")
            return
        }

        val buildMethod = reqBuilder.declaredMethods.firstOrNull {
            it.name == "build" && it.parameterTypes.isEmpty()
        } ?: return

        runCatching {
            de.robv.android.xposed.XposedBridge.hookMethod(buildMethod, object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val req = param.result ?: return
                        val urlMethod = req.javaClass.getMethod("url")
                        val httpUrl = urlMethod.invoke(req) ?: return
                        val urlStr = httpUrl.toString()
                        if (MediaUrls.isPhoto(urlStr)) {
                            record(urlStr)
                        }
                    }
                }
            })
            DiagLog.line("$MARK armed on Request.Builder.build")
        }.onFailure {
            DiagLog.line("$MARK OkHttp hook failed: $it")
        }
    }

    /**
     * Finds `DataSpec` by shape.
     *
     * Matched on declared field types in order, because R8 renamed the class to `j` and will pick
     * something else next release.
     */
    private fun resolveDataSpec(classLoader: ClassLoader): Class<*>? {
        for (name in CANDIDATES) {
            val cls = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
            if (hasDataSpecShape(cls)) return cls
        }
        return null
    }

    internal fun hasDataSpecShape(cls: Class<*>): Boolean {
        val fields = cls.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        if (fields.map { it.type.name } != SPEC_FIELDS) return false
        // Field types alone are NOT unique. An ablation against the 12.13.0-release.0 APK matched
        // two classes: `DataSpec` and `DataSpec.Builder` (`j$a`), which declares the same nine types
        // in the same order. Hooking the builder would capture half-assembled URLs and miss every
        // spec built through the 9-arg constructor directly -- a silent partial capture.
        //
        // DataSpec is immutable and its builder is not: nine final fields versus none. Structural,
        // so R8 cannot erase it.
        return fields.all { Modifier.isFinal(it.modifiers) }
    }

    /**
     * Records a URL if [MediaUrls] considers it tweet media.
     *
     * Deduplicated by URL: HLS playback re-opens the same playlist repeatedly, and without this one
     * video's requests would evict everything else from the cap.
     */
    /**
     * Records a photo URL discovered outside the OkHttp/DataSpec hooks (share-sheet harvest).
     * Same path as a network capture so [best] does not care where it came from.
     */
    fun notePhoto(url: String) {
        if (!MediaUrls.isTweetPhoto(url)) return
        record(url)
    }

    private fun record(url: String) {
        val kind = classify(url) ?: return
        synchronized(seen) {
            // VIDEO_INIT floods the capture set during playback (device 1.20 log: 285 init
            // vs 84 photo). Keep the latest one for diagnostics/grouping but never let it
            // consume the CAP budget that should hold masters and tweet photos.
            if (kind == Kind.VIDEO_INIT) {
                seen.removeAll { it.kind == Kind.VIDEO_INIT }
                seen.add(Seen(url, kind, System.currentTimeMillis()))
            } else {
                seen.removeAll { it.url == url }
                // For photos, keep one entry per photoKey at the highest-quality URL seen.
                if (kind == Kind.PHOTO) {
                    val key = MediaUrls.photoKey(url)
                    if (key != null) {
                        seen.removeAll {
                            it.kind == Kind.PHOTO && MediaUrls.photoKey(it.url) == key
                        }
                    }
                }
                seen.add(Seen(url, kind, System.currentTimeMillis()))
            }
            while (seen.size > CAP) {
                val dropIdx = seen.indexOfFirst { it.kind == Kind.VIDEO_INIT }
                    .takeIf { it >= 0 }
                    ?: seen.indexOfFirst { it.kind == Kind.HLS_VARIANT }
                        .takeIf { it >= 0 }
                    ?: 0
                seen.removeAt(dropIdx)
            }
            updateFocus(kind, url)
        }
        // Log each distinct URL once. HLS playback re-requests the same playlist and init segment
        // continuously: the 20260828 session was 318 lines, 280 of them MEDIASPY, carrying only 53
        // distinct payloads -- one VIDEO_INIT URL appeared 18 times. That repetition is what buried
        // the lines that matter, so the flood is dropped here rather than filtered when reading.
        if (logged.add(url)) {
            DiagLog.line("$MARK $kind ${url.take(URL_LOG_LIMIT)}")
        }
    }

    /**
     * URLs already written to the log, so a repeat request is silent.
     *
     * Separate from [seen], which is the capture set the downloader picks from and is capped and
     * evicted: an eviction must not make a URL loggable again. Bounded independently so a long
     * session cannot grow it without limit.
     */
    private val logged = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            val added = super.add(element)
            if (added && size > LOG_DEDUP_CAP) iterator().let { it.next(); it.remove() }
            return added
        }
    }

    /**
     * Updates [focus] from a newly recorded capture.
     *
     * Rules (1.23):
     * - Display photo → lock that photoKey (strong: user is looking at an image).
     * - VIDEO_INIT → lock that mediaId **only if** focus is not a display photo (init flood
     *   must not demote a photo the user just focused; device 1.21).
     * - HLS_MASTER → lock mediaId when focus is empty or already that video; never steal a
     *   display-photo focus on master alone (prefetch while scrolling).
     * - Tiny/non-display photos and variants never set focus.
     */
    private fun updateFocus(kind: Kind, url: String) {
        when (kind) {
            Kind.PHOTO -> {
                if (!MediaUrls.isDisplayPhoto(url)) return
                val key = MediaUrls.photoKey(url) ?: return
                focus = Focus(Kind.PHOTO, key, System.currentTimeMillis())
                DiagLog.line("$MARK FOCUS photo key=$key")
            }
            Kind.VIDEO_INIT -> {
                val id = MediaUrls.mediaId(url) ?: return
                val cur = focus
                if (cur?.kind == Kind.PHOTO) return
                if (cur?.kind == Kind.HLS_MASTER && cur.key == id) return
                focus = Focus(Kind.HLS_MASTER, id, System.currentTimeMillis())
                DiagLog.line("$MARK FOCUS video media=$id via init")
            }
            Kind.HLS_MASTER -> {
                val id = MediaUrls.mediaId(url) ?: return
                val cur = focus
                // Display-photo focus is stronger than a bare master (timeline prefetch).
                if (cur?.kind == Kind.PHOTO) return
                if (cur?.kind == Kind.HLS_MASTER && cur.key == id) {
                    // Refresh lock time for the same group (tag= rotation).
                    focus = Focus(Kind.HLS_MASTER, id, System.currentTimeMillis())
                    return
                }
                // A *watched* video (VIDEO_INIT for the locked media id) is protected from
                // bare master prefetch of other ids. A master-only focus is weak: pure
                // recency between masters must still work (prefersMostRecentWithinAKind).
                if (cur?.kind == Kind.HLS_MASTER) {
                    val curStillPlaying = seen.any {
                        it.kind == Kind.VIDEO_INIT && MediaUrls.mediaId(it.url) == cur.key
                    }
                    if (curStillPlaying) {
                        DiagLog.line(
                            "$MARK FOCUS keep video media=${cur.key} (playing); ignore master $id",
                        )
                        return
                    }
                }
                focus = Focus(Kind.HLS_MASTER, id, System.currentTimeMillis())
                DiagLog.line("$MARK FOCUS video media=$id via master")
            }
            Kind.HLS_VARIANT -> Unit
        }
    }

    /**
     * Classifies a URL for ranking, or returns null for anything that is not addressable media.
     *
     * Every predicate here comes from [MediaUrls]. Segments and audio-only renditions are parts of a
     * stream rather than something a user can be handed, and they are excluded by not matching any
     * branch -- see the note in the body about why there is no separate exclusion test.
     */
    internal fun classify(url: String): Kind? {
        if (!MediaUrls.isInteresting(url)) return null
        // Positive identification only, no exclusion list. An earlier version also tested
        // `isAudioTrack` up front; ablation proved that clause was dead weight -- removing it left
        // the suite green, because an audio rendition matches none of the four branches below and
        // falls through to null anyway. Two paths to one outcome means neither can be tested, so the
        // branches are the single authority on what counts as downloadable media.
        return when {
            MediaUrls.isMasterPlaylist(url) -> Kind.HLS_MASTER
            MediaUrls.isManifest(url) -> Kind.HLS_VARIANT
            MediaUrls.isPhoto(url) -> Kind.PHOTO
            // The init segment. 1.13-1.18 treated this as a complete file because it ends in
            // `.mp4`; it is an fMP4 header with no frames, and saving it produced the small
            // unplayable files the device reported. Classified so it appears in the log, never
            // offered for download -- see [best].
            MediaUrls.isVideoTrack(url) && url.substringBefore('?').endsWith(".mp4", ignoreCase = true) ->
                Kind.VIDEO_INIT
            else -> null
        }
    }

    /**
     * The URL to download, or null when the player has not fetched a usable one.
     *
     * Only a **master playlist** qualifies. Through 1.18 this preferred `PROGRESSIVE_MP4` because
     * `HostDownloader` could write it directly -- but what it wrote was an init segment, so the
     * "directly downloadable" advantage was writing a broken file quickly. Masters are the only
     * URLs from which a complete video is reachable.
     *
     * Selection is **most recent first**, and that ordering is the second fix here. 1.18 ranked by
     * pixel area before recency, so the largest video of the whole session won every tap: the
     * device log shows taps on three different tweets all resolving to one 1080x1920 file from the
     * first. Quality is chosen later, from the master's own ladder ([Hls.bestVariant]), which is
     * where it belongs -- the ladder lists what the CDN has, while the capture set only lists what
     * the player happened to request.
     *
     * The newest capture identifies the media group; the master for *that* group is preferred, with
     * any master as a fallback so a tap still works if the group's master scrolled out of the cap.
     */
    fun best(tweetId: String? = null): List<Seen> = synchronized(seen) {
        // status id ≠ media id (device logs). tweetId is diagnostic only for capture matching.
        if (tweetId != null) {
            DiagLog.line("$MARK best(status=$tweetId)")
        }

        // VIDEO_INIT must not drive selection: playback and timeline prefetch flood it and
        // made every photo lose a pure recency contest (device 1.21).
        val masters = seen.filter { it.kind == Kind.HLS_MASTER }
        val displayPhotos = seen
            .filter { it.kind == Kind.PHOTO && MediaUrls.isDisplayPhoto(it.url) }
            .sortedByDescending { it.seenAt }
        val anyTweetPhotos = seen
            .filter { it.kind == Kind.PHOTO && MediaUrls.isTweetPhoto(it.url) }
            .sortedByDescending { it.seenAt }

        // 1.23 focus lock: if the locked group is still in the capture set, serve it.
        // Dropped from the cap → fall through to recency so a tap still works.
        val locked = focus
        if (locked != null) {
            when (locked.kind) {
                Kind.PHOTO -> {
                    val pool = displayPhotos.ifEmpty { anyTweetPhotos }
                    val hits = pool
                        .filter { MediaUrls.photoKey(it.url) == locked.key }
                        .distinctBy { MediaUrls.photoKey(it.url) }
                    if (hits.isNotEmpty()) {
                        DiagLog.line("$MARK best via FOCUS photo key=${locked.key}")
                        return hits
                    }
                }
                Kind.HLS_MASTER -> {
                    val hit = masters
                        .filter { MediaUrls.mediaId(it.url) == locked.key }
                        .maxByOrNull { it.seenAt }
                    if (hit != null) {
                        DiagLog.line("$MARK best via FOCUS video media=${locked.key}")
                        return listOf(hit)
                    }
                }
                else -> Unit
            }
        }

        val newestMaster = masters.maxByOrNull { it.seenAt }
        val newestDisplay = displayPhotos.firstOrNull()
        val newestPhoto = newestDisplay ?: anyTweetPhotos.firstOrNull()

        // Prefer a real photo when it is the freshest *meaningful* capture, or when it was
        // harvested/seen at least as recently as the newest master. Tinies alone never win.
        if (newestDisplay != null) {
            val masterAt = newestMaster?.seenAt ?: -1L
            if (newestDisplay.seenAt >= masterAt) {
                val key = MediaUrls.photoKey(newestDisplay.url)
                return displayPhotos
                    .filter { MediaUrls.photoKey(it.url) == key }
                    .distinctBy { MediaUrls.photoKey(it.url) }
                    .ifEmpty { listOf(newestDisplay) }
            }
        }

        // Harvested or captured tweet photo with no competing newer master → save the photo.
        if (newestPhoto != null && newestMaster == null) {
            val key = MediaUrls.photoKey(newestPhoto.url)
            val pool = if (newestDisplay != null) displayPhotos else anyTweetPhotos
            return pool.filter { MediaUrls.photoKey(it.url) == key }
                .distinctBy { MediaUrls.photoKey(it.url) }
                .ifEmpty { listOf(newestPhoto) }
        }

        if (newestMaster == null) return emptyList()
        // Grouping still uses VIDEO_INIT/VARIANT: the segment stream identifies which video
        // is on screen when a newer master was only prefetched (1.19 ablation).
        // PHOTO recency contests above deliberately ignore VIDEO_INIT so init flood cannot
        // demote a display photo.
        val newestId = seen
            .filter { it.kind != Kind.PHOTO }
            .maxByOrNull { it.seenAt }
            ?.let { MediaUrls.mediaId(it.url) }
        val hit = masters.filter { MediaUrls.mediaId(it.url) == newestId }.maxByOrNull { it.seenAt }
            ?: newestMaster
        return listOf(hit)
    }

    /** Everything captured, newest first. Diagnostics only. */
    fun all(): List<Seen> = synchronized(seen) { seen.reversed() }

    fun clear() = synchronized(seen) {
        seen.clear()
        focus = null
    }

    private val seen = mutableListOf<Seen>()

    /**
     * Locked media group the user is currently looking at.
     *
     * Timeline prefetch and VIDEO_INIT flood continuously re-order pure recency; without a
     * focus, opening the share sheet a second later can offer a different tweet's media than
     * the one on screen (device 1.18–1.22). Focus is updated only by strong signals:
     * display photos, masters the user is actually playing (VIDEO_INIT for the same media id),
     * and never by tiny thumbs or lone prefetched masters while a photo is focused.
     */
    private data class Focus(val kind: Kind, val key: String, val lockedAt: Long)

    @Volatile private var focus: Focus? = null

    /**
     * Returns the photoKey of the photo the user is currently looking at, or null
     * if focus is empty, stale, or on a video. Used by the share-sheet to download
     * only the displayed photo rather than every photo in a multi-image tweet.
     */
    fun focusedPhotoKey(): String? {
        val f = focus ?: return null
        if (f.kind != Kind.PHOTO) return null
        return f.key
    }

    /**
     * Given a list of photo keys from [StatusMedia.resolve], returns the one the user
     * most recently viewed, or null if none of them were seen.
     *
     * This solves the problem that [focusedPhotoKey] is global: between opening a tweet
     * and tapping share, the user may scroll past other tweets whose photos overwrite
     * the focus. But the photo the user actually looked at in the target tweet is still
     * in [seen], and it was seen more recently than any other photo from that same tweet.
     *
     * The share sheet provides the status id; syndication provides the photo keys for
     * that status; this method intersects those keys with the capture history to find
     * the most recently viewed one — the photo the user was looking at when they tapped
     * share, even if focus has since moved to another tweet.
     */
    fun mostRecentSeenPhotoKey(photoKeys: Collection<String>): String? = synchronized(seen) {
        if (photoKeys.isEmpty()) return null
        // Only consider display-quality photos (name=large or bigger).
        // Timeline prefetch loads name=tiny/small for every visible tweet;
        // those captures would make every photo in a multi-image tweet
        // look "seen" even though the user never opened the image viewer.
        val candidates = seen
            .filter { it.kind == Kind.PHOTO && MediaUrls.isDisplayPhoto(it.url) }
            .sortedBy { it.seenAt }
        // When multiple photos from the same tweet were seen (image viewer
        // preloads adjacent pages), pick the FIRST one seen — X loads the
        // initially displayed photo before its neighbours, so the earliest
        // display-quality capture is the strongest signal for which photo
        // the user was looking at when they opened the tweet.
        candidates.firstOrNull { MediaUrls.photoKey(it.url) in photoKeys }
            ?.let { MediaUrls.photoKey(it.url) }
    }

    private const val MARK = "MEDIASPY"

    /** Distinct URLs remembered for log de-duplication. */
    private const val LOG_DEDUP_CAP = 512

    /** `DataSpec`'s 9-arg constructor. */
    private const val CTOR_ARITY = 9

    private const val CAP = 48
    private const val URL_LOG_LIMIT = 160



    /**
     * `DataSpec`'s field types in declaration order, as `Class.getName()` spells them.
     *
     * Note `[B` rather than `byte[]`: that is the JVM's name for a byte array, and writing the source
     * form here made the predicate reject the real class while still rejecting every decoy — a check
     * that looked strict but was simply always false. The fixture test is what surfaced it.
     */
    private val SPEC_FIELDS = listOf(
        "android.net.Uri", "long", "int", "[B", "java.util.Map",
        "long", "long", "java.lang.String", "int",
    )

    /**
     * Names to shape-test, unobfuscated first in case the host ships a debug build.
     *
     * A fixed list rather than the brute-force package enumeration [HostResolver] uses: `DataSpec`
     * is a public API type in a package R8 left named, so single letters cover it at a fraction of
     * the startup cost.
     */
    private val CANDIDATES: List<String> = buildList {
        add("androidx.media3.datasource.DataSpec")
        for (c in 'a'..'z') add("androidx.media3.datasource.$c")
    }
}
