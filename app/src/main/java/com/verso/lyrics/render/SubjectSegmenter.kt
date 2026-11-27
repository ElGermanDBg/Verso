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
import kotlin.math.exp
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class SubjectMaskResult(
    val maskBitmap: Bitmap,
    val foregroundBitmap: Bitmap
)

/**
 * Recorta a la persona en primer plano usando Google ML Kit Selfie Segmentation (100% on-device),
 * con erosión suave de halo, refinamiento guiado por color y soporte de edición manual de máscara.
 */
object SubjectSegmenter {

    private val segmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        Segmentation.getClient(options)
    }

    suspend fun extractForeground(sourceBitmap: Bitmap): Bitmap? {
        val res = extractMaskAndForeground(sourceBitmap) ?: return null
        res.maskBitmap.recycle()
        return res.foregroundBitmap
    }

    suspend fun extractMaskAndForeground(sourceBitmap: Bitmap): SubjectMaskResult? = withContext(Dispatchers.Default) {
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
        val rawConf = FloatArray(total)
        var subjectCount = 0

        for (i in 0 until total) {
            val conf = buffer.float
            rawConf[i] = conf
            if (conf > 0.5f) subjectCount++
        }

        if (subjectCount < total * 0.015f) {
            return@withContext null
        }

        // Paso 1: Erosión suave 3x3 en la zona de frontera para recortar 1px de halo del fondo
        val eroded = FloatArray(total)
        for (y in 0 until maskHeight) {
            val y0 = (y - 1).coerceAtLeast(0)
            val y1 = (y + 1).coerceAtMost(maskHeight - 1)
            for (x in 0 until maskWidth) {
                val idx = y * maskWidth + x
                val center = rawConf[idx]
                if (center <= 0.05f || center >= 0.98f) {
                    eroded[idx] = center
                } else {
                    val x0 = (x - 1).coerceAtLeast(0)
                    val x1 = (x + 1).coerceAtMost(maskWidth - 1)
                    var minVal = center
                    for (ny in y0..y1) {
                        for (nx in x0..x1) {
                            minVal = min(minVal, rawConf[ny * maskWidth + nx])
                        }
                    }
                    eroded[idx] = 0.65f * minVal + 0.35f * center
                }
            }
        }

        // Paso 2: Suavizado guiado por color de la foto (Joint Bilateral 3x3 rápido)
        val needsScale = sourceBitmap.width != maskWidth || sourceBitmap.height != maskHeight
        val scaledPhoto = if (needsScale) {
            Bitmap.createScaledBitmap(sourceBitmap, maskWidth, maskHeight, true)
        } else {
            sourceBitmap
        }
        val readablePhoto = if (scaledPhoto.config == Bitmap.Config.ARGB_8888) {
            scaledPhoto
        } else {
            scaledPhoto.copy(Bitmap.Config.ARGB_8888, false)
        }
        val photoPixels = IntArray(total)
        readablePhoto.getPixels(photoPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
        if (readablePhoto !== scaledPhoto && readablePhoto !== sourceBitmap) readablePhoto.recycle()
        if (scaledPhoto !== sourceBitmap) scaledPhoto.recycle()

        val refined = FloatArray(total)
        val sigmaColorSq = 28f * 28f * 3f
        for (y in 0 until maskHeight) {
            val y0 = (y - 1).coerceAtLeast(0)
            val y1 = (y + 1).coerceAtMost(maskHeight - 1)
            for (x in 0 until maskWidth) {
                val idx = y * maskWidth + x
                val c = eroded[idx]
                if (c <= 0.05f || c >= 0.95f) {
                    refined[idx] = c
                } else {
                    val centerRgb = photoPixels[idx]
                    val cr = (centerRgb shr 16) and 0xFF
                    val cg = (centerRgb shr 8) and 0xFF
                    val cb = centerRgb and 0xFF
                    val x0 = (x - 1).coerceAtLeast(0)
                    val x1 = (x + 1).coerceAtMost(maskWidth - 1)

                    var sumW = 0f
                    var sumConf = 0f
                    for (ny in y0..y1) {
                        for (nx in x0..x1) {
                            val nIdx = ny * maskWidth + nx
                            val nRgb = photoPixels[nIdx]
                            val dr = ((nRgb shr 16) and 0xFF) - cr
                            val dg = ((nRgb shr 8) and 0xFF) - cg
                            val db = (nRgb and 0xFF) - cb
                            val distSq = (dr * dr + dg * dg + db * db).toFloat()
                            val w = exp(-distSq / sigmaColorSq)
                            sumW += w
                            sumConf += w * eroded[nIdx]
                        }
                    }
                    refined[idx] = if (sumW > 0f) sumConf / sumW else c
                }
            }
        }

        // Paso 3: Curva de opacidad nítida con anti-aliasing limpio
        val maskPixels = IntArray(total)
        for (i in 0 until total) {
            val alpha = (smoothstep(0.50f, 0.85f, refined[i]) * 255f).toInt().coerceIn(0, 255)
            maskPixels[i] = Color.argb(alpha, 255, 255, 255)
        }

        val rawMaskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
        rawMaskBitmap.setPixels(maskPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

        val fullMaskBitmap = if (maskWidth == sourceBitmap.width && maskHeight == sourceBitmap.height) {
            rawMaskBitmap
        } else {
            val scaled = Bitmap.createBitmap(sourceBitmap.width, sourceBitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(scaled).drawBitmap(
                rawMaskBitmap,
                null,
                android.graphics.Rect(0, 0, sourceBitmap.width, sourceBitmap.height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            rawMaskBitmap.recycle()
            scaled
        }

        val foreground = applyMask(sourceBitmap, fullMaskBitmap)
        SubjectMaskResult(maskBitmap = fullMaskBitmap, foregroundBitmap = foreground)
    }

    fun createEmptyMask(sourceBitmap: Bitmap): Bitmap =
        Bitmap.createBitmap(sourceBitmap.width, sourceBitmap.height, Bitmap.Config.ARGB_8888)

    fun applyMask(sourceBitmap: Bitmap, maskBitmap: Bitmap): Bitmap {
        val outputBitmap = Bitmap.createBitmap(sourceBitmap.width, sourceBitmap.height, Bitmap.Config.ARGB_8888)
        if (sourceBitmap.isRecycled || maskBitmap.isRecycled) return outputBitmap
        val canvas = Canvas(outputBitmap)
        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(sourceBitmap, 0f, 0f, drawPaint)

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        val dstRect = android.graphics.Rect(0, 0, sourceBitmap.width, sourceBitmap.height)
        canvas.drawBitmap(maskBitmap, null, dstRect, maskPaint)
        return outputBitmap
    }

    fun paintMaskSegment(
        maskBitmap: Bitmap,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radiusPx: Float,
        addMode: Boolean
    ) {
        if (maskBitmap.isRecycled || !maskBitmap.isMutable) return
        runCatching {
            val canvas = Canvas(maskBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = (radiusPx * 2f).coerceAtLeast(4f)
                if (addMode) {
                    color = Color.WHITE
                } else {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
            }
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
