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
import com.lapseclock.capture.CaptureStatus
import com.lapseclock.capture.PhotoStore
import com.lapseclock.settings.AppSettings
import com.lapseclock.video.ExportState
import com.lapseclock.weather.WeatherUi
import java.time.LocalDate

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
    onCycleTheme: () -> Unit,
    onBack: () -> Unit,
    onSetInterval: (Int) -> Unit,
    onSetResolution: (Int, Int) -> Unit,
    onSetUse24h: (Boolean) -> Unit,
    onSetPaused: (Boolean) -> Unit,
    onSetAutoClean: (Int) -> Unit,
    onSetSoftBrightness: (Boolean) -> Unit,
    onSetBurnShiftLevel: (Int) -> Unit,
    onSetWatermarkEnabled: (Boolean) -> Unit,
    onSetWatermarkText: (String) -> Unit,
    onSetWatermarkShowTime: (Boolean) -> Unit,
    onSetWeatherCity: (String) -> Unit,
    onSetUseLocation: (Boolean) -> Unit,
    onSetThemeId: (String) -> Unit,
    onSetIdleStartHour: (Int) -> Unit,
    onSetIdleInterval: (Int) -> Unit,
    store: PhotoStore,
    onStartExport: (LocalDate, LocalDate, Int, Int, Int) -> Unit,
    onCancelExport: () -> Unit,
) {
    // 第二屏只是时钟的一个「页」，不进 Screen 枚举——它不改变 App 的导航状态
    var infoOpen by remember { mutableStateOf(false) }
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
            weather = weather,
            gold = gold,
        )

        if (infoOpen) {
            InfoScreen(
                store = store,
                settings = settings,
                status = status,
                weather = weather,
                onBack = { infoOpen = false },
            )
        }

        when (screen) {
            Screen.Settings -> SettingsSheet(
                settings = settings,
                store = store,
                onDismiss = onBack,
                onSetInterval = onSetInterval,
                onSetResolution = onSetResolution,
                onSetUse24h = onSetUse24h,
                onSetPaused = onSetPaused,
                onSetAutoClean = onSetAutoClean,
                onSetSoftBrightness = onSetSoftBrightness,
                onSetBurnShiftLevel = onSetBurnShiftLevel,
                onSetWatermarkEnabled = onSetWatermarkEnabled,
                onSetWatermarkText = onSetWatermarkText,
                onSetWatermarkShowTime = onSetWatermarkShowTime,
                onSetWeatherCity = onSetWeatherCity,
                onSetUseLocation = onSetUseLocation,
                onSetThemeId = onSetThemeId,
                onSetIdleStartHour = onSetIdleStartHour,
                onSetIdleInterval = onSetIdleInterval,
            )

            Screen.Export -> ExportScreen(
                store = store,
                state = exportState,
                onDismiss = onBack,
                onStart = onStartExport,
                onCancel = onCancelExport,
            )

            Screen.Clock -> Unit
        }
    }
}
