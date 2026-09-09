package com.jiesa.xvideocatcher

import java.io.File
import java.io.RandomAccessFile

/**
 * Rewrites MP4 container creation/modification times to download time.
 *
 * OEM galleries often sort by `mvhd`/`tkhd`/`mdhd` inside the file, ignoring or
 * overriding MediaStore `datetaken`. Progressive CDN files and HLS remux output both
 * keep old/zero container times — 1.26 only stamped MediaStore columns, so Jay still
 * saw "long ago" on video.
 *
 * Pure byte surgery on version-0 (32-bit) and version-1 (64-bit) full-box times.
 * No re-encode.
 */
object Mp4DateStamp {

    /** MP4 epoch is 1904-01-01 UTC; Unix is 1970-01-01. */
    const val MP4_EPOCH_OFFSET = 2_082_844_800L

    fun toMp4Time(epochSecondsUnix: Long): Int =
        (epochSecondsUnix + MP4_EPOCH_OFFSET).coerceIn(0L, 0xFFFF_FFFFL).toInt()

    /**
     * @return number of timestamp **pairs** rewritten (each box contributes 1).
     */
    fun stampFile(file: File, epochSecondsUnix: Long = System.currentTimeMillis() / 1000L): Int {
        if (!file.isFile || file.length() < 32L) return 0
        if (file.length() > 512L * 1024 * 1024) return 0 // refuse multi-GB map
        val mp4Time = toMp4Time(epochSecondsUnix)
        return RandomAccessFile(file, "rw").use { raf ->
            val buf = ByteArray(file.length().toInt())
            raf.readFully(buf)
            val n = stampBytesWithMp4Time(buf, mp4Time)
            if (n > 0) {
                raf.seek(0)
                raf.write(buf)
            }
            n
        }
    }

    /** In-memory entry used by unit tests and [stampFile]. */
    fun stampBytes(data: ByteArray, epochSecondsUnix: Long): Int =
        stampBytesWithMp4Time(data, toMp4Time(epochSecondsUnix))

    internal fun stampBytesWithMp4Time(data: ByteArray, mp4Time: Int): Int =
        stampRegion(data, 0, data.size, mp4Time)

    private fun stampRegion(data: ByteArray, start: Int, end: Int, mp4Time: Int): Int {
        var count = 0
        var i = start
        while (i + 8 <= end) {
            val size = readU32(data, i)
            if (size < 8) break
            val type = String(data, i + 4, 4, Charsets.US_ASCII)
            val header: Int
            val boxEnd: Int
            if (size == 1) {
                if (i + 16 > end) break
                val large = readU64(data, i + 8)
                if (large < 16L || i + large > end) break
                header = 16
                boxEnd = (i + large).toInt()
            } else {
                if (i + size > end) break
                header = 8
                boxEnd = i + size
            }
            val body = i + header
            when (type) {
                "moov", "trak", "mdia", "minf", "stbl", "edts", "moof", "traf" ->
                    count += stampRegion(data, body, boxEnd, mp4Time)
                "mvhd", "tkhd", "mdhd" ->
                    count += stampFullBoxTimes(data, body, boxEnd, mp4Time)
            }
            i = boxEnd
        }
        return count
    }

    private fun stampFullBoxTimes(data: ByteArray, body: Int, end: Int, mp4Time: Int): Int {
        if (body >= end) return 0
        val version = data[body].toInt() and 0xff
        val timesAt = body + 4
        return if (version == 0) {
            if (timesAt + 8 > end) return 0
            writeU32(data, timesAt, mp4Time)
            writeU32(data, timesAt + 4, mp4Time)
            1
        } else {
            if (timesAt + 16 > end) return 0
            writeU32(data, timesAt, 0)
            writeU32(data, timesAt + 4, mp4Time)
            writeU32(data, timesAt + 8, 0)
            writeU32(data, timesAt + 12, mp4Time)
            1
        }
    }

    private fun readU32(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xff) shl 24) or
            ((data[off + 1].toInt() and 0xff) shl 16) or
            ((data[off + 2].toInt() and 0xff) shl 8) or
            (data[off + 3].toInt() and 0xff)

    private fun readU64(data: ByteArray, off: Int): Long =
        ((readU32(data, off).toLong() and 0xFFFF_FFFFL) shl 32) or
            (readU32(data, off + 4).toLong() and 0xFFFF_FFFFL)

    private fun writeU32(data: ByteArray, off: Int, value: Int) {
        data[off] = (value ushr 24).toByte()
        data[off + 1] = (value ushr 16).toByte()
        data[off + 2] = (value ushr 8).toByte()
        data[off + 3] = value.toByte()
    }
}
