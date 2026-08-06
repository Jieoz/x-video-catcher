package com.jiesa.xvideocatcher

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Turns an HLS master playlist into one playable MP4 file.
 *
 * ## Why a mux step is unavoidable
 *
 * X publishes split-track HLS: video in one segment list, audio in another. There is no
 * progressive rendition (see [Hls] for the evidence — every `.mp4` the player fetches is an
 * fMP4 init segment). Concatenating the video segments alone gives a valid but **silent**
 * file, so the two tracks have to be combined, and combining them means remuxing.
 *
 * `MediaMuxer` + `MediaExtractor` do this without re-encoding: samples are copied through,
 * so the output is bit-identical video at whatever quality the CDN served. Cost is I/O, not
 * CPU.
 *
 * ## Temp files
 *
 * `MediaExtractor` needs a seekable source, which a network stream is not, so each track is
 * assembled on disk first. They go in [Context.getCacheDir]'s owner — passed in as
 * [workDir] — and are deleted in a `finally`, including on failure: a 40 MB orphan in X's
 * cache directory would be our bug showing up as X's storage growth.
 */
object HlsVideo {

    /**
     * Result of assembling one track, kept so the caller can report a specific failure
     * rather than a generic one.
     */
    private class Track(val file: File, val bytes: Long)

    data class Plan(
        val variant: Hls.Variant,
        val audio: Hls.AudioTrack?,
    )

    /**
     * Chooses what to download from a master playlist URL.
     *
     * Split from [saveTo] so the choice is inspectable and testable without network or
     * platform: the caller logs the plan before committing to a multi-megabyte transfer.
     */
    fun plan(masterUrl: String): Plan? {
        val text = runCatching { Http.text(masterUrl) }.getOrNull() ?: return null
        val master = Hls.parseMaster(text, masterUrl)
        val variant = Hls.bestVariant(master) ?: return null
        return Plan(variant = variant, audio = Hls.audioFor(master, variant))
    }

    /**
     * Downloads [plan] and writes a muxed MP4 to [sink], returning bytes written.
     *
     * Throws on any failure rather than returning 0: [MediaSaver] brackets the write with
     * `IS_PENDING` and deletes the row when the body throws, so a thrown error removes the
     * placeholder while a 0 return would leave the caller to distinguish "empty" from
     * "broken".
     *
     * Audio is best-effort by design. A missing or unreadable audio track produces a
     * video-only file with a logged warning, because a silent video is still what the user
     * asked for; a failed *video* track is fatal.
     */
    fun saveTo(plan: Plan, workDir: File, sink: OutputStream): Long {
        val stamp = System.nanoTime()
        val videoFile = File(workDir, "xvc_v_$stamp.mp4")
        val audioFile = File(workDir, "xvc_a_$stamp.mp4")
        val muxedFile = File(workDir, "xvc_m_$stamp.mp4")
        try {
            val video = assemble(plan.variant.url, videoFile)
                ?: throw java.io.IOException("video track assembly failed")
            DiagLog.line("  video track ${video.bytes} bytes")

            val audio = plan.audio?.let { track ->
                assemble(track.url, audioFile).also {
                    if (it == null) DiagLog.line("  audio track failed, saving video only")
                    else DiagLog.line("  audio track ${it.bytes} bytes")
                }
            }
            if (plan.audio == null) DiagLog.line("  no audio rendition in master, video only")

            mux(video.file, audio?.file, muxedFile)
            val written = muxedFile.inputStream().use { it.copyTo(sink) }
            if (written <= 0L) throw java.io.IOException("mux produced 0 bytes")
            return written
        } finally {
            // Every path, including the throwing ones: these are tens of megabytes inside
            // the host app's cache.
            listOf(videoFile, audioFile, muxedFile).forEach { runCatching { it.delete() } }
        }
    }

    /**
     * Fetches a media playlist and concatenates init + segments into one file.
     *
     * Returns null when the playlist has no `#EXT-X-MAP`: for fMP4 the init segment carries
     * the `moov` box, and segments without it are undecodable. Writing them anyway is
     * precisely the bug this rewrite fixes, so the absence is treated as failure rather
     * than skipped.
     */
    private fun assemble(playlistUrl: String, target: File): Track? {
        val text = runCatching { Http.text(playlistUrl) }.getOrNull() ?: return null
        val playlist = Hls.parseMedia(text, playlistUrl)
        val init = playlist.initUrl ?: run {
            DiagLog.line("  playlist has no EXT-X-MAP: $playlistUrl")
            return null
        }
        if (playlist.segments.isEmpty()) {
            DiagLog.line("  playlist has no segments: $playlistUrl")
            return null
        }
        return runCatching {
            var total = 0L
            target.outputStream().buffered().use { out ->
                total += Http.copyTo(init, out)
                for (seg in playlist.segments) total += Http.copyTo(seg, out)
            }
            Track(target, total)
        }.getOrElse {
            DiagLog.line("  segment fetch failed: ${it.javaClass.simpleName} ${it.message}")
            null
        }
    }

    /**
     * Remuxes one or two elementary tracks into a single MP4.
     *
     * Sample data is copied, never decoded. `presentationTimeUs` comes from the extractor
     * so the two tracks stay in sync; recomputing timestamps from a frame counter would
     * drift on variable frame rate, which X's uploads have.
     */
    private fun mux(videoFile: File, audioFile: File?, target: File) {
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sources = mutableListOf<Pair<MediaExtractor, Int>>()
        try {
            val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            val vTrack = firstTrack(videoExtractor, "video/")
                ?: throw java.io.IOException("no video track in assembled stream")
            videoExtractor.selectTrack(vTrack)
            val vOut = muxer.addTrack(videoExtractor.getTrackFormat(vTrack))
            sources += videoExtractor to vOut

            if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
                runCatching {
                    val audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
                    val aTrack = firstTrack(audioExtractor, "audio/")
                    if (aTrack == null) {
                        audioExtractor.release()
                    } else {
                        audioExtractor.selectTrack(aTrack)
                        val aOut = muxer.addTrack(audioExtractor.getTrackFormat(aTrack))
                        sources += audioExtractor to aOut
                    }
                }.onFailure {
                    DiagLog.line("  audio not muxable: ${it.javaClass.simpleName}")
                }
            }

            muxer.start()
            val buffer = ByteBuffer.allocate(BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            for ((extractor, outTrack) in sources) {
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(outTrack, buffer, info)
                    extractor.advance()
                }
            }
            muxer.stop()
        } finally {
            sources.forEach { (extractor, _) -> runCatching { extractor.release() } }
            runCatching { muxer.release() }
        }
    }

    private fun firstTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    /** 1 MB: X's video segments run a few hundred KB, and a sample never exceeds one. */
    private const val BUFFER_BYTES = 1 shl 20
}
