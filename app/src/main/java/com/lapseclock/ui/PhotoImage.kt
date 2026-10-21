package com.lapseclock.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 解码照片并按 EXIF 纠正方向。
 *
 * CameraX 直存 JPEG 时方向写在 EXIF 里；开了水印则像素已经转正、EXIF 为空。
 * 两种情况都走这里，界面和编码器就不会出现「关水印后照片躺倒」。
 */
suspend fun decodePhoto(file: File, maxWidth: Int, maxHeight: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth &&
            bounds.outHeight / (sample * 2) >= maxHeight
        ) {
            sample *= 2
        }
        // 大图再压一道总像素上限，全屏预览 4000×3000 不降到 12M 以上容易 OOM
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 8_000_000L) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@withContext null

        val orientation = try {
            ExifInterface(file).getAttributeInt(
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
        bmp
    }

@Composable
fun PhotoImage(
    file: File?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxWidth: Int = 800,
    maxHeight: Int = 800,
) {
    val bitmap by produceState<ImageBitmap?>(null, file, maxWidth, maxHeight) {
        value = file?.let { decodePhoto(it, maxWidth, maxHeight)?.asImageBitmap() }
    }
    Box(modifier = modifier) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}
