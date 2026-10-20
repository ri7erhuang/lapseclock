package com.lapseclock.settings

import android.content.Context
import com.lapseclock.BuildConfig
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
    val paused: Boolean = false,
    val autoCleanDays: Int = 0,
    val softBrightness: Boolean = true,
    val burnShiftLevel: Int = 2,
    val watermarkEnabled: Boolean = true,
    val watermarkText: String = "LapseClock",
    val watermarkShowTime: Boolean = true,
    val weatherCity: String = BuildConfig.QWEATHER_CITY,
    val useLocation: Boolean = true,
    /** 手动休眠：点时钟上的 🌙 进入，点 ☀ 退出 */
    val manualSleep: Boolean = false,
    /** 被手动唤醒的那一夜（nightKeyOf），当晚不再自动休眠 */
    val autoSleepSnoozeNight: String = "",
    /** 时钟皮肤 id，[com.lapseclock.ui.theme.Themes.AUTO] 表示跟随时段自动 */
    val themeId: String = com.lapseclock.ui.theme.Themes.AUTO,
    /** 闲时降频：从几点开始降频（0 表示关闭）。下班后空办公室继续 60s 一张纯属浪费 */
    val idleStartHour: Int = 19,
    /** 闲时降频后的拍照间隔 */
    val idleIntervalSec: Int = 300,
) {
    val targetLabel: String get() = "${targetWidth}×${targetHeight}"

    /** 当前时刻是否处于闲时降频时段（夜间休眠本身就是不拍，所以不需要跨夜处理） */
    fun isIdleHour(hour: Int): Boolean = idleStartHour in 1..23 && hour >= idleStartHour

    /** 当前生效的拍照间隔（秒） */
    fun effectiveIntervalSec(hour: Int): Int =
        if (isIdleHour(hour)) idleIntervalSec else intervalSec
}

class SettingsRepo(private val context: Context) {

    private object Keys {
        val INTERVAL = intPreferencesKey("interval_sec")
        val WIDTH = intPreferencesKey("target_width")
        val HEIGHT = intPreferencesKey("target_height")
        val USE_24H = booleanPreferencesKey("use_24h")
        val PAUSED = booleanPreferencesKey("paused")
        val AUTO_CLEAN = intPreferencesKey("auto_clean_days")
        val SOFT_BRIGHTNESS = booleanPreferencesKey("soft_brightness")
        val BURN_SHIFT = intPreferencesKey("burn_shift_level")
        val WATERMARK_ENABLED = booleanPreferencesKey("watermark_enabled")
        val WATERMARK_TEXT = stringPreferencesKey("watermark_text")
        val WATERMARK_SHOW_TIME = booleanPreferencesKey("watermark_show_time")
        val WEATHER_CITY = stringPreferencesKey("weather_city")
        val USE_LOCATION = booleanPreferencesKey("use_location")
        val MANUAL_SLEEP = booleanPreferencesKey("manual_sleep")
        val SNOOZE_NIGHT = stringPreferencesKey("auto_sleep_snooze_night")
        val THEME_ID = stringPreferencesKey("theme_id")
        val IDLE_START_HOUR = intPreferencesKey("idle_start_hour")
        val IDLE_INTERVAL = intPreferencesKey("idle_interval_sec")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            intervalSec = p[Keys.INTERVAL] ?: 60,
            targetWidth = p[Keys.WIDTH] ?: 1600,
            targetHeight = p[Keys.HEIGHT] ?: 1200,
            use24h = p[Keys.USE_24H] ?: true,
            paused = p[Keys.PAUSED] ?: false,
            autoCleanDays = p[Keys.AUTO_CLEAN] ?: 0,
            softBrightness = p[Keys.SOFT_BRIGHTNESS] ?: true,
            burnShiftLevel = p[Keys.BURN_SHIFT] ?: 2,
            watermarkEnabled = p[Keys.WATERMARK_ENABLED] ?: true,
            watermarkText = p[Keys.WATERMARK_TEXT] ?: "LapseClock",
            watermarkShowTime = p[Keys.WATERMARK_SHOW_TIME] ?: true,
            weatherCity = p[Keys.WEATHER_CITY] ?: BuildConfig.QWEATHER_CITY,
            useLocation = p[Keys.USE_LOCATION] ?: true,
            manualSleep = p[Keys.MANUAL_SLEEP] ?: false,
            autoSleepSnoozeNight = p[Keys.SNOOZE_NIGHT] ?: "",
            themeId = p[Keys.THEME_ID] ?: com.lapseclock.ui.theme.Themes.AUTO,
            idleStartHour = p[Keys.IDLE_START_HOUR] ?: 19,
            idleIntervalSec = p[Keys.IDLE_INTERVAL] ?: 300,
        )
    }

    suspend fun setInterval(sec: Int) = context.dataStore.edit { it[Keys.INTERVAL] = sec }
    suspend fun setResolution(w: Int, h: Int) = context.dataStore.edit {
        it[Keys.WIDTH] = w
        it[Keys.HEIGHT] = h
    }
    suspend fun setUse24h(v: Boolean) = context.dataStore.edit { it[Keys.USE_24H] = v }
    suspend fun setPaused(v: Boolean) = context.dataStore.edit { it[Keys.PAUSED] = v }
    suspend fun setAutoCleanDays(days: Int) = context.dataStore.edit { it[Keys.AUTO_CLEAN] = days }
    suspend fun setSoftBrightness(v: Boolean) = context.dataStore.edit { it[Keys.SOFT_BRIGHTNESS] = v }
    suspend fun setBurnShiftLevel(level: Int) = context.dataStore.edit { it[Keys.BURN_SHIFT] = level }
    suspend fun setWatermarkEnabled(v: Boolean) = context.dataStore.edit { it[Keys.WATERMARK_ENABLED] = v }
    suspend fun setWatermarkText(v: String) = context.dataStore.edit { it[Keys.WATERMARK_TEXT] = v }
    suspend fun setWatermarkShowTime(v: Boolean) = context.dataStore.edit { it[Keys.WATERMARK_SHOW_TIME] = v }
    suspend fun setWeatherCity(city: String) = context.dataStore.edit { it[Keys.WEATHER_CITY] = city }
    suspend fun setUseLocation(v: Boolean) = context.dataStore.edit { it[Keys.USE_LOCATION] = v }
    suspend fun setManualSleep(v: Boolean) = context.dataStore.edit { it[Keys.MANUAL_SLEEP] = v }
    suspend fun setSnoozeNight(night: String) = context.dataStore.edit { it[Keys.SNOOZE_NIGHT] = night }
    suspend fun setThemeId(id: String) = context.dataStore.edit { it[Keys.THEME_ID] = id }
    suspend fun setIdleStartHour(hour: Int) = context.dataStore.edit { it[Keys.IDLE_START_HOUR] = hour }
    suspend fun setIdleInterval(sec: Int) = context.dataStore.edit { it[Keys.IDLE_INTERVAL] = sec }
}
