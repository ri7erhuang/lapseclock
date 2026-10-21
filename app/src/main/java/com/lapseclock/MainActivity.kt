package com.lapseclock

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.lapseclock.ui.SettingsSheet
import com.lapseclock.ui.ExportScreen
import com.lapseclock.ui.LapseApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCapture() else vm.permissionNeeded.value = true
        }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) vm.refreshWeatherNow()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 横屏时前置挖孔会落到屏幕左侧，默认策略会把整块内容内缩 80 多像素，
        // 左对齐的页面（第二屏）会直接露出黑边。ALWAYS 让内容延伸到挖孔区域底下。
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        if (hasCameraPermission()) startCapture() else {
            vm.permissionNeeded.value = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        if (vm.weatherConfigured && vm.settings.value.useLocation && !hasLocationPermission()) {
            locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }

        setContent {
            val settings by vm.settings.collectAsState()
            val screen by vm.screen.collectAsState()
            val permissionNeeded by vm.permissionNeeded.collectAsState()
            val status by vm.capture.status.collectAsState()
            val exportState by vm.export.state.collectAsState()
            val weather by vm.weather.collectAsState()
            val gold by vm.gold.collectAsState()

            LapseApp(
                screen = screen,
                settings = settings,
                status = status,
                permissionNeeded = permissionNeeded,
                exportState = exportState,
                weather = weather,
                gold = gold,
                onRequestPermission = { cameraPermission.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { vm.screen.value = Screen.Settings },
                onOpenExport = { vm.screen.value = Screen.Export },
                onEnterSleep = { vm.enterSleep() },
                onWakeUp = { vm.wakeUp() },
                onTakeShot = { vm.captureNow() },
                onCycleTheme = { vm.cycleTheme() },
                onBack = { vm.screen.value = Screen.Clock },
                onSetInterval = { vm.setInterval(it) },
                onSetResolution = { w, h -> vm.setResolution(w, h) },
                onSetUse24h = { vm.setUse24h(it) },
                onSetPaused = { vm.setPaused(it) },
                onSetAutoClean = { vm.setAutoClean(it) },
                onSetSoftBrightness = { vm.setSoftBrightness(it) },
                onSetBurnShiftLevel = { vm.setBurnShiftLevel(it) },
                onSetWatermarkEnabled = { vm.setWatermarkEnabled(it) },
                onSetWatermarkText = { vm.setWatermarkText(it) },
                onSetWatermarkShowTime = { vm.setWatermarkShowTime(it) },
                onSetWeatherCity = { vm.setWeatherCity(it) },
                onSetUseLocation = { vm.setUseLocation(it) },
                onSetThemeId = { vm.setThemeId(it) },
                onSetAutoExport = { vm.setAutoExport(it) },
                store = vm.store,
                onStartExport = { from, to, fps, w, h -> vm.export.start(from, to, fps, w, h) },
                onCancelExport = { vm.export.cancel() },
                onResetExport = { vm.export.reset() },
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission()) startCapture()
    }

    override fun onStop() {
        super.onStop()
        vm.capture.stop()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCapture() {
        lifecycleScope.launch {
            vm.permissionNeeded.value = false
            vm.capture.start(this@MainActivity)
        }
    }
}
