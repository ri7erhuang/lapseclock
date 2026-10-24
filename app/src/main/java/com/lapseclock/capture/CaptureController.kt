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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
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
    /** 剩余空间低于 [CaptureController.MIN_FREE_BYTES] 时停拍 */
    val storageLow: Boolean = false,
    val freeBytes: Long = 0L,
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

    /** 绑定/解绑/拍照串行化：试拍与定时拍照不能同时操作 CameraX */
    private val cameraLock = Mutex()

    @Volatile
    private var owner: LifecycleOwner? = null

    /** 启动循环：相机绑定后持续占用，仅在休眠/暂停/停止时释放 */
    suspend fun start(owner: LifecycleOwner) {
        this.owner = owner
        if (loopJob?.isActive != true) startLoop()
    }

    /**
     * 立刻拍一张。用于「试拍 / 取景」——无预览模式下，这是确认拍到哪的唯一办法。
     * 不改变定时时刻表；与定时拍照共用 cameraLock，不会撞车。
     */
    suspend fun shootNow(): Boolean {
        val o = owner ?: run {
            _status.value = _status.value.copy(lastError = "试拍失败：相机未就绪")
            return false
        }
        if (store.freeBytes() < MIN_FREE_BYTES) {
            _status.value = _status.value.copy(
                lastError = "试拍失败：存储空间不足",
                storageLow = true,
            )
            return false
        }
        val s = repo.settings.first()
        val ok = cameraLock.withLock {
            if (imageCapture == null) bindInternal(o)
            val ic = imageCapture
            if (ic == null) {
                _status.value = _status.value.copy(lastError = _status.value.lastError ?: "试拍失败：相机不可用")
                return@withLock false
            }
            val shot = shoot(s)
            // 暂停/休眠时相机本来就不该常驻，试拍后恢复原状
            if (isSleeping(s.manualSleep, s.autoSleepSnoozeNight) || s.paused) unbindInternal()
            shot
        }
        refreshCounts()
        return ok
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
            // 绑定失败时清掉引用，避免下次 shoot 拿到一个没绑定成功的旧 use case
            imageCapture = null
            provider = null
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
            var bindFailures = 0

            // 启动即初始化计数，否则暂停/休眠状态下角标会一直显示 0
            refreshCounts()

            while (isActive) {
                val s = repo.settings.first()

                if (isSleeping(s.manualSleep, s.autoSleepSnoozeNight)) {
                    _status.value = _status.value.copy(loopActive = false, sleeping = true)
                    cameraLock.withLock { unbindInternal() }
                    t0 = 0L
                    delay(1000)
                    continue
                } else if (_status.value.sleeping) {
                    _status.value = _status.value.copy(sleeping = false)
                }

                if (s.paused) {
                    _status.value = _status.value.copy(loopActive = false)
                    cameraLock.withLock { unbindInternal() }
                    t0 = 0L
                    delay(400)
                    continue
                }

                // 存储不足时停拍并释放相机，等空间恢复后自动继续；拍照循环不空转
                val free = store.freeBytes()
                if (free < MIN_FREE_BYTES) {
                    _status.value = _status.value.copy(
                        loopActive = false,
                        storageLow = true,
                        freeBytes = free,
                        lastError = "存储空间不足，已暂停拍照",
                    )
                    cameraLock.withLock { unbindInternal() }
                    t0 = 0L
                    delay(60_000)
                    continue
                } else if (_status.value.storageLow) {
                    _status.value = _status.value.copy(
                        storageLow = false,
                        freeBytes = free,
                        // 只清掉存储告警，不要顺手抹掉相机错误
                        lastError = _status.value.lastError
                            ?.takeUnless { it.startsWith("存储空间不足") },
                    )
                }

                val intervalMs = s.intervalSec * 1000L
                if (intervalMs != lastInterval) {
                    // 间隔变更后时刻表要重排，否则会沿用旧节奏
                    t0 = 0L
                    lastInterval = intervalMs
                }

                // 分辨率变化：释放旧绑定，拍前用新分辨率重绑
                if (s.targetWidth != lastW || s.targetHeight != lastH) {
                    lastW = s.targetWidth
                    lastH = s.targetHeight
                    cameraLock.withLock {
                        if (imageCapture != null) unbindInternal()
                    }
                }

                // 首次绑定（此后持续占用，不在拍后解绑）
                if (imageCapture == null) {
                    cameraLock.withLock {
                        if (imageCapture == null) owner?.let { bindInternal(it) }
                    }
                    if (imageCapture == null) {
                        _status.value = _status.value.copy(loopActive = false)
                        t0 = 0L
                        // 相机被别的 App 占用时不要每秒硬试，退避到 30 秒
                        bindFailures++
                        delay((BIND_RETRY_MS shl (bindFailures - 1).coerceAtMost(4)).coerceAtMost(30_000L))
                        continue
                    }
                    bindFailures = 0
                }

                // 相机已绑定、即将进入定时等待：角标应显示「延时中」，
                // 不要停在「启动中」——首次/唤醒后到第一张之间会有一整个间隔的空窗
                if (!_status.value.loopActive) {
                    _status.value = _status.value.copy(loopActive = true)
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
                val ok = cameraLock.withLock { shoot(s) }
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
        val ic = imageCapture ?: run {
            _status.value = _status.value.copy(lastError = "相机未就绪")
            return false
        }
        _status.value = _status.value.copy(capturing = true)
        val file = store.newFile()
        val ok = if (s.watermarkEnabled && (s.watermarkText.isNotBlank() || s.watermarkShowTime)) {
            shootWithWatermark(ic, file, s)
        } else {
            shootPlain(ic, file)
        }
        if (ok) {
            try {
                withContext(Dispatchers.IO) {
                    store.ensureIndex()
                    store.register(file)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 照片已经落盘，索引登记失败只影响本次统计，下次启动会重建
                android.util.Log.w("LapseClock", "照片索引登记失败: ${t.message}")
            }
        } else {
            // 失败的拍摄可能留下 0 字节文件，别让它混进索引/相册统计
            file.delete()
        }
        _status.value = _status.value.copy(
            capturing = false,
            lastShotAt = System.currentTimeMillis(),
            lastError = if (ok) null else _status.value.lastError,
        )
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

        return try {
            Watermark.draw(bmp, s.watermarkText, s.watermarkShowTime)
            file.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } finally {
            // 压缩/写盘抛异常时也要回收，否则整块 ARGB 位图会一直等到 GC
            bmp.recycle()
        }
    }

    /** 外部改动了照片目录（如合成后删除源照片）时，让角标立刻重新统计，不必等到下一次拍照 */
    suspend fun refreshCountsNow() = refreshCounts()

    private suspend fun refreshCounts() {
        // 索引只在启动时扫一次目录，之后 counts() 是 O(1)；仍放 IO，避免首次建索引卡住主线程
        try {
            store.ensureIndex()
            val counts = withContext(Dispatchers.IO) { store.counts() }
            val free = store.freeBytes()
            _status.value = _status.value.copy(
                todayCount = counts.today,
                totalCount = counts.total,
                freeBytes = free,
                storageLow = free < MIN_FREE_BYTES,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.w("LapseClock", "刷新照片统计失败: ${t.message}")
        }
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

        /** 相机绑定失败后的首次重试间隔，之后指数退避到 30 秒 */
        private const val BIND_RETRY_MS = 2000L

        /** 低于该剩余空间就停拍，避免把系统盘写爆 */
        const val MIN_FREE_BYTES = 500L * 1024 * 1024
    }
}
