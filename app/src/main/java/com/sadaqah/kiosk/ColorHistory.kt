package com.sadaqah.kiosk

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ColorHistory {
    private val _colors = MutableStateFlow<List<Long>>(emptyList())
    val colors: StateFlow<List<Long>> = _colors
    private var prefs: SharedPreferences? = null

    // Curated palette: muted, harmonious, easy on the eye — suitable for a donation kiosk
    val suggested: List<Long> = listOf(
        0xFFFFFFFFL,  // White
        0xFF1C1C1CL,  // Near Black (soft)
        0xFF006475L,  // Teal (app default)
        0xFF1A3A5CL,  // Deep Navy
        0xFF4A7C59L,  // Sage Green
        0xFF8B7355L,  // Warm Tan
        0xFF6B7280L,  // Slate Grey
        0xFFF8F4EEL,  // Warm Cream
    )

    fun init(sharedPrefs: SharedPreferences) {
        prefs = sharedPrefs
        val stored = sharedPrefs.getString("color_history", "")
        if (!stored.isNullOrEmpty()) {
            _colors.value = stored.split(",").mapNotNull { it.toLongOrNull() }
        }
    }

    fun addColor(colorLong: Long) {
        // Normalise to unsigned 32-bit ARGB so sign-extended longs compare correctly
        val normalized = colorLong and 0xFFFFFFFFL
        val current = _colors.value.toMutableList()
        current.remove(normalized)
        current.add(0, normalized)
        _colors.value = current.take(8)
        prefs?.edit { putString("color_history", _colors.value.joinToString(",")) }
    }
}
