package com.lapseclock.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 照片水印：右下角白字黑边，可选拍摄时间。
 * 在 JPEG 落盘前烧进画面，延时视频自然继承。
 */
object Watermark {

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun draw(bitmap: Bitmap, text: String, showTime: Boolean) {
        val label = text.trim()
        val time = if (showTime) {
            timeFmt.format(LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()))
        } else {
            null
        }
        if (label.isEmpty() && time == null) return

        val lines = listOfNotNull(label.ifEmpty { null }, time)

        val canvas = Canvas(bitmap)
        val fontSize = bitmap.height * 0.035f
        val padding = fontSize * 1.4f

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = fontSize
            style = Paint.Style.STROKE
            strokeWidth = fontSize / 8f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            typeface = Typeface.DEFAULT_BOLD
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = fontSize
            typeface = Typeface.DEFAULT_BOLD
        }

        val lineHeight = fontSize * 1.25f
        val metrics = fill.fontMetrics
        var baseline = bitmap.height - padding - metrics.descent

        for (i in lines.indices.reversed()) {
            val line = lines[i]
            val x = bitmap.width - padding - fill.measureText(line)
            canvas.drawText(line, x, baseline, stroke)
            canvas.drawText(line, x, baseline, fill)
            baseline -= lineHeight
        }
    }
}
