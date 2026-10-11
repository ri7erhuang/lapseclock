package com.lapseclock.video

import android.app.Application
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.lapseclock.capture.PhotoStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

sealed class ExportState {
    object Idle : ExportState()

    data class Running(val done: Int, val total: Int) : ExportState() {
        val progress: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }

    data class Done(val uri: Uri, val durationMs: Long, val frames: Int) : ExportState()

    data class Failed(val message: String) : ExportState()

    object Cancelled : ExportState()
}

class ExportController(
    private val app: Application,
    private val store: PhotoStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state

    private var job: Job? = null
    private val cancelled = AtomicBoolean(false)

    fun start(from: LocalDate, to: LocalDate, fps: Int, width: Int, height: Int) {
        if (job?.isActive == true) return
        val files = store.filesBetweenInclusive(from, to)
        if (files.isEmpty()) {
            _state.value = ExportState.Failed("所选日期范围内没有照片")
            return
        }
        cancelled.set(false)
        _state.value = ExportState.Running(0, files.size)

        job = scope.launch {
            var outFile: File? = null
            try {
                val frames = withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "export").apply { mkdirs() }
                    val stamp = System.currentTimeMillis()
                    val out = File(dir, "timelapse_$stamp.mp4")
                    outFile = out
                    val enc = TimelapseEncoder(
                        files = files,
                        outFile = out,
                        fps = fps,
                        outWidth = width,
                        outHeight = height,
                        isCancelled = { cancelled.get() },
                        onProgress = { d, t -> _state.value = ExportState.Running(d, t) },
                    )
                    enc.encode()
                }

                if (cancelled.get() || frames == 0) {
                    outFile?.delete()
                    _state.value = ExportState.Cancelled
                    return@launch
                }

                val out = outFile!!
                val durationMs = withContext(Dispatchers.IO) { readDurationMs(out) }
                val uri = withContext(Dispatchers.IO) { publish(out) }
                _state.value = ExportState.Done(uri, durationMs, frames)
            } catch (t: Throwable) {
                outFile?.delete()
                _state.value = ExportState.Failed(t.message ?: t.toString())
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        if (job?.isActive != true) _state.value = ExportState.Idle
    }

    fun reset() {
        cancelled.set(true)
        if (job?.isActive != true) _state.value = ExportState.Idle
    }

    private fun readDurationMs(file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0").toLong()
        } catch (_: Exception) {
            0L
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    /** 写入系统相册 Movies/Timelapse/，失败则回退 FileProvider 分享缓存文件 */
    private fun publish(file: File): Uri {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/Timelapse",
            )
            put(MediaStore.Video.Media.DATE_ADDED, now / 1000)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                put(MediaStore.Video.Media.DATE_TAKEN, now)
            }
        }
        return try {
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw RuntimeException("MediaStore insert 返回 null")
            resolver.openOutputStream(uri)?.use { os ->
                file.inputStream().use { it.copyTo(os) }
            } ?: throw RuntimeException("无法打开输出流")
            file.delete()
            uri
        } catch (_: Throwable) {
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        }
    }

    companion object {
        val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
