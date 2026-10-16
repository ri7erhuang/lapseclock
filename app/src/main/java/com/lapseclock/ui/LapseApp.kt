package com.lapseclock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenExport: () -> Unit,
    onBack: () -> Unit,
    onSetInterval: (Int) -> Unit,
    onSetResolution: (Int, Int) -> Unit,
    onSetUse24h: (Boolean) -> Unit,
    onSetShowSeconds: (Boolean) -> Unit,
    onSetPaused: (Boolean) -> Unit,
    onSetAutoClean: (Int) -> Unit,
    onSetSoftBrightness: (Boolean) -> Unit,
    onSetBurnShiftLevel: (Int) -> Unit,
    onSetWatermarkEnabled: (Boolean) -> Unit,
    onSetWatermarkText: (String) -> Unit,
    onSetWatermarkShowTime: (Boolean) -> Unit,
    onSetWeatherCity: (String) -> Unit,
    onSetUseLocation: (Boolean) -> Unit,
    store: PhotoStore,
    onStartExport: (LocalDate, LocalDate, Int, Int, Int) -> Unit,
    onCancelExport: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        BackHandler(enabled = screen != Screen.Clock) {
            onBack()
        }
        ClockScreen(
            settings = settings,
            status = status,
            permissionNeeded = permissionNeeded,
            onRequestPermission = onRequestPermission,
            onOpenSettings = onOpenSettings,
            onOpenExport = onOpenExport,
            weather = weather,
        )

        when (screen) {
            Screen.Settings -> SettingsSheet(
                settings = settings,
                store = store,
                onDismiss = onBack,
                onSetInterval = onSetInterval,
                onSetResolution = onSetResolution,
                onSetUse24h = onSetUse24h,
                onSetShowSeconds = onSetShowSeconds,
                onSetPaused = onSetPaused,
                onSetAutoClean = onSetAutoClean,
                onSetSoftBrightness = onSetSoftBrightness,
                onSetBurnShiftLevel = onSetBurnShiftLevel,
                onSetWatermarkEnabled = onSetWatermarkEnabled,
                onSetWatermarkText = onSetWatermarkText,
                onSetWatermarkShowTime = onSetWatermarkShowTime,
                onSetWeatherCity = onSetWeatherCity,
                onSetUseLocation = onSetUseLocation,
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
