package com.jiesa.xvideocatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4DateStampTest {

    @Test
    fun stampsMvhdCreationAndModification() {
        // moov { mvhd v0 with ctime=100 mtime=200 }
        val mvhdPayload = ByteArray(20)
        mvhdPayload[0] = 0 // version
        writeU32(mvhdPayload, 4, 100)
        writeU32(mvhdPayload, 8, 200)
        writeU32(mvhdPayload, 12, 1000) // timescale
        writeU32(mvhdPayload, 16, 0) // duration

        val mvhd = box("mvhd", mvhdPayload)
        val moov = box("moov", mvhd)
        val file = moov.copyOf() // top-level moov only

        val unix = 1_700_000_000L
        val n = Mp4DateStamp.stampBytes(file, unix)
        assertTrue("stamped boxes=$n", n >= 1)

        val expected = Mp4DateStamp.toMp4Time(unix)
        // mvhd type at offset 8 (moov header) + 4 size + 0 = wait:
        // moov: [0..3]=size [4..7]=moov [8..]=mvhd box
        // mvhd: [8..11]=size [12..15]=mvhd [16]=version [20..23]=ctime [24..27]=mtime
        val ctime = readU32(file, 20)
        val mtime = readU32(file, 24)
        assertEquals(expected, ctime)
        assertEquals(expected, mtime)
        // and not the old values
        assertTrue(ctime != 100)
    }

    @Test
    fun toMp4TimeAdds1904Offset() {
        val unix = 1_700_000_000L
        val mp4 = unix + Mp4DateStamp.MP4_EPOCH_OFFSET
        // low 32 bits as signed int bit pattern
        assertEquals(mp4.toInt(), Mp4DateStamp.toMp4Time(unix))
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val out = ByteArray(size)
        writeU32(out, 0, size)
        val tb = type.toByteArray(Charsets.US_ASCII)
        require(tb.size == 4)
        System.arraycopy(tb, 0, out, 4, 4)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    private fun writeU32(data: ByteArray, off: Int, value: Int) {
        data[off] = (value ushr 24).toByte()
        data[off + 1] = (value ushr 16).toByte()
        data[off + 2] = (value ushr 8).toByte()
        data[off + 3] = value.toByte()
    }

    private fun readU32(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xff) shl 24) or
            ((data[off + 1].toInt() and 0xff) shl 16) or
            ((data[off + 2].toInt() and 0xff) shl 8) or
            (data[off + 3].toInt() and 0xff)
}
