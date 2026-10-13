package com.lapseclock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lapseclock.capture.CaptureController
import com.lapseclock.capture.PhotoStore
import com.lapseclock.settings.AppSettings
import com.lapseclock.settings.SettingsRepo
import com.lapseclock.video.ExportController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.os.Environment

enum class Screen { Clock, Settings, Export }

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

    override fun onCleared() {
        capture.stop()
        export.cancel()
    }
}
