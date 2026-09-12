package io.github.tacpr.simfingerprint.data

import android.content.Context

object DiagnosticsStore {
    private const val PREFS_NAME = "diagnostics"
    private const val KEY_LINES = "lines"
    private const val MAX_LINES = 40
    private val allowedReason = Regex("[^A-Za-z0-9_.:\\-]")

    @Synchronized
    fun record(context: Context, event: String, reason: String = "") {
        val safeEvent = sanitize(event).ifBlank { "UNKNOWN" }
        val safeReason = sanitize(reason)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lines = prefs.getString(KEY_LINES, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toMutableList()
        lines += "${System.currentTimeMillis()}|$safeEvent|$safeReason"
        val kept = lines.takeLast(MAX_LINES)
        prefs.edit().putString(KEY_LINES, kept.joinToString("\n")).commit()
    }

    fun read(context: Context): List<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LINES, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .asReversed()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LINES)
            .apply()
    }

    private fun sanitize(value: String): String =
        value.take(64).replace(allowedReason, "_")
}
