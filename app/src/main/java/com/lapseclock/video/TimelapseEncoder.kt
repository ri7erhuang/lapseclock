package com.lapseclock.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 延时编码管线：Bitmap → GL 纹理（center-crop）→ MediaCodec(Surface 输入, H.264) → MediaMuxer(MP4)
 *
 * 单线程阻塞执行，调用方决定线程；进度与取消通过回调协作。
 */
class TimelapseEncoder(
    private val files: List<File>,
    private val outFile: File,
    private val fps: Int,
    private val outWidth: Int,
    private val outHeight: Int,
    private val isCancelled: () -> Boolean,
    private val onProgress: (done: Int, total: Int) -> Unit,
) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false

    /** 返回实际写入的帧数 */
    fun encode(): Int {
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(
                MediaFormat.KEY_BIT_RATE,
                (outWidth.toLong() * outHeight * fps / 10).coerceIn(2_000_000L, 20_000_000L).toInt(),
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = c
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = c.createInputSurface()
        c.start()

        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        initEgl(inputSurface)
        setupGl(outWidth, outHeight)

        var frames = 0
        val info = MediaCodec.BufferInfo()
        try {
            for (file in files) {
                if (isCancelled()) break
                val bitmap = decodeForSize(file) ?: continue
                try {
                    val inAspect = bitmap.width.toFloat() / bitmap.height
                    val outAspect = outWidth.toFloat() / outHeight
                    val scaleX = if (inAspect > outAspect) outAspect / inAspect else 1f
                    val scaleY = if (inAspect < outAspect) inAspect / outAspect else 1f
                    drawFrame(bitmap, scaleX, scaleY, frames.toLong())
                } finally {
                    // 单帧绘制抛异常时也要回收，下一帧还能继续或让 releaseAll 收尾
                    bitmap.recycle()
                }
                drainEncoder(false, info)
                frames++
                onProgress(frames, files.size)
            }

            if (!isCancelled()) {
                c.signalEndOfInputStream()
                drainEncoder(true, info)
            }
        } finally {
            releaseAll()
        }
        return frames
    }

    // ---------- 解码 ----------

    private fun decodeForSize(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= outWidth &&
            bounds.outHeight / (sample * 2) >= outHeight
        ) {
            sample *= 2
        }
        // 极端长宽比（如超宽全景）下，仅按两边缩放仍可能解出 20MP+ 的位图；
        // 再压一道总像素上限，避免编码过程中 OOM
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 8_000_000L) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        if (!m.isIdentity) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        }
        return bmp
    }

    // ---------- EGL / GL ----------

    private fun initEgl(surface: android.view.Surface) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }
        eglDisplay = display

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfig, 0) ||
            numConfig[0] <= 0
        ) throw RuntimeException("eglChooseConfig failed")
        val config = configs[0]!!

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
        eglContext = ctx

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, surfaceAttribs, 0)
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed")
        }
        this.eglSurface = eglSurface

        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, ctx)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    private var program = 0
    private var posHandle = 0
    private var texHandle = 0
    private var scaleHandle = 0
    private var samplerHandle = 0
    private var textureId = 0

    private fun setupGl(width: Int, height: Int) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        posHandle = GLES20.glGetAttribLocation(program, "aPos")
        texHandle = GLES20.glGetAttribLocation(program, "aTex")
        scaleHandle = GLES20.glGetUniformLocation(program, "uScale")
        samplerHandle = GLES20.glGetUniformLocation(program, "uTex")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    private val quadPos = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    )
    private val quadTex = floatArrayOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f,
    )
    private val posBuf = ByteBuffer.allocateDirect(quadPos.size * 4).order(ByteOrder.nativeOrder())
        .apply { asFloatBuffer().put(quadPos) }
    private val texBuf = ByteBuffer.allocateDirect(quadTex.size * 4).order(ByteOrder.nativeOrder())
        .apply { asFloatBuffer().put(quadTex) }

    private fun drawFrame(bitmap: Bitmap, scaleX: Float, scaleY: Float, frameIndex: Long) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glUniform2f(scaleHandle, scaleX, scaleY)

        posBuf.position(0)
        texBuf.position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 8, posBuf)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 8, texBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")

        eglSurfaceTime(frameIndex)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun eglSurfaceTime(frameIndex: Long) {
        val ptsNs = frameIndex * 1_000_000_000L / fps
        android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
    }

    // ---------- 编码输出 ----------

    private fun drainEncoder(endOfStream: Boolean, info: MediaCodec.BufferInfo) {
        val c = codec ?: return
        var waited = 0
        while (true) {
            val index = c.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    waited++
                    if (waited > 500) return
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val m = muxer ?: return
                    if (muxerStarted) throw RuntimeException("format changed twice")
                    val newFormat = c.outputFormat
                    trackIndex = m.addTrack(newFormat)
                    m.start()
                    muxerStarted = true
                }

                index >= 0 -> {
                    val buffer = c.getOutputBuffer(index)
                        ?: throw RuntimeException("getOutputBuffer($index) null")
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size > 0) {
                        if (!muxerStarted) throw RuntimeException("muxer 未启动")
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer!!.writeSampleData(trackIndex, buffer, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    c.releaseOutputBuffer(index, false)
                    if (eos) return
                }
            }
        }
    }

    private fun releaseAll() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (_: Exception) {
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE

        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null

        try {
            if (muxerStarted) muxer?.stop()
        } catch (_: Exception) {
        }
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        muxerStarted = false
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            uniform vec2 uScale;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (aTex - 0.5) * uScale + 0.5;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTex);
            }
        """

        private fun loadShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("shader compile failed: $log")
            }
            return shader
        }

        private fun createProgram(vertex: String, fragment: String): Int {
            val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(p)
                GLES20.glDeleteProgram(p)
                throw RuntimeException("program link failed: $log")
            }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return p
        }

        private fun checkGlError(op: String) {
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                throw RuntimeException("$op: glError 0x${Integer.toHexString(error)}")
            }
        }
    }
}
