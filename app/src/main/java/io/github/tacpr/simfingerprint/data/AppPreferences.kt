package io.github.tacpr.simfingerprint.data

import android.content.Context

class AppPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            check(prefs.edit().putBoolean(KEY_ENABLED, value).commit()) { "Enabled state was not persisted" }
        }

    var configuredSlotId: Int
        get() = prefs.getInt(KEY_SLOT_ID, 0)
        set(value) {
            check(prefs.edit().putInt(KEY_SLOT_ID, value).commit()) { "Slot state was not persisted" }
        }

    var lastHookHeartbeat: Long
        get() = prefs.getLong(KEY_HOOK_HEARTBEAT, 0L)
        set(value) = prefs.edit().putLong(KEY_HOOK_HEARTBEAT, value).apply()

    var termsAccepted: Boolean
        get() = prefs.getBoolean(KEY_TERMS_ACCEPTED, false)
        set(value) { check(prefs.edit().putBoolean(KEY_TERMS_ACCEPTED, value).commit()) }

    fun storageIsWritable(): Boolean {
        val probe = System.currentTimeMillis()
        if (!prefs.edit().putLong(KEY_STORAGE_PROBE, probe).commit()) return false
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_STORAGE_PROBE, -1L) == probe
    }

    companion object {
        private const val PREFS_NAME = "app_preferences"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SLOT_ID = "slot_id"
        private const val KEY_HOOK_HEARTBEAT = "hook_heartbeat"
        private const val KEY_STORAGE_PROBE = "storage_probe"
        private const val KEY_TERMS_ACCEPTED = "terms_accepted_v2"
    }
}
