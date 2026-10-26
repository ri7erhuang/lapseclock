package com.lapseclock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.lapseclock.GoldUi
import com.lapseclock.Screen
import com.lapseclock.calendar.dayInfoOf
import com.lapseclock.capture.CaptureStatus
import com.lapseclock.capture.PhotoStore
import com.lapseclock.settings.AppSettings
import com.lapseclock.video.ExportState
import com.lapseclock.weather.WeatherUi
import com.lapseclock.ui.theme.resolveClockTheme
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
fun LapseApp(
    screen: Screen,
    settings: AppSettings,
    status: CaptureStatus,
    permissionNeeded: Boolean,
    exportState: ExportState,
    weather: WeatherUi?,
    gold: GoldUi?,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExport: () -> Unit,
    onEnterSleep: () -> Unit,
    onWakeUp: () -> Unit,
    onTakeShot: () -> Unit,
    onCycleTheme: () -> Unit,
    onFortuneSeen: () -> Unit,
    onBack: () -> Unit,
    onSetInterval: (Int) -> Unit,
    onSetResolution: (Int, Int) -> Unit,
    onSetUse24h: (Boolean) -> Unit,
    onSetPaused: (Boolean) -> Unit,
    onSetSoftBrightness: (Boolean) -> Unit,
    onSetBurnShiftLevel: (Int) -> Unit,
    onSetWatermarkEnabled: (Boolean) -> Unit,
    onSetWatermarkText: (String) -> Unit,
    onSetWatermarkShowTime: (Boolean) -> Unit,
    onSetWeatherCity: (String) -> Unit,
    onSetUseLocation: (Boolean) -> Unit,
    onSetThemeId: (String) -> Unit,
    onSetAutoExport: (Boolean) -> Unit,
    store: PhotoStore,
    onStartExport: (Collection<LocalDate>, Int, Int, Int) -> Unit,
    onCancelExport: () -> Unit,
    onResetExport: () -> Unit,
    onDeleteSources: (Collection<LocalDate>) -> Unit,
) {
    // 第二屏只是时钟的一个「页」，不进 Screen 枚举——它不改变 App 的导航状态
    var infoOpen by remember { mutableStateOf(false) }

    // 番茄钟：状态挂在这一层，右滑回主页后计时继续走
    val pomodoro = rememberPomodoro()
    var pomodoroOpen by remember { mutableStateOf(false) }
    val clockTheme = remember(settings.themeId, weather?.text) {
        val now = LocalDateTime.now()
        resolveClockTheme(
            settings.themeId,
            now,
            weather?.text,
            dayInfoOf(now.toLocalDate()).specialText,
        )
    }
    LaunchedEffect(screen) {
        if (screen != Screen.Clock) infoOpen = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        BackHandler(enabled = screen != Screen.Clock || infoOpen) {
            if (infoOpen) infoOpen = false else onBack()
        }

        // 只在时钟真正露出时组合它：设置/合成/数据屏是整屏不透明覆盖层，
        // 若时钟仍在下面，既会每秒空转重组，手势也可能穿透到被盖住的时钟上
        if (screen == Screen.Clock && !infoOpen) {
            ClockScreen(
                settings = settings,
                status = status,
                permissionNeeded = permissionNeeded,
                onRequestPermission = onRequestPermission,
                onOpenSettings = onOpenSettings,
                onOpenExport = onOpenExport,
                onEnterSleep = onEnterSleep,
                onWakeUp = onWakeUp,
                onCycleTheme = onCycleTheme,
                onSwipeLeft = { infoOpen = true },
                onSwipeDown = { pomodoroOpen = true },
                onFortuneSeen = onFortuneSeen,
                // 专注进行中时在主页底部留一行，回来只看时间也知道还剩多久
                pomodoroText = if (pomodoro.running) "${pomodoro.label} ${pomodoro.clockText}" else null,
                weather = weather,
                gold = gold,
            )
        }

        if (infoOpen) {
            InfoScreen(
                store = store,
                settings = settings,
                status = status,
                weather = weather,
                onBack = { infoOpen = false },
            )
        }

        if (pomodoroOpen) {
            PomodoroScreen(
                pomodoro = pomodoro,
                theme = clockTheme,
                onDismiss = { pomodoroOpen = false },
            )
        }

        when (screen) {
            Screen.Settings -> SettingsSheet(
                settings = settings,
                status = status,
                store = store,
                onDismiss = onBack,
                onTakeShot = onTakeShot,
                onSetInterval = onSetInterval,
                onSetResolution = onSetResolution,
                onSetUse24h = onSetUse24h,
                onSetPaused = onSetPaused,
                onSetSoftBrightness = onSetSoftBrightness,
                onSetBurnShiftLevel = onSetBurnShiftLevel,
                onSetWatermarkEnabled = onSetWatermarkEnabled,
                onSetWatermarkText = onSetWatermarkText,
                onSetWatermarkShowTime = onSetWatermarkShowTime,
                onSetWeatherCity = onSetWeatherCity,
                onSetUseLocation = onSetUseLocation,
                onSetThemeId = onSetThemeId,
                onSetAutoExport = onSetAutoExport,
            )

            Screen.Export -> ExportScreen(
                store = store,
                state = exportState,
                onDismiss = onBack,
                onStart = onStartExport,
                onCancel = onCancelExport,
                onReset = onResetExport,
                onDeleteSources = onDeleteSources,
            )

            Screen.Clock -> Unit
        }
    }
}
