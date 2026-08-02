package com.jiesa.xvideocatcher.hook

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Finds host fields by *shape* rather than by name.
 *
 * Obfuscated member names change on every host release; the structure does not. A field holding
 * video renditions stays "the only `List` on the video-info class" across builds even as its name
 * cycles through `c`, `b`, `f`. So each lookup here states a structural property and searches for
 * it, and the hard-coded names in [HostClasses] are used only as a fast path.
 *
 * Every resolver returns null instead of throwing. A miss must degrade to "no download entry in
 * the sheet", never to an exception inside X's UI thread.
 */
internal object HostShapes {

    /** Field of exactly [type] on [cls] or its superclasses; null unless there is exactly one. */
    fun uniqueFieldOfType(cls: Class<*>, type: Class<*>): Field? =
        instanceFields(cls).filter { it.type == type }.singleOrNull()

    /** Field whose type name is [typeName]; null unless there is exactly one. */
    fun uniqueFieldOfTypeName(cls: Class<*>, typeName: String): Field? =
        instanceFields(cls).filter { it.type.name == typeName }.singleOrNull()

    /**
     * Field holding the media-type enum, identified by the enum constants themselves.
     *
     * Matching on constant names is sound where matching on a class name is not: enum names are
     * part of the API surface (`Enum.name()`, `valueOf`) so R8 leaves them intact.
     */
    fun mediaTypeField(cls: Class<*>): Field? =
        instanceFields(cls).firstOrNull { f ->
            f.type.isEnum && f.type.enumConstants
                ?.map { (it as Enum<*>).name }
                ?.containsAll(listOf(HostClasses.TYPE_VIDEO, HostClasses.TYPE_IMAGE)) == true
        }

    /**
     * Field holding video info, identified by that class's own shape: two floats (the aspect
     * ratio) plus exactly one `List` (the renditions).
     *
     * Cross-checked against an independent source: the 2023 TwiFucker snapshot recorded the same
     * two-float + one-List shape for Twitter 10.x, and X 12.13 still matches. A shape that has
     * held for three years across a rename is a safer bet than any single field name.
     */
    fun videoInfoField(cls: Class<*>): Field? =
        instanceFields(cls).firstOrNull { f -> looksLikeVideoInfo(f.type) }

    private fun looksLikeVideoInfo(type: Class<*>): Boolean {
        val fields = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        val floats = fields.count { it.type == Float::class.javaPrimitiveType }
        val lists = fields.count { List::class.java.isAssignableFrom(it.type) }
        return floats == 2 && lists == 1
    }

    /** The renditions list on a video-info instance: its only `List` field. */
    fun variantsField(videoInfoClass: Class<*>): Field? =
        instanceFields(videoInfoClass).filter { List::class.java.isAssignableFrom(it.type) }
            .singleOrNull()

    /**
     * Reads (url, bitrate) off one rendition without relying on field names.
     *
     * A rendition carries one int (bitrate) and several strings, one of which is the URL. The URL
     * is picked by content — an `https://` value that is not a MIME type — because "which String
     * field is the url" is exactly the kind of thing that shifts between builds. The int is taken
     * as the bitrate: it is the only numeric field on the class.
     */
    fun readVariant(variant: Any): HostVariant? {
        val fields = instanceFields(variant.javaClass)
        var bitrate = 0
        val strings = mutableListOf<String>()
        for (f in fields) {
            f.isAccessible = true
            when {
                f.type == Int::class.javaPrimitiveType -> bitrate = f.getInt(variant)
                f.type == String::class.java -> (f.get(variant) as? String)?.let { strings.add(it) }
            }
        }
        val url = strings.firstOrNull { it.startsWith("https://") } ?: return null
        // MIME types also contain '/', so a content type is "has a slash but is not a URL".
        val contentType = strings.firstOrNull { it.contains('/') && !it.startsWith("http") }
        return HostVariant(url = url, bitrate = bitrate, contentType = contentType)
    }

    /** Instance fields of [cls] and its superclasses, nearest class first. */
    private fun instanceFields(cls: Class<*>): List<Field> {
        val out = mutableListOf<Field>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            out += c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            c = c.superclass
        }
        out.forEach { it.isAccessible = true }
        return out
    }
}

/** One playable rendition read off the host. */
internal data class HostVariant(
    val url: String,
    val bitrate: Int,
    val contentType: String?,
)
