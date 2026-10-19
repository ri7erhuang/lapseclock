package com.lapseclock

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lapseclock.capture.CaptureController
import com.lapseclock.capture.PhotoStore
import com.lapseclock.settings.AppSettings
import com.lapseclock.settings.SettingsRepo
import com.lapseclock.gold.GoldRepo
import com.lapseclock.video.ExportController
import com.lapseclock.weather.LocationProvider
import com.lapseclock.weather.WeatherRepo
import com.lapseclock.weather.WeatherUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import android.os.Environment

enum class Screen { Clock, Settings, Export }

/** rising：与上一次抓取相比的涨跌，null 表示无对比（首次或持平） */
data class GoldUi(val price: String, val rising: Boolean?)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val repo = SettingsRepo(app)

    val settings = repo.settings.stateIn(
        viewModelScope, SharingStarted.Eagerly, AppSettings()
    )

    val store = PhotoStore(
        app.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: app.filesDir
    )

    val capture = CaptureController(app, store, repo, viewModelScope)
    val export = ExportController(app, store, viewModelScope)

    val screen = MutableStateFlow(Screen.Clock)
    val permissionNeeded = MutableStateFlow(false)

    val weather = MutableStateFlow<WeatherUi?>(null)
    private val weatherRepo = WeatherRepo()
    private val locationProvider = LocationProvider(app)
    private val weatherMutex = Mutex()

    init {
        // 启动即拉取；定位开关/手动城市（输入停止 0.8s 后）变化时重新拉取
        viewModelScope.launch {
            settings
                .map { it.useLocation to it.weatherCity.trim() }
                .debounce(800L)
                .distinctUntilChanged()
                .collect { refreshWeather() }
        }
        // 常规刷新：每 5 分钟（休眠时段不刷新）
        viewModelScope.launch {
            while (true) {
                delay(REFRESH_INTERVAL_MS)
                if (!isSleepHour(java.time.LocalTime.now().hour)) refreshWeather()
            }
        }
    }

    fun refreshWeatherNow() = viewModelScope.launch { refreshWeather() }

    val gold = MutableStateFlow<GoldUi?>(null)

    init {
        // 积存金：每 5 分钟（休眠时段不刷新）
        viewModelScope.launch {
            while (true) {
                if (!isSleepHour(java.time.LocalTime.now().hour)) refreshGold()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshGold() {
        runCatching { withTimeout(20_000L) { GoldRepo.fetchPrice() } }
            .onSuccess { newPrice ->
                val prev = gold.value?.price
                val rising = prev?.let {
                    runCatching {
                        when {
                            newPrice.toBigDecimal() > it.toBigDecimal() -> true
                            newPrice.toBigDecimal() < it.toBigDecimal() -> false
                            else -> null
                        }
                    }.getOrNull()
                }
                gold.value = GoldUi(newPrice, rising)
                android.util.Log.i("LapseClock", "积存金: $newPrice (上次 $prev)")
            }
            .onFailure {
                android.util.Log.w("LapseClock", "积存金刷新失败: ${it.javaClass.simpleName} ${it.message}")
            }
    }

    private suspend fun refreshWeather() {
        val s = settings.value
        val fallbackCity = s.weatherCity.trim()
        weatherMutex.withLock {
            var result: WeatherUi? = null

            if (s.useLocation && hasLocationPermission()) {
                val coords = runCatching { withTimeout(15_000L) { locationProvider.getLocation() } }
                    .onFailure { android.util.Log.w("LapseClock", "定位失败: ${it.message}") }
                    .getOrNull()
                if (coords != null) {
                    result = runCatching { withTimeout(20_000L) { weatherRepo.fetchByCoords(coords.first, coords.second) } }
                        .onFailure { android.util.Log.w("LapseClock", "坐标天气失败: ${it.message}") }
                        .getOrNull()
                }
            }

            if (result == null && fallbackCity.isNotBlank()) {
                result = runCatching { withTimeout(20_000L) { weatherRepo.fetch(fallbackCity) } }
                    .onFailure { android.util.Log.w("LapseClock", "城市天气失败: ${it.javaClass.simpleName} ${it.message}") }
                    .getOrNull()
            }

            if (result != null) {
                weather.value = result
                android.util.Log.i("LapseClock", "天气刷新成功: ${result.city} ${result.text} ${result.temp}°")
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        val ctx = getApplication<Application>()
        return ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun setInterval(sec: Int) = viewModelScope.launch { repo.setInterval(sec) }
    fun setResolution(w: Int, h: Int) = viewModelScope.launch { repo.setResolution(w, h) }
    fun setUse24h(v: Boolean) = viewModelScope.launch { repo.setUse24h(v) }
    fun setShowSeconds(v: Boolean) = viewModelScope.launch { repo.setShowSeconds(v) }
    fun setPaused(v: Boolean) = viewModelScope.launch { repo.setPaused(v) }
    fun setAutoClean(days: Int) = viewModelScope.launch { repo.setAutoCleanDays(days) }
    fun setSoftBrightness(v: Boolean) = viewModelScope.launch { repo.setSoftBrightness(v) }
    fun setBurnShiftLevel(level: Int) = viewModelScope.launch { repo.setBurnShiftLevel(level) }
    fun setWatermarkEnabled(v: Boolean) = viewModelScope.launch { repo.setWatermarkEnabled(v) }
    fun setWatermarkText(v: String) = viewModelScope.launch { repo.setWatermarkText(v) }
    fun setWatermarkShowTime(v: Boolean) = viewModelScope.launch { repo.setWatermarkShowTime(v) }
    fun setWeatherCity(city: String) = viewModelScope.launch { repo.setWeatherCity(city) }
    fun setUseLocation(v: Boolean) = viewModelScope.launch { repo.setUseLocation(v) }

    override fun onCleared() {
        capture.stop()
        export.cancel()
    }

    companion object {
        /** 天气/积存金常规刷新间隔 */
        private const val REFRESH_INTERVAL_MS = 5 * 60_000L
    }
}
