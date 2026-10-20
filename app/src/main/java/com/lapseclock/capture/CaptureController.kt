package com.lapseclock.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.lapseclock.isSleeping
import com.lapseclock.settings.AppSettings
import com.lapseclock.settings.SettingsRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

data class CaptureStatus(
    val bound: Boolean = false,
    val capturing: Boolean = false,
    val loopActive: Boolean = false,
    val lastShotAt: Long = 0L,
    val lastError: String? = null,
    val sleeping: Boolean = false,
    val skipped: Int = 0,
    val todayCount: Int = 0,
    val totalCount: Int = 0,
    /** 当前处于闲时降频时段（下班后的空办公室没必要 60s 一张） */
    val idle: Boolean = false,
)

/**
 * 无预览拍照循环：固定时刻表 t0 + n×interval 触发；若一张耗时超过间隔，
 * 跳过错过的拍次（只统计不堆积），保证节奏均匀。
 */
class CaptureController(
    private val appContext: Context,
    private val store: PhotoStore,
    private val repo: SettingsRepo,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(CaptureStatus())
    val status: StateFlow<CaptureStatus> = _status

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var loopJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = ContextCompat.getMainExecutor(appContext)

    @Volatile
    private var owner: LifecycleOwner? = null

    /** 启动循环：相机绑定后持续占用，仅在休眠/暂停/停止时释放 */
    suspend fun start(owner: LifecycleOwner) {
        this.owner = owner
        if (loopJob?.isActive != true) startLoop()
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        mainHandler.post {
            try {
                provider?.unbindAll()
            } catch (_: Exception) {
            }
            provider = null
            imageCapture = null
            _status.value = _status.value.copy(bound = false, loopActive = false, capturing = false)
        }
    }

    private suspend fun bindInternal(owner: LifecycleOwner) {
        try {
            val p = awaitFuture(ProcessCameraProvider.getInstance(appContext))
            val s = repo.settings.first()
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(Size(s.targetWidth, s.targetHeight))
                .setJpegQuality(JPEG_QUALITY)
                .build()
            p.unbindAll()
            p.bindToLifecycle(
                owner,
                CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build(),
                capture,
            )
            provider = p
            imageCapture = capture
            _status.value = _status.value.copy(bound = true, lastError = null)
        } catch (t: Throwable) {
            _status.value = _status.value.copy(
                bound = false,
                lastError = "相机绑定失败: ${t.message}",
            )
        }
    }

    private suspend fun unbindInternal() {
        if (imageCapture == null && provider == null) return
        withContext(Dispatchers.Main) {
            try {
                provider?.unbindAll()
            } catch (_: Exception) {
            }
            imageCapture = null
            _status.value = _status.value.copy(bound = false)
        }
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            var t0 = 0L
            var n = 1L
            var lastInterval = -1L
            var skippedTotal = 0
            var lastW = -1
            var lastH = -1

            // 启动即初始化计数，否则暂停/休眠状态下角标会一直显示 0
            refreshCounts()

            while (isActive) {
                val s = repo.settings.first()

                if (isSleeping(s.manualSleep, s.autoSleepSnoozeNight)) {
                    _status.value = _status.value.copy(loopActive = false, sleeping = true)
                    unbindInternal()
                    t0 = 0L
                    delay(1000)
                    continue
                } else if (_status.value.sleeping) {
                    _status.value = _status.value.copy(sleeping = false)
                }

                if (s.paused) {
                    _status.value = _status.value.copy(loopActive = false)
                    unbindInternal()
                    t0 = 0L
                    delay(400)
                    continue
                }

                val idle = s.isIdleHour(java.time.LocalTime.now().hour)
                if (_status.value.idle != idle) _status.value = _status.value.copy(idle = idle)
                val intervalMs = s.effectiveIntervalSec(java.time.LocalTime.now().hour) * 1000L
                if (intervalMs != lastInterval) {
                    // 进出闲时时段时时刻表要重排，否则会继续沿用白天的节奏
                    t0 = 0L
                    lastInterval = intervalMs
                }

                // 分辨率变化：释放旧绑定，拍前用新分辨率重绑
                if (s.targetWidth != lastW || s.targetHeight != lastH) {
                    lastW = s.targetWidth
                    lastH = s.targetHeight
                    if (imageCapture != null) unbindInternal()
                }

                // 首次绑定（此后持续占用，不在拍后解绑）
                if (imageCapture == null) {
                    owner?.let { bindInternal(it) }
                    if (imageCapture == null) {
                        _status.value = _status.value.copy(loopActive = false)
                        t0 = 0L
                        delay(1000)
                        continue
                    }
                }

                if (t0 == 0L) {
                    t0 = System.currentTimeMillis()
                    n = 1L
                }

                var target = t0 + n * intervalMs
                var now = System.currentTimeMillis()
                if (target > now) {
                    // 分片等待：每段复查一次设置，休眠/暂停最多 1 秒内生效，不必等满整个间隔
                    var interrupted = false
                    while (isActive) {
                        val left = target - System.currentTimeMillis()
                        if (left <= 0L) break
                        delay(left.coerceAtMost(WAIT_SLICE_MS))
                        val cur = repo.settings.first()
                        if (cur.paused || isSleeping(cur.manualSleep, cur.autoSleepSnoozeNight)) {
                            interrupted = true
                            break
                        }
                    }
                    if (interrupted) {
                        t0 = 0L
                        continue
                    }
                } else {
                    val lateness = now - target
                    if (lateness >= intervalMs) {
                        val skip = lateness / intervalMs
                        n += skip
                        skippedTotal += skip.toInt()
                        _status.value = _status.value.copy(skipped = skippedTotal)
                        target = t0 + n * intervalMs
                        if (target > now) delay(target - now)
                    }
                }

                if (!isActive) break

                _status.value = _status.value.copy(loopActive = true)
                val ok = shoot(s)
                refreshCounts()

                // 拍完后把时刻表推进到未来的拍次，避免补拍堆积
                n++
                now = System.currentTimeMillis()
                if (t0 + n * intervalMs <= now) {
                    val behind = (now - t0) / intervalMs + 1
                    if (behind > n) {
                        skippedTotal += (behind - n).toInt()
                        _status.value = _status.value.copy(skipped = skippedTotal)
                        n = behind
                    }
                }

                if (!ok) delay(intervalMs.coerceAtMost(3000L))
            }
        }
    }


    private suspend fun shoot(s: AppSettings): Boolean {
        val ic = imageCapture ?: return false
        _status.value = _status.value.copy(capturing = true)
        val file = store.newFile()
        val ok = if (s.watermarkEnabled && (s.watermarkText.isNotBlank() || s.watermarkShowTime)) {
            shootWithWatermark(ic, file, s)
        } else {
            shootPlain(ic, file)
        }
        _status.value = _status.value.copy(
            capturing = false,
            lastShotAt = System.currentTimeMillis(),
            lastError = if (ok) null else _status.value.lastError,
        )
        if (ok && s.autoCleanDays > 0) {
            scope.launch { store.deleteOlderThan(s.autoCleanDays) }
        }
        return ok
    }

    private suspend fun shootPlain(ic: ImageCapture, file: File): Boolean {
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        return suspendCancellableCoroutine { cont ->
            try {
                ic.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(e: ImageCaptureException) {
                        _status.value = _status.value.copy(lastError = e.message)
                        if (cont.isActive) cont.resume(false)
                    }
                })
            } catch (t: Throwable) {
                _status.value = _status.value.copy(lastError = t.message)
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    private suspend fun shootWithWatermark(ic: ImageCapture, file: File, s: AppSettings): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                ic.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        // 回调在主线程，重活切后台
                        scope.launch(Dispatchers.Default) {
                            val ok = try {
                                saveWatermarked(image, file, s)
                            } catch (t: Throwable) {
                                _status.value = _status.value.copy(lastError = t.message)
                                false
                            } finally {
                                image.close()
                            }
                            if (cont.isActive) cont.resume(ok)
                        }
                    }

                    override fun onError(e: ImageCaptureException) {
                        _status.value = _status.value.copy(lastError = e.message)
                        if (cont.isActive) cont.resume(false)
                    }
                })
            } catch (t: Throwable) {
                _status.value = _status.value.copy(lastError = t.message)
                if (cont.isActive) cont.resume(false)
            }
        }

    /** 已在 Dispatchers.Default 上调用 */
    private fun saveWatermarked(image: ImageProxy, file: File, s: AppSettings): Boolean {
        val buffer = image.planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // inMutable 让解码结果本身可写，省掉下面「不可变位图再拷一份」的全尺寸拷贝（1600×1200 约 7.7MB）
        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return false

        val rotation = image.imageInfo.rotationDegrees
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        } else if (!bmp.isMutable) {
            // 兜底：个别机型忽略 inMutable，Canvas 需要可变位图
            val mutable = bmp.copy(Bitmap.Config.ARGB_8888, true)
            bmp.recycle()
            bmp = mutable
        }

        Watermark.draw(bmp, s.watermarkText, s.watermarkShowTime)

        file.outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        bmp.recycle()
        return true
    }

    private suspend fun refreshCounts() {
        // 目录扫描放到 IO 线程：拍照循环跑在 Main 上，不能在这里阻塞 UI
        val counts = withContext(Dispatchers.IO) { store.counts() }
        _status.value = _status.value.copy(
            todayCount = counts.today,
            totalCount = counts.total,
        )
    }

    private suspend fun <T> awaitFuture(f: ListenableFuture<T>): T =
        suspendCancellableCoroutine { cont ->
            f.addListener({ if (cont.isActive) cont.resume(f.get()) }, executor)
        }

    companion object {
        /** 90 与 100 肉眼几乎无差，单张体积约省一半；CameraX 直存与水印重编码共用 */
        private const val JPEG_QUALITY = 90

        /** 时刻表等待的分片长度：每段结束复查设置，保证休眠/暂停秒级生效 */
        private const val WAIT_SLICE_MS = 1000L
    }
}
