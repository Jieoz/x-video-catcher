package com.jiesa.xvideocatcher.hook

import com.jiesa.xvideocatcher.DiagLog
import com.jiesa.xvideocatcher.MediaUrls
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * Pulls tweet photo URLs out of whatever object the share sheet is holding.
 *
 * MediaSpy only sees what OkHttp already fetched. Opening share on a photo you are
 * looking at does not always re-fetch it, and timeline video traffic keeps updating
 * "recency", so selection by capture time alone still picks a video. The sheet's
 * own object graph, however, is about *this* status — any `pbs.twimg.com/media/`
 * string on it is the photo set for the row being shared.
 */
internal object CaptureHarvest {

    private const val MAX_NODES = 400
    private const val MAX_DEPTH = 5

    /** Record every tweet-photo URL reachable from [roots] into [MediaSpy]. */
    fun recordPhotosFrom(vararg roots: Any?) {
        val found = linkedSetOf<String>()
        for (root in roots) {
            if (root == null) continue
            walk(root, found)
        }
        if (found.isEmpty()) {
            DiagLog.line("HARVEST photos=0")
            return
        }
        for (url in found) {
            MediaSpy.notePhoto(url)
        }
        DiagLog.line("HARVEST photos=${found.size} sample=${found.first().take(80)}")
    }

    private fun walk(root: Any, out: MutableSet<String>) {
        val seen = IdentityHashMap<Any, Boolean>()
        val q: ArrayDeque<Pair<Any, Int>> = ArrayDeque()
        q.add(root to 0)
        var visits = 0
        while (q.isNotEmpty() && visits < MAX_NODES) {
            val (node, depth) = q.removeFirst()
            if (seen.put(node, true) != null) continue
            visits++
            when (node) {
                is String -> {
                    if (MediaUrls.isTweetPhoto(node)) out.add(node)
                    continue
                }
                is CharSequence -> {
                    val s = node.toString()
                    if (MediaUrls.isTweetPhoto(s)) out.add(s)
                    continue
                }
            }
            if (depth >= MAX_DEPTH) continue
            val cn = node.javaClass.name
            if (cn.startsWith("android.") && !cn.startsWith("android.net.Uri")) continue
            if (cn.startsWith("java.") || cn.startsWith("kotlin.")) continue
            when (node) {
                is Array<*> -> node.filterNotNull().forEach { q.add(it to depth + 1) }
                is Collection<*> -> node.filterNotNull().forEach { q.add(it to depth + 1) }
                is Map<*, *> -> {
                    node.keys.filterNotNull().forEach { q.add(it to depth + 1) }
                    node.values.filterNotNull().forEach { q.add(it to depth + 1) }
                }
                else -> {
                    var c: Class<*>? = node.javaClass
                    while (c != null && c != Any::class.java) {
                        for (f in c.declaredFields) {
                            if (Modifier.isStatic(f.modifiers)) continue
                            if (f.type.isPrimitive) continue
                            f.isAccessible = true
                            val v = runCatching { f.get(node) }.getOrNull() ?: continue
                            q.add(v to depth + 1)
                        }
                        c = c.superclass
                    }
                }
            }
        }
    }
}
