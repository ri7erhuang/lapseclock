package com.lapseclock.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lapseclock_settings")

data class AppSettings(
    val intervalSec: Int = 10,
    val targetWidth: Int = 1280,
    val targetHeight: Int = 720,
    val jpegQuality: Int = 70,
    val use24h: Boolean = true,
    val showSeconds: Boolean = true,
    val paused: Boolean = false,
    val autoCleanDays: Int = 0,
    val softBrightness: Boolean = true,
    val burnShiftLevel: Int = 2,
) {
    val targetLabel: String get() = "${targetWidth}×${targetHeight}"
}

class SettingsRepo(private val context: Context) {

    private object Keys {
        val INTERVAL = intPreferencesKey("interval_sec")
        val WIDTH = intPreferencesKey("target_width")
        val HEIGHT = intPreferencesKey("target_height")
        val QUALITY = intPreferencesKey("jpeg_quality")
        val USE_24H = booleanPreferencesKey("use_24h")
        val SHOW_SECONDS = booleanPreferencesKey("show_seconds")
        val PAUSED = booleanPreferencesKey("paused")
        val AUTO_CLEAN = intPreferencesKey("auto_clean_days")
        val SOFT_BRIGHTNESS = booleanPreferencesKey("soft_brightness")
        val BURN_SHIFT = intPreferencesKey("burn_shift_level")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            intervalSec = p[Keys.INTERVAL] ?: 10,
            targetWidth = p[Keys.WIDTH] ?: 1280,
            targetHeight = p[Keys.HEIGHT] ?: 720,
            jpegQuality = p[Keys.QUALITY] ?: 70,
            use24h = p[Keys.USE_24H] ?: true,
            showSeconds = p[Keys.SHOW_SECONDS] ?: true,
            paused = p[Keys.PAUSED] ?: false,
            autoCleanDays = p[Keys.AUTO_CLEAN] ?: 0,
            softBrightness = p[Keys.SOFT_BRIGHTNESS] ?: true,
            burnShiftLevel = p[Keys.BURN_SHIFT] ?: 2,
        )
    }

    suspend fun setInterval(sec: Int) = context.dataStore.edit { it[Keys.INTERVAL] = sec }
    suspend fun setResolution(w: Int, h: Int) = context.dataStore.edit {
        it[Keys.WIDTH] = w
        it[Keys.HEIGHT] = h
    }
    suspend fun setQuality(q: Int) = context.dataStore.edit { it[Keys.QUALITY] = q }
    suspend fun setUse24h(v: Boolean) = context.dataStore.edit { it[Keys.USE_24H] = v }
    suspend fun setShowSeconds(v: Boolean) = context.dataStore.edit { it[Keys.SHOW_SECONDS] = v }
    suspend fun setPaused(v: Boolean) = context.dataStore.edit { it[Keys.PAUSED] = v }
    suspend fun setAutoCleanDays(days: Int) = context.dataStore.edit { it[Keys.AUTO_CLEAN] = days }
    suspend fun setSoftBrightness(v: Boolean) = context.dataStore.edit { it[Keys.SOFT_BRIGHTNESS] = v }
    suspend fun setBurnShiftLevel(level: Int) = context.dataStore.edit { it[Keys.BURN_SHIFT] = level }
}
