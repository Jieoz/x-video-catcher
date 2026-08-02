package com.jiesa.xvideocatcher.hook

import android.content.Context

/**
 * User-visible text, resolved without the module's own resources.
 *
 * A module's `R` class is not reachable from inside the host process unless its APK is added to
 * the host's asset path first. That call works, but it mutates host state to read a handful of
 * short strings, and it fails silently on some host/LSPosed combinations — leaving blank labels
 * in the sheet. Since the strings here are few and short, they are inlined and localised against
 * the host's own locale instead.
 */
internal class ModuleStrings {

    fun downloadLabel(context: Context): String =
        if (isChinese(context)) "下载视频" else "Download video"

    fun startedLabel(context: Context, count: Int): String =
        if (isChinese(context)) "开始下载 $count 个文件" else "Downloading $count file(s)"

    fun successLabel(context: Context, count: Int): String =
        if (isChinese(context)) "已保存 $count 个文件到相册" else "Saved $count file(s)"

    fun failureLabel(context: Context): String =
        if (isChinese(context)) "下载失败" else "Download failed"

    fun noMediaLabel(context: Context): String =
        if (isChinese(context)) "这条推文没有可下载的媒体" else "No downloadable media"

    private fun isChinese(context: Context): Boolean =
        context.resources.configuration.locales[0].language == "zh"
}
