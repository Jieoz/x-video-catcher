package com.jiesa.xvideocatcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSettingsTest {

    @Test
    fun diagDefaultsOffInDiagLog() {
        DiagLog.resetForTest()
        // Production default: disabled. The user must opt in via Settings.
        assertFalse(DiagLog.isEnabled())

        var wrote = false
        DiagLog.writer = { wrote = true; true }
        DiagLog.bindForTest()
        DiagLog.line("should-not-write")
        DiagLog.flushNow()
        assertFalse(wrote)

        // After enabling, writes go through.
        DiagLog.setEnabled(true)
        DiagLog.line("should-write")
        DiagLog.flushNow()
        assertTrue(wrote)
    }
}
