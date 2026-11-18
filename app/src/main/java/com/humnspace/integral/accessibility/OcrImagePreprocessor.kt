package com.humnspace.integral.accessibility

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.sqrt

/** Makes low-contrast screen text easier to recognize while bounding OCR memory use. */
object OcrImagePreprocessor {
    fun prepare(source: Bitmap, upscaleSmallText: Boolean, maxPixels: Int = 4_000_000): Bitmap {
        val area = source.width.toLong() * source.height.toLong()
        val maxEdge = maxOf(source.width, source.height).toFloat()
        var scale = 1f
        if (upscaleSmallText) {
            scale = minOf(2f, 2400f / maxEdge, sqrt(maxPixels.toFloat() / area.coerceAtLeast(1L)))
            if (maxEdge < 1400f) scale = minOf(2f, 1400f / maxEdge)
        }
        scale = scale.coerceAtLeast(0.5f)
        val outWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val outHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val contrast = ColorMatrix(floatArrayOf(
            1.18f, 0f, 0f, 0f, -23f,
            0f, 1.18f, 0f, 0f, -23f,
            0f, 0f, 1.18f, 0f, -23f,
            0f, 0f, 0f, 1f, 0f
        ))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(contrast)
        }
        Canvas(output).drawBitmap(source, null, android.graphics.Rect(0, 0, outWidth, outHeight), paint)
        return output
    }
}
