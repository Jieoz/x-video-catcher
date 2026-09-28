package com.jiesa.xvideocatcher

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The switch read side, as seen from inside X.
 *
 * The old "publish" — copy a local prefs schema into remote preferences — is gone. The settings UI
 * now writes the switch straight through the Xposed service ([ModuleRuntime.writeSwitch]), so there
 * is no second local store to reconcile. What remains to guard on the read side is the fail-closed
 * default: with no framework (the unit-test case, and any non-hooked process), the host read must
 * report OFF rather than throw.
 */
class ModuleSettingsPublishTest {

    @Test
    fun hostReadDefaultsOffWhenTheFrameworkIsAbsent() {
        assertFalse(ModuleSettings.readDiagEnabledFromHost())
    }
}
