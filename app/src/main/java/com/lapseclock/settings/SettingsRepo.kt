package com.lapseclock.settings

import android.content.Context
import com.lapseclock.BuildConfig
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lapseclock_settings")

data class AppSettings(
    val intervalSec: Int = 60,
    val targetWidth: Int = 1600,
    val targetHeight: Int = 1200,
    val use24h: Boolean = true,
    val paused: Boolean = false,
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
    /** 每天休眠结束后自动把昨天的照片合成视频 */
    val autoExport: Boolean = true,
    /** 最近一次成功自动合成的日期（yyyy-MM-dd），避免重复出片 */
    val lastAutoExportDate: String = "",
    /** 最近一次点开「每日一签」的日期（yyyy-MM-dd），当天点过就不再显示图标 */
    val lastFortuneDate: String = "",
) {
    val targetLabel: String get() = "${targetWidth}×${targetHeight}"
}

class SettingsRepo(private val context: Context) {

    private object Keys {
        val INTERVAL = intPreferencesKey("interval_sec")
        val WIDTH = intPreferencesKey("target_width")
        val HEIGHT = intPreferencesKey("target_height")
        val USE_24H = booleanPreferencesKey("use_24h")
        val PAUSED = booleanPreferencesKey("paused")
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
        val AUTO_EXPORT = booleanPreferencesKey("auto_export")
        val LAST_AUTO_EXPORT = stringPreferencesKey("last_auto_export_date")
        val LAST_FORTUNE = stringPreferencesKey("last_fortune_date")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e ->
            // 设置文件损坏/读失败时退回默认值，不能让整个状态流终止
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { p ->
            AppSettings(
                intervalSec = p[Keys.INTERVAL] ?: 60,
                targetWidth = p[Keys.WIDTH] ?: 1600,
                targetHeight = p[Keys.HEIGHT] ?: 1200,
                use24h = p[Keys.USE_24H] ?: true,
                paused = p[Keys.PAUSED] ?: false,
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
                autoExport = p[Keys.AUTO_EXPORT] ?: true,
                lastAutoExportDate = p[Keys.LAST_AUTO_EXPORT] ?: "",
                lastFortuneDate = p[Keys.LAST_FORTUNE] ?: "",
            )
        }

    suspend fun setInterval(sec: Int) = context.dataStore.edit { it[Keys.INTERVAL] = sec }
    suspend fun setResolution(w: Int, h: Int) = context.dataStore.edit {
        it[Keys.WIDTH] = w
        it[Keys.HEIGHT] = h
    }
    suspend fun setUse24h(v: Boolean) = context.dataStore.edit { it[Keys.USE_24H] = v }
    suspend fun setPaused(v: Boolean) = context.dataStore.edit { it[Keys.PAUSED] = v }
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
    suspend fun setAutoExport(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_EXPORT] = v }
    suspend fun setLastFortuneDate(date: String) = context.dataStore.edit { it[Keys.LAST_FORTUNE] = date }
    suspend fun setLastAutoExportDate(date: String) =
        context.dataStore.edit { it[Keys.LAST_AUTO_EXPORT] = date }
}
