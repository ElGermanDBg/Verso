package com.verso.lyrics.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Recorta a la persona en primer plano usando Google ML Kit Selfie Segmentation (100% on-device).
 */
object SubjectSegmenter {

    private val segmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        Segmentation.getClient(options)
    }

    suspend fun extractForeground(sourceBitmap: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        if (sourceBitmap.isRecycled || sourceBitmap.width <= 0 || sourceBitmap.height <= 0) {
            return@withContext null
        }

        val mask = runCatching {
            suspendCancellableCoroutine { cont ->
                val image = InputImage.fromBitmap(sourceBitmap, 0)
                segmenter.process(image)
                    .addOnSuccessListener { result ->
                        if (cont.isActive) cont.resume(result)
                    }
                    .addOnFailureListener { err ->
                        if (cont.isActive) cont.resumeWithException(err)
                    }
            }
        }.getOrNull() ?: return@withContext null

        val maskWidth = mask.width
        val maskHeight = mask.height
        if (maskWidth <= 0 || maskHeight <= 0) return@withContext null

        val buffer = mask.buffer
        buffer.rewind()
        val total = maskWidth * maskHeight
        val maskPixels = IntArray(total)
        var subjectCount = 0

        for (i in 0 until total) {
            val conf = buffer.float
            if (conf > 0.5f) subjectCount++
            val alpha = (smoothstep(0.32f, 0.72f, conf) * 255f).toInt().coerceIn(0, 255)
            maskPixels[i] = Color.argb(alpha, 255, 255, 255)
        }

        // Si menos del 1.5% de la foto es persona, consideramos que no hay sujeto claro
        if (subjectCount < total * 0.015f) {
            return@withContext null
        }

        val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
        maskBitmap.setPixels(maskPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

        val outputBitmap = Bitmap.createBitmap(sourceBitmap.width, sourceBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(sourceBitmap, 0f, 0f, drawPaint)

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        val dstRect = android.graphics.Rect(0, 0, sourceBitmap.width, sourceBitmap.height)
        canvas.drawBitmap(maskBitmap, null, dstRect, maskPaint)
        maskBitmap.recycle()

        outputBitmap
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
