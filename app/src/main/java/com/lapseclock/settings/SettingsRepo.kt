package com.lapseclock.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lapseclock_settings")

data class AppSettings(
    val intervalSec: Int = 60,
    val targetWidth: Int = 1600,
    val targetHeight: Int = 1200,
    val use24h: Boolean = true,
    val showSeconds: Boolean = true,
    val paused: Boolean = false,
    val autoCleanDays: Int = 0,
    val softBrightness: Boolean = true,
    val burnShiftLevel: Int = 2,
    val watermarkEnabled: Boolean = true,
    val watermarkText: String = "LapseClock",
    val watermarkShowTime: Boolean = true,
) {
    val targetLabel: String get() = "${targetWidth}×${targetHeight}"
}

class SettingsRepo(private val context: Context) {

    private object Keys {
        val INTERVAL = intPreferencesKey("interval_sec")
        val WIDTH = intPreferencesKey("target_width")
        val HEIGHT = intPreferencesKey("target_height")
        val USE_24H = booleanPreferencesKey("use_24h")
        val SHOW_SECONDS = booleanPreferencesKey("show_seconds")
        val PAUSED = booleanPreferencesKey("paused")
        val AUTO_CLEAN = intPreferencesKey("auto_clean_days")
        val SOFT_BRIGHTNESS = booleanPreferencesKey("soft_brightness")
        val BURN_SHIFT = intPreferencesKey("burn_shift_level")
        val WATERMARK_ENABLED = booleanPreferencesKey("watermark_enabled")
        val WATERMARK_TEXT = stringPreferencesKey("watermark_text")
        val WATERMARK_SHOW_TIME = booleanPreferencesKey("watermark_show_time")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            intervalSec = p[Keys.INTERVAL] ?: 60,
            targetWidth = p[Keys.WIDTH] ?: 1600,
            targetHeight = p[Keys.HEIGHT] ?: 1200,
            use24h = p[Keys.USE_24H] ?: true,
            showSeconds = p[Keys.SHOW_SECONDS] ?: true,
            paused = p[Keys.PAUSED] ?: false,
            autoCleanDays = p[Keys.AUTO_CLEAN] ?: 0,
            softBrightness = p[Keys.SOFT_BRIGHTNESS] ?: true,
            burnShiftLevel = p[Keys.BURN_SHIFT] ?: 2,
            watermarkEnabled = p[Keys.WATERMARK_ENABLED] ?: true,
            watermarkText = p[Keys.WATERMARK_TEXT] ?: "LapseClock",
            watermarkShowTime = p[Keys.WATERMARK_SHOW_TIME] ?: true,
        )
    }

    suspend fun setInterval(sec: Int) = context.dataStore.edit { it[Keys.INTERVAL] = sec }
    suspend fun setResolution(w: Int, h: Int) = context.dataStore.edit {
        it[Keys.WIDTH] = w
        it[Keys.HEIGHT] = h
    }
    suspend fun setUse24h(v: Boolean) = context.dataStore.edit { it[Keys.USE_24H] = v }
    suspend fun setShowSeconds(v: Boolean) = context.dataStore.edit { it[Keys.SHOW_SECONDS] = v }
    suspend fun setPaused(v: Boolean) = context.dataStore.edit { it[Keys.PAUSED] = v }
    suspend fun setAutoCleanDays(days: Int) = context.dataStore.edit { it[Keys.AUTO_CLEAN] = days }
    suspend fun setSoftBrightness(v: Boolean) = context.dataStore.edit { it[Keys.SOFT_BRIGHTNESS] = v }
    suspend fun setBurnShiftLevel(level: Int) = context.dataStore.edit { it[Keys.BURN_SHIFT] = level }
    suspend fun setWatermarkEnabled(v: Boolean) = context.dataStore.edit { it[Keys.WATERMARK_ENABLED] = v }
    suspend fun setWatermarkText(v: String) = context.dataStore.edit { it[Keys.WATERMARK_TEXT] = v }
    suspend fun setWatermarkShowTime(v: Boolean) = context.dataStore.edit { it[Keys.WATERMARK_SHOW_TIME] = v }
}
