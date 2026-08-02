package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import com.jiesa.xvideocatcher.DownloadTarget
import com.jiesa.xvideocatcher.MediaUrls
import java.lang.reflect.Modifier

/** One downloadable item resolved off a host tweet: where it lives and what to call it. */
internal data class HostMedia(
    val url: String,
    val spec: DownloadTarget.Spec,
)

/**
 * Pulls downloadable media out of a live host tweet object.
 *
 * This is what makes the module a module rather than a second app: the tweet is already fully
 * parsed in X's memory when the share sheet opens, so the media list, the rendition URLs and the
 * bitrates are all sitting there. No API call, no network hook, no tweet-id round trip.
 *
 * Traversal is shape-driven rather than path-driven. Instead of walking a fixed
 * `wrapper.a.Z.extendedEntities.media` chain — which breaks the moment any link is renamed — it
 * searches the object graph for instances of the media-entity class. That is a slower lookup and
 * a much less fragile one.
 */
internal object TweetMedia {

    /** How deep to search the tweet graph. Media sits 2-4 hops in; 6 covers quoted tweets. */
    private const val MAX_DEPTH = 6

    /**
     * Media in [tweet], best rendition per item, or an empty list when there is nothing to
     * download (text-only tweet, or a host change that broke traversal).
     */
    fun extract(tweet: Any): List<HostMedia> {
        val mediaClass = runCatching {
            tweet.javaClass.classLoader?.loadClass(HostClasses.MEDIA_ENTITY)
        }.getOrNull()
        if (mediaClass == null) {
            // Not fatal — traversal falls back to shape matching — but it means the recorded
            // media-entity name drifted, which is worth knowing before anything else fails.
            DiagLog.line("media entity class ${HostClasses.MEDIA_ENTITY} not found; using shape match")
        }

        val entities = collectMediaEntities(tweet, mediaClass)
        if (entities.isEmpty()) {
            // Three different causes produce the same empty result, and the caller can only
            // report "no downloadable media". Naming them here is what separates a text-only
            // post from a host change that broke traversal.
            DiagLog.line(
                "no media entities in tweet graph (depth<=$MAX_DEPTH, " +
                    "root=${tweet.javaClass.name}, byClass=${mediaClass != null})"
            )
            return emptyList()
        }

        val out = entities.mapNotNull { toTarget(it) }.distinctBy { it.url }
        if (out.isEmpty()) {
            DiagLog.line(
                "found ${entities.size} media entity(s) but no usable rendition; " +
                    "types=${entities.joinToString { mediaTypeName(it) ?: "?" }}"
            )
        }
        return out
    }

    /**
     * Breadth-first walk collecting media entities.
     *
     * When [mediaClass] resolved, membership is an `isInstance` check. When it did not — host
     * renamed the class — anything carrying both the media-type enum and a video-info field is
     * treated as a media entity, since that pair is what a media entity structurally *is*.
     */
    private fun collectMediaEntities(root: Any, mediaClass: Class<*>?): List<Any> {
        val found = mutableListOf<Any>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        var frontier = listOf(root)
        var depth = 0

        while (frontier.isNotEmpty() && depth < MAX_DEPTH) {
            val next = mutableListOf<Any>()
            for (node in frontier) {
                if (!seen.add(node)) continue

                if (isMediaEntity(node, mediaClass)) {
                    found.add(node)
                    // Media entities are leaves for this purpose; no need to walk into them.
                    continue
                }

                for (child in childrenOf(node)) next.add(child)
            }
            frontier = next
            depth++
        }
        return found
    }

    private fun isMediaEntity(node: Any, mediaClass: Class<*>?): Boolean {
        if (mediaClass != null) return mediaClass.isInstance(node)
        return HostShapes.mediaTypeField(node.javaClass) != null &&
            HostShapes.videoInfoField(node.javaClass) != null
    }

    /** Host-object children worth walking: nested model objects and collection elements. */
    private fun childrenOf(node: Any): List<Any> {
        val out = mutableListOf<Any>()
        var c: Class<*>? = node.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                val type = f.type
                if (type.isPrimitive) continue
                // Skip the JDK/Kotlin runtime: no host model object lives under those roots, and
                // walking into e.g. a HashMap's internals wastes the depth budget.
                f.isAccessible = true
                val value = runCatching { f.get(node) } .getOrNull() ?: continue
                when {
                    value is Collection<*> -> value.filterNotNull().forEach { out.add(it) }
                    value is Array<*> -> value.filterNotNull().forEach { out.add(it) }
                    value is Map<*, *> -> value.values.filterNotNull().forEach { out.add(it) }
                    isHostObject(value) -> out.add(value)
                }
            }
            c = c.superclass
        }
        return out
    }

    /** True for objects belonging to the host app, which are the only ones worth traversing. */
    private fun isHostObject(value: Any): Boolean {
        val n = value.javaClass.name
        return n.startsWith("com.twitter.") || n.startsWith("com.x.") || n.startsWith("tv.periscope.")
    }

    /** Best rendition of one media entity, or null if there is nothing downloadable. */
    private fun toTarget(entity: Any): HostMedia? {
        val typeName = mediaTypeName(entity)
        return when (typeName) {
            HostClasses.TYPE_VIDEO, HostClasses.TYPE_ANIMATED_GIF -> videoTarget(entity)
            HostClasses.TYPE_IMAGE -> imageTarget(entity)
            // Unknown or MODEL3D: nothing downloadable, and guessing would produce a broken file.
            else -> null
        }
    }

    private fun mediaTypeName(entity: Any): String? {
        val f = HostShapes.mediaTypeField(entity.javaClass) ?: return null
        return (runCatching { f.get(entity) }.getOrNull() as? Enum<*>)?.name
    }

    /** Highest-bitrate progressive MP4. HLS variants are skipped: a playlist is not a file. */
    private fun videoTarget(entity: Any): HostMedia? {
        val infoField = HostShapes.videoInfoField(entity.javaClass) ?: return null
        val info = runCatching { infoField.get(entity) }.getOrNull() ?: return null
        val variantsField = HostShapes.variantsField(info.javaClass) ?: return null
        val variants = runCatching { variantsField.get(info) }.getOrNull() as? List<*> ?: return null

        val best = variants.filterNotNull()
            .mapNotNull { HostShapes.readVariant(it) }
            // A rendition list mixes progressive MP4s with an HLS playlist. Only the MP4 is a
            // file; taking the .m3u8 by bitrate would save a few hundred bytes of text.
            .filter { it.url.contains(".mp4") }
            .maxByOrNull { it.bitrate }
            ?: return null

        val url = best.url.substringBefore('?')
        val mediaId = MediaUrls.mediaId(url) ?: return null
        val (w, h) = MediaUrls.resolution(url) ?: (0 to 0)
        return HostMedia(url = url, spec = DownloadTarget.videoSpec(mediaId, w, h))
    }

    private fun imageTarget(entity: Any): HostMedia? {
        val raw = imageUrl(entity) ?: return null
        val url = MediaUrls.highestQualityPhoto(raw)
        val spec = DownloadTarget.photoSpec(url) ?: return null
        return HostMedia(url = url, spec = spec)
    }

    /**
     * The `media_url_https` value. Selected by content rather than by field name: it is the
     * String on the entity pointing at the media CDN.
     */
    private fun imageUrl(entity: Any): String? {
        var c: Class<*>? = entity.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type != String::class.java) continue
                f.isAccessible = true
                val v = runCatching { f.get(entity) }.getOrNull() as? String ?: continue
                if (v.startsWith("https://pbs.twimg.com/media/")) return v
            }
            c = c.superclass
        }
        return null
    }
}
