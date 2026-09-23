package com.jiesa.xvideocatcher

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSettingsPublishTest {

    @Test
    fun publishCopiesSchemaAndFlag() {
        val local = MemoryPrefs()
        local.edit().putInt("settings_schema", 1).putBoolean(ModuleSettings.KEY_DIAG_ENABLED, true).commit()
        val remote = MemoryPrefs()
        ModuleSettings.remoteWriter = { source ->
            remote.edit()
                .putInt("settings_schema", source.getInt("settings_schema", 0))
                .putBoolean(
                    ModuleSettings.KEY_DIAG_ENABLED,
                    source.getBoolean(ModuleSettings.KEY_DIAG_ENABLED, false),
                )
                .commit()
        }

        ModuleSettings.publishForTest(local)

        assertEquals(1, remote.getInt("settings_schema", 0))
        assertTrue(remote.getBoolean(ModuleSettings.KEY_DIAG_ENABLED, false))
    }

    @Test
    fun aFailedPublishDoesNotThrow() {
        ModuleSettings.remoteWriter = { error("framework unavailable") }
        ModuleSettings.publishForTest(MemoryPrefs())
    }

    @Test
    fun hostReadDefaultsOffWhenTheFrameworkIsAbsent() {
        assertFalse(ModuleSettings.readDiagEnabledFromHost())
    }

    private class MemoryPrefs : SharedPreferences {
        private val values = linkedMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, def: String?) = values[key] as? String ?: def
        override fun getStringSet(key: String, def: MutableSet<String>?) = def
        override fun getInt(key: String, def: Int) = values[key] as? Int ?: def
        override fun getLong(key: String, def: Long) = values[key] as? Long ?: def
        override fun getFloat(key: String, def: Float) = values[key] as? Float ?: def
        override fun getBoolean(key: String, def: Boolean) = values[key] as? Boolean ?: def
        override fun contains(key: String) = key in values
        override fun edit() = Editor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        inner class Editor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = put(key, value)
            override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)
            override fun putInt(key: String, value: Int) = put(key, value)
            override fun putLong(key: String, value: Long) = put(key, value)
            override fun putFloat(key: String, value: Float) = put(key, value)
            override fun putBoolean(key: String, value: Boolean) = put(key, value)
            override fun remove(key: String) = put(key, null)
            override fun clear(): SharedPreferences.Editor {
                pending.clear()
                values.keys.forEach { pending[it] = null }
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                pending.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
            private fun put(key: String, value: Any?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
        }
    }
}
