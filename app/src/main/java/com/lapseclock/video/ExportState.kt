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
import java.util.concurrent.atomic.AtomicBoolean

sealed class ExportState {
    object Idle : ExportState()

    data class Running(val done: Int, val total: Int) : ExportState() {
        val progress: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }

    data class Done(
        val uri: Uri,
        val durationMs: Long,
        val frames: Int,
        val from: LocalDate,
        val to: LocalDate,
        /** 每日自动合成，而不是用户手动点开始 */
        val auto: Boolean = false,
    ) : ExportState()

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

    init {
        // 进程被杀会留下没写完的 mp4，启动时清掉；只清理 10 分钟前的，避免撞上进行中的导出
        scope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 10 * 60_000L
            File(app.cacheDir, "export").listFiles()
                ?.filter { it.isFile && it.lastModified() < cutoff }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    fun start(
        from: LocalDate,
        to: LocalDate,
        fps: Int,
        width: Int,
        height: Int,
        auto: Boolean = false,
    ) {
        if (job?.isActive == true) return
        cancelled.set(false)
        // 取帧列表放到 IO；越大范围越不能卡在主线程
        _state.value = ExportState.Running(0, 0)

        job = scope.launch {
            var outFile: File? = null
            try {
                val files = withContext(Dispatchers.IO) {
                    store.ensureIndex()
                    store.filesBetweenInclusive(from, to)
                }
                if (files.isEmpty()) {
                    _state.value = ExportState.Failed("所选日期范围内没有照片")
                    return@launch
                }
                _state.value = ExportState.Running(0, files.size)

                val frames = withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "export").apply { mkdirs() }
                    val out = File(dir, timelapseOutputName(from, to, fps))
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
                _state.value = ExportState.Done(uri, durationMs, frames, from, to, auto)
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

    /** 导出完成后重新回到可配置状态，让用户能换范围再合成一次 */
    fun reset() {
        if (job?.isActive == true) return
        cancelled.set(false)
        _state.value = ExportState.Idle
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
            put(MediaStore.Video.Media.DATE_TAKEN, now)
            // 写完再置 0，否则相册里会先出现一个播不了的半成品
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return try {
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw RuntimeException("MediaStore insert 返回 null")
            try {
                resolver.openOutputStream(uri)?.use { os ->
                    file.inputStream().use { it.copyTo(os) }
                } ?: throw RuntimeException("无法打开输出流")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
                file.delete()
                uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        } catch (_: Throwable) {
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        }
    }

}
