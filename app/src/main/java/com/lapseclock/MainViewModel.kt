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
import com.lapseclock.video.AutoExportPolicy
import com.lapseclock.video.ExportController
import com.lapseclock.video.ExportState
import com.lapseclock.weather.LocationProvider
import com.lapseclock.weather.WeatherRepo
import com.lapseclock.weather.WeatherUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import android.os.Environment
import java.time.LocalDate

enum class Screen { Clock, Settings, Export }

/** rising：与上一次抓取相比的涨跌，null 表示无对比（首次或持平） */
data class GoldUi(val price: String, val rising: Boolean?)

@OptIn(kotlinx.coroutines.FlowPreview::class)
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

    /** local.properties 里是否真的配了和风天气（key + host 都非空） */
    val weatherConfigured: Boolean =
        BuildConfig.QWEATHER_KEY.isNotBlank() && BuildConfig.QWEATHER_HOST.isNotBlank()
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
        // 常规刷新：每 5 分钟（休眠不刷新）
        viewModelScope.launch {
            while (true) {
                delay(REFRESH_INTERVAL_MS)
                if (!sleepingNow()) refreshWeather()
            }
        }
    }

    fun refreshWeatherNow() = viewModelScope.launch { refreshWeather() }

    /** 当前是否处于休眠：手动休眠优先，其次夜间时段且当晚没被手动唤醒 */
    fun sleepingNow(): Boolean = with(settings.value) {
        isSleeping(manualSleep, autoSleepSnoozeNight)
    }

    /** 手动进入休眠：拍照停止、天气与积存金不再刷新、时钟熄屏 */
    fun enterSleep() = viewModelScope.launch { repo.setManualSleep(true) }

    /** 退出休眠；若仍处于夜间时段，记下「当晚不再休眠」，并立刻补一次数据 */
    fun wakeUp() = viewModelScope.launch {
        val now = java.time.LocalDateTime.now()
        if (isSleepHour(now.hour)) repo.setSnoozeNight(nightKeyOf(now))
        repo.setManualSleep(false)
        refreshWeather()
        refreshGold()
    }

    val gold = MutableStateFlow<GoldUi?>(null)

    init {
        // 积存金：每 5 分钟（休眠不刷新）
        viewModelScope.launch {
            while (true) {
                if (!sleepingNow()) refreshGold()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    /** 自动合成失败后的退避，避免每分钟重试 */
    private var autoExportRetryAfter = 0L

    init {
        // 启动时后台建一次照片索引；之后拍照/清理都增量维护，界面查询不再扫目录
        viewModelScope.launch { store.ensureIndex() }

        // 每分钟检查：昨天的照片是否已经自动合成过
        viewModelScope.launch {
            while (true) {
                try {
                    maybeAutoExport()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // 一次检查失败不能让自动合成永远停摆
                    android.util.Log.w("LapseClock", "自动合成检查失败: ${t.message}")
                }
                delay(60_000L)
            }
        }

        // 自动合成成功后记下日期，避免同一天重复出片
        viewModelScope.launch {
            export.state.collect { st ->
                if (st is ExportState.Done && st.auto) {
                    try {
                        repo.setLastAutoExportDate(st.dates.last().toString())
                    } catch (t: Throwable) {
                        android.util.Log.w("LapseClock", "记录自动合成日期失败: ${t.message}")
                    }
                    android.util.Log.i(
                        "LapseClock",
                        "自动合成完成: ${st.dates.first()} ${st.frames} 帧",
                    )
                }
            }
        }
    }

    /**
     * 休眠结束后的第一次检查会把昨天的照片合成成片：
     * 昨天有照片、今天还没出过、当前不在休眠/导出中，就自动跑一次。
     * 编码参数与手动导出默认值一致（30fps / 1080p）。
     */
    private suspend fun maybeAutoExport() {
        // 直接读 DataStore 的首个真实值：settings StateFlow 的初始值只是默认配置，
        // 若在磁盘读完前检查，可能把用户关掉的自动合成又跑起来
        val s = repo.settings.first()
        val yesterday = LocalDate.now().minusDays(1)
        store.ensureIndex()
        val shouldRun = AutoExportPolicy.shouldRun(
            enabled = s.autoExport,
            sleeping = isSleeping(s.manualSleep, s.autoSleepSnoozeNight),
            exporting = export.state.value is ExportState.Running,
            lastAutoExportDate = s.lastAutoExportDate,
            yesterday = yesterday,
            yesterdayCount = store.countOn(yesterday),
            nowMillis = System.currentTimeMillis(),
            retryAfterMillis = autoExportRetryAfter,
        )
        if (!shouldRun) return

        if (store.freeBytes() < AutoExportPolicy.MIN_FREE_BYTES) {
            android.util.Log.w("LapseClock", "自动合成跳过：剩余空间不足")
            autoExportRetryAfter = System.currentTimeMillis() + AutoExportPolicy.RETRY_MS
            return
        }

        autoExportRetryAfter = System.currentTimeMillis() + AutoExportPolicy.RETRY_MS
        export.start(
            dates = listOf(yesterday),
            fps = AutoExportPolicy.FPS,
            width = AutoExportPolicy.WIDTH,
            height = AutoExportPolicy.HEIGHT,
            auto = true,
        )
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
        // 未配置和风天气（local.properties 缺 key/host）时直接跳过，避免每 5 分钟刷一次必然失败的请求
        if (!weatherConfigured) return
        // 读磁盘上的真实值：settings StateFlow 的初始值只是默认配置，
        // 在磁盘读完前用它可能按默认的「开启定位」多发一次请求
        val s = repo.settings.first()
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
    fun setPaused(v: Boolean) = viewModelScope.launch { repo.setPaused(v) }
    fun setSoftBrightness(v: Boolean) = viewModelScope.launch { repo.setSoftBrightness(v) }
    fun setBurnShiftLevel(level: Int) = viewModelScope.launch { repo.setBurnShiftLevel(level) }
    fun setWatermarkEnabled(v: Boolean) = viewModelScope.launch { repo.setWatermarkEnabled(v) }
    fun setWatermarkText(v: String) = viewModelScope.launch { repo.setWatermarkText(v) }
    fun setWatermarkShowTime(v: Boolean) = viewModelScope.launch { repo.setWatermarkShowTime(v) }
    fun setWeatherCity(city: String) = viewModelScope.launch { repo.setWeatherCity(city) }
    fun setUseLocation(v: Boolean) = viewModelScope.launch { repo.setUseLocation(v) }
    fun setThemeId(id: String) = viewModelScope.launch { repo.setThemeId(id) }
    fun setAutoExport(v: Boolean) = viewModelScope.launch { repo.setAutoExport(v) }

    /**
     * 合成结束后由用户在弹窗里确认，删除这些日期的源照片。
     * 只删用户明确选中的日期，不做任何"顺手清理"。
     */
    fun deleteSourcePhotos(dates: Collection<LocalDate>) = viewModelScope.launch {
        if (dates.isEmpty()) return@launch
        val removed = runCatching {
            withContext(Dispatchers.IO) { store.deleteDates(dates) }
        }.onFailure {
            android.util.Log.w("LapseClock", "删除源照片失败: ${it.message}")
        }.getOrDefault(0)
        if (removed > 0) {
            android.util.Log.i("LapseClock", "已删除 ${dates.size} 天的源照片 $removed 张")
            runCatching { capture.refreshCountsNow() }
        }
    }

    /** 设置页「立即拍一张」：手动试拍，不打断定时时刻表 */
    fun captureNow() = viewModelScope.launch { capture.shootNow() }

    /** 双击时钟：按「自动 → 墨白 → 暖夜 → 青瓷 → 胶片 → 赛博」循环 */
    fun cycleTheme() = viewModelScope.launch {
        repo.setThemeId(com.lapseclock.ui.theme.Themes.nextChoice(settings.value.themeId).id)
    }

    override fun onCleared() {
        capture.stop()
        export.cancel()
    }

    companion object {
        /** 天气/积存金常规刷新间隔 */
        private const val REFRESH_INTERVAL_MS = 5 * 60_000L
    }
}
