package com.jiesa.xvideocatcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gallery sort keys must be **download time**, not CDN / container creation time.
 *
 * Uses [MediaSaver.stampMap] (pure Map) rather than ContentValues: Android's ContentValues
 * stub throws "not mocked" on plain JUnit.
 */
class MediaSaverStampTest {

    @Test
    fun stampUsesSecondsForAddedAndModifiedAndMillisForTaken() {
        val now = 1_725_000_000_123L
        val v = MediaSaver.stampMap(now)
        assertEquals(now / 1000L, v[MediaSaver.COL_DATE_ADDED])
        assertEquals(now / 1000L, v[MediaSaver.COL_DATE_MODIFIED])
        assertEquals(now, v[MediaSaver.COL_DATE_TAKEN])
    }
}
