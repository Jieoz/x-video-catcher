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

    /** Ranking classes, in preference order -- see [RANK]. */
    enum class Kind { PROGRESSIVE_MP4, HLS_MASTER, HLS_VARIANT, PHOTO }

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
    private fun record(url: String) {
        val kind = classify(url) ?: return
        synchronized(seen) {
            seen.removeAll { it.url == url }
            seen.add(Seen(url, kind, System.currentTimeMillis()))
            while (seen.size > CAP) seen.removeAt(0)
        }
        DiagLog.line("$MARK $kind ${url.take(URL_LOG_LIMIT)}")
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
            // Complete progressive file only. Device 1.12 logs also show fMP4 media segments
            // (path like /vid/.../0/3000/....m4s) under /vid/; those are pieces of an HLS stream, not a file
            // the user can play. isVideoTrack accepts them, so the extension is the load-bearing
            // discriminator between "save this" and "log and ignore".
            MediaUrls.isVideoTrack(url) && url.substringBefore('?').endsWith(".mp4", ignoreCase = true) ->
                Kind.PROGRESSIVE_MP4
            else -> null
        }
    }

    /**
     * The best video URL currently known, or null if the player has not fetched one.
     *
     * Photos are excluded: they are captured for diagnostics, but the tweet-based path already
     * handles images and picking one here would race it.
     *
     * A complete progressive MP4 outranks playlists: [HostDownloader.downloadCaptured] can save it
     * today, and the 1.12 device log proved X serves both on the same playback
     * (`HLS_MASTER` plus a complete progressive .mp4 under /vid/). Preferring the master left the download path with a
     * non-fetchable URL while a playable file was already in the capture set.
     *
     * Within progressive files, higher resolution wins, then recency — the player often opens the
     * init segment of every rung; the user wants the sharpest complete file seen so far.
     */
    fun best(): Seen? = synchronized(seen) {
        seen.filter { it.kind != Kind.PHOTO }
            .maxWithOrNull(
                compareBy<Seen> { RANK.indexOf(it.kind) }
                    .thenBy { progressiveRank(it) }
                    .thenBy { it.seenAt },
            )
    }

    /** Pixel area for progressive URLs; 0 for everything else so it does not disturb other kinds. */
    private fun progressiveRank(seen: Seen): Long {
        if (seen.kind != Kind.PROGRESSIVE_MP4) return 0L
        val (w, h) = MediaUrls.resolution(seen.url) ?: return 0L
        return w.toLong() * h.toLong()
    }

    /** Everything captured, newest first. Diagnostics only. */
    fun all(): List<Seen> = synchronized(seen) { seen.reversed() }

    fun clear() = synchronized(seen) { seen.clear() }

    private val seen = mutableListOf<Seen>()

    private const val MARK = "MEDIASPY"

    /** `DataSpec`'s 9-arg constructor. */
    private const val CTOR_ARITY = 9

    private const val CAP = 32
    private const val URL_LOG_LIMIT = 160

    /**
     * Preference order, best last -- [best] takes the maximum.
     *
     * `indexOf` returns -1 for a kind absent here, which sorts below everything present. That is
     * the intended fallback rather than a crash if a new kind is added and not ranked.
     */
    // Best last: progressive is what downloadCaptured can write; master/variant are diagnostics.
    private val RANK = listOf(Kind.HLS_VARIANT, Kind.HLS_MASTER, Kind.PROGRESSIVE_MP4)

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
