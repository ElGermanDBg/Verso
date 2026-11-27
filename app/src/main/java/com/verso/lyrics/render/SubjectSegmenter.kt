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
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
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

    internal fun computeSmartBrushAlpha(
        oldAlpha: Int,
        dist: Float,
        radius: Float,
        colorDist: Float,
        colorTolerance: Float,
        addMode: Boolean
    ): Int {
        if (dist >= radius) return oldAlpha
        val spatialW = 1f - smoothstep(radius * 0.70f, radius, dist)
        val colorW = 1f - smoothstep(colorTolerance * 0.55f, colorTolerance * 1.15f, colorDist)
        val w = (spatialW * colorW).coerceIn(0f, 1f)
        if (w <= 0.02f) return oldAlpha
        return if (addMode) {
            max(oldAlpha, (w * 255f).toInt().coerceIn(0, 255))
        } else {
            min(oldAlpha, ((1f - w) * 255f).toInt().coerceIn(0, 255))
        }
    }

    fun paintSmartMaskSegment(
        sourceBitmap: Bitmap,
        maskBitmap: Bitmap,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radiusPx: Float,
        addMode: Boolean,
        colorTolerance: Float = 46f
    ) {
        if (sourceBitmap.isRecycled || maskBitmap.isRecycled || !maskBitmap.isMutable) return
        if (sourceBitmap.width != maskBitmap.width || sourceBitmap.height != maskBitmap.height) {
            paintMaskSegment(maskBitmap, x1, y1, x2, y2, radiusPx, addMode)
            return
        }
        runCatching {
            val w = maskBitmap.width
            val h = maskBitmap.height
            if (w <= 0 || h <= 0) return@runCatching
            val readableSource = if (sourceBitmap.config == Bitmap.Config.ARGB_8888) {
                sourceBitmap
            } else {
                sourceBitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching
            }
            try {
                val len = hypot(x2 - x1, y2 - y1)
                val r = radiusPx.coerceAtLeast(2.5f)
                val rCeil = ceil(r).toInt()
                val step = max(2f, r * 0.35f)
                val steps = max(1, ceil(len / step).toInt())

                for (s in 0..steps) {
                    val t = s.toFloat() / steps.toFloat()
                    val cx = (x1 + (x2 - x1) * t).toInt().coerceIn(0, w - 1)
                    val cy = (y1 + (y2 - y1) * t).toInt().coerceIn(0, h - 1)

                    // Muestra 3x3 alrededor del centro para obtener el color de referencia
                    val sLeft = (cx - 1).coerceAtLeast(0)
                    val sTop = (cy - 1).coerceAtLeast(0)
                    val sRight = (cx + 1).coerceAtMost(w - 1)
                    val sBottom = (cy + 1).coerceAtMost(h - 1)
                    val sw = sRight - sLeft + 1
                    val sh = sBottom - sTop + 1
                    val refSample = IntArray(sw * sh)
                    readableSource.getPixels(refSample, 0, sw, sLeft, sTop, sw, sh)
                    var sumR = 0
                    var sumG = 0
                    var sumB = 0
                    for (px in refSample) {
                        sumR += (px shr 16) and 0xFF
                        sumG += (px shr 8) and 0xFF
                        sumB += px and 0xFF
                    }
                    val count = refSample.size.coerceAtLeast(1)
                    val refR = sumR.toFloat() / count
                    val refG = sumG.toFloat() / count
                    val refB = sumB.toFloat() / count

                    val left = (cx - rCeil).coerceAtLeast(0)
                    val top = (cy - rCeil).coerceAtLeast(0)
                    val right = (cx + rCeil).coerceAtMost(w - 1)
                    val bottom = (cy + rCeil).coerceAtMost(h - 1)
                    val pw = right - left + 1
                    val ph = bottom - top + 1

                    val srcPixels = IntArray(pw * ph)
                    val maskPixels = IntArray(pw * ph)
                    readableSource.getPixels(srcPixels, 0, pw, left, top, pw, ph)
                    maskBitmap.getPixels(maskPixels, 0, pw, left, top, pw, ph)

                    for (py in 0 until ph) {
                        val dy = (top + py - cy).toFloat()
                        val rowOffset = py * pw
                        for (px in 0 until pw) {
                            val dx = (left + px - cx).toFloat()
                            val dist = hypot(dx, dy)
                            if (dist < r) {
                                val idx = rowOffset + px
                                val rgb = srcPixels[idx]
                                val dr = ((rgb shr 16) and 0xFF) - refR
                                val dg = ((rgb shr 8) and 0xFF) - refG
                                val db = (rgb and 0xFF) - refB
                                val colorDist = sqrt(dr * dr + dg * dg + db * db)
                                val oldAlpha = (maskPixels[idx] ushr 24) and 0xFF
                                val newAlpha = computeSmartBrushAlpha(
                                    oldAlpha = oldAlpha,
                                    dist = dist,
                                    radius = r,
                                    colorDist = colorDist,
                                    colorTolerance = colorTolerance,
                                    addMode = addMode
                                )
                                maskPixels[idx] = Color.argb(newAlpha, 255, 255, 255)
                            }
                        }
                    }
                    maskBitmap.setPixels(maskPixels, 0, pw, left, top, pw, ph)
                }
            } finally {
                if (readableSource !== sourceBitmap) readableSource.recycle()
            }
        }
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
