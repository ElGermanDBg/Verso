package com.verso.lyrics.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.verso.lyrics.model.BrushStyle
import com.verso.lyrics.model.DrawingPoint
import com.verso.lyrics.model.DrawingStroke
import com.verso.lyrics.model.LyricAlignment
import com.verso.lyrics.model.LyricAnimation
import com.verso.lyrics.model.LyricEffect
import com.verso.lyrics.model.LyricFont
import com.verso.lyrics.model.LyricLine
import com.verso.lyrics.model.LyricStyle
import com.verso.lyrics.model.PhotoFilter
import com.verso.lyrics.model.StoryConfig
import com.verso.lyrics.model.activeLyricIndex
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renderizador unificado sobre [Canvas] utilizado tanto para la vista previa en tiempo real
 * como para la generación de frames del video MP4 exportado.
 */
object LyricFrameRenderer {

    private val typefaceCache = ConcurrentHashMap<LyricFont, Typeface>()

    /** Stamp procedural de tiza 96×96 con porosidad orgánica de papel y estrías direccionales. */
    private val chalkStampBitmap: Bitmap by lazy { createChalkStampBitmap(96) }

    private data class AnimatedLineFrame(
        val text: CharSequence,
        val offsetY: Float = 0f,
        val scale: Float = 1f,
        val alpha: Int = 255,
        val glowColor: Int = Color.BLACK,
        val glowRadius: Float = 0f
    )

    fun drawFrame(
        context: Context,
        canvas: Canvas,
        width: Int,
        height: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        positionMs: Long,
        photoBitmap: Bitmap?,
        albumArtBitmap: Bitmap?,
        foregroundBitmap: Bitmap? = null,
        maskOverlayBitmap: Bitmap? = null,
        showMaskOverlay: Boolean = false,
        showCenterGuides: Boolean = false,
        beatPulse: Float = 0f
    ) {
        if (width <= 0 || height <= 0) return

        drawBackground(canvas, width, height, photoBitmap, config.backgroundDim, config.photoFilter)

        if (config.strokes.isNotEmpty()) {
            drawStrokes(canvas, width, height, config.strokes)
        }

        if (showCenterGuides) {
            drawCenterGuides(canvas, width, height, config)
        }

        val effectivePosMs = (positionMs + config.lyricsOffsetMs).coerceAtLeast(0L)
        val activeIdx = activeLyricIndex(lines, effectivePosMs)
        val typeface = resolveTypeface(context, config.font)

        if (lines.isEmpty()) {
            drawEmptyHint(context, canvas, width, height, config)
        } else {
            drawLyricsLayer(
                canvas = canvas,
                width = width,
                height = height,
                config = config,
                lines = lines,
                activeIdx = activeIdx,
                posMs = effectivePosMs,
                typeface = typeface,
                outlinePassOnly = false,
                beatPulse = beatPulse
            )
        }

        // Capa de profundidad: persona recortada encima de las letras
        val hasForeground = config.lyricsBehindSubject && foregroundBitmap != null && !foregroundBitmap.isRecycled
        if (hasForeground) {
            val fg = foregroundBitmap!!
            val dstRect = computeCenterCropRect(
                fg.width.toFloat(),
                fg.height.toFloat(),
                width,
                height
            )
            val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = colorFilterForForeground(config.photoFilter, config.backgroundDim)
            }
            canvas.drawBitmap(fg, null, dstRect, fgPaint)

            // Efecto Doble capa: contorno hueco fino por encima de la silueta
            if (config.effect == LyricEffect.HOLLOW_OVERLAP && lines.isNotEmpty()) {
                drawLyricsLayer(
                    canvas = canvas,
                    width = width,
                    height = height,
                    config = config,
                    lines = lines,
                    activeIdx = activeIdx,
                    posMs = effectivePosMs,
                    typeface = typeface,
                    outlinePassOnly = true,
                    beatPulse = beatPulse
                )
            }
        }

        if (config.showSongBadge && config.track != null) {
            drawSongBadge(
                canvas = canvas,
                width = width,
                height = height,
                title = config.track.title,
                artist = config.track.artist,
                albumArt = albumArtBitmap,
                accentColor = config.accentColor,
                font = resolveTypeface(context, config.font)
            )
        }

        // Vista previa en rosa translúcido de la máscara al editar el contorno
        if (showMaskOverlay && maskOverlayBitmap != null && !maskOverlayBitmap.isRecycled) {
            val dstRect = computeCenterCropRect(
                maskOverlayBitmap.width.toFloat(),
                maskOverlayBitmap.height.toFloat(),
                width,
                height
            )
            val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(Color.argb(125, 255, 92, 138), PorterDuff.Mode.SRC_IN)
            }
            canvas.drawBitmap(maskOverlayBitmap, null, dstRect, overlayPaint)
        }
    }

    private fun createChalkStampBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val half = size / 2f

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = (x - half) / half
                val dy = (y - half) / half
                val dist = hypot(dx, dy)
                if (dist >= 1.0f) {
                    pixels[y * size + x] = Color.TRANSPARENT
                    continue
                }

                // Ruido multi-octava determinista para simular poros de papel y grano seco de tiza
                val n1 = valueNoise2D(x * 0.24f, y * 0.24f)
                val n2 = valueNoise2D(x * 0.55f + 17f, y * 0.55f + 31f)
                val striation = valueNoise2D(x * 0.14f + 5f, y * 0.85f + 11f)
                val fineGrain = deterministicHash(x, y, 73)

                // Borde orgánico irregular + caída suave radial
                val edgeWarp = (n1 - 0.5f) * 0.26f
                val radial = (1f - (dist + edgeWarp).coerceIn(0f, 1f))
                val radialFalloff = radial * radial

                val texture = n1 * 0.42f + n2 * 0.28f + striation * 0.18f + fineGrain * 0.12f
                // Umbral poroso: deja huecos secos reales propios de la tiza
                val density = ((texture - 0.28f) / 0.55f).coerceIn(0f, 1f)
                val alpha = (radialFalloff * density * 255f).toInt().coerceIn(0, 255)
                pixels[y * size + x] = Color.argb(alpha, 255, 255, 255)
            }
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
    }

    private fun valueNoise2D(x: Float, y: Float): Float {
        val xi = kotlin.math.floor(x.toDouble()).toInt()
        val yi = kotlin.math.floor(y.toDouble()).toInt()
        val xf = x - xi
        val yf = y - yi

        val u = xf * xf * (3f - 2f * xf)
        val v = yf * yf * (3f - 2f * yf)

        val aa = deterministicHash(xi, yi, 11)
        val ba = deterministicHash(xi + 1, yi, 11)
        val ab = deterministicHash(xi, yi + 1, 11)
        val bb = deterministicHash(xi + 1, yi + 1, 11)

        val x1 = aa + u * (ba - aa)
        val x2 = ab + u * (bb - ab)
        return (x1 + v * (x2 - x1)).coerceIn(0f, 1f)
    }

    private fun drawStrokes(
        canvas: Canvas,
        width: Int,
        height: Int,
        strokes: List<DrawingStroke>
    ) {
        strokes.forEachIndexed { strokeIdx, stroke ->
            val pts = stroke.points
            if (pts.isEmpty()) return@forEachIndexed
            val strokePx = (stroke.widthNorm * width).coerceAtLeast(3f)

            when (stroke.style) {
                BrushStyle.CHALK -> drawChalkStroke(canvas, width, height, strokeIdx, stroke, strokePx)
                BrushStyle.NEON -> drawNeonStroke(canvas, width, height, stroke, strokePx)
                BrushStyle.MARKER -> drawMarkerStroke(canvas, width, height, stroke, strokePx)
                BrushStyle.ARROW -> drawArrowStroke(canvas, width, height, stroke, strokePx)
            }
        }
    }

    private fun drawChalkStroke(
        canvas: Canvas,
        width: Int,
        height: Int,
        strokeIdx: Int,
        stroke: DrawingStroke,
        strokePx: Float
    ) {
        val pts = stroke.points
        val stamp = chalkStampBitmap
        val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(stroke.color, PorterDuff.Mode.SRC_IN)
        }

        val stepPx = max(2.2f, strokePx * 0.14f)
        val baseRadius = strokePx * 0.62f
        val dstRect = RectF()
        var stepCounter = 0

        for (i in 0 until pts.size) {
            val x1 = pts[i].x * width
            val y1 = pts[i].y * height
            val x2 = if (i + 1 < pts.size) pts[i + 1].x * width else x1
            val y2 = if (i + 1 < pts.size) pts[i + 1].y * height else y1
            val dist = hypot(x2 - x1, y2 - y1)
            val angleDeg = Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()
            val steps = max(1, (dist / stepPx).toInt())

            for (s in 0..steps) {
                val t = if (steps == 0) 0f else s.toFloat() / steps
                val cx = x1 + (x2 - x1) * t
                val cy = y1 + (y2 - y1) * t
                stepCounter++

                val hScale = deterministicHash(strokeIdx, stepCounter, 1)
                val hRot = deterministicHash(strokeIdx, stepCounter, 2)
                val hAlpha = deterministicHash(strokeIdx, stepCounter, 3)

                val radius = baseRadius * (0.92f + 0.34f * hScale)
                val rot = angleDeg + (hRot - 0.5f) * 32f
                stampPaint.alpha = (145 + (80 * hAlpha)).toInt().coerceIn(120, 235)

                dstRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
                canvas.save()
                canvas.rotate(rot, cx, cy)
                canvas.drawBitmap(stamp, null, dstRect, stampPaint)
                canvas.restore()
            }
        }
    }

    private fun drawNeonStroke(
        canvas: Canvas,
        width: Int,
        height: Int,
        stroke: DrawingStroke,
        strokePx: Float
    ) {
        val path = buildSmoothPath(stroke.points, width, height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        paint.color = ColorUtils.setAlphaComponent(stroke.color, 48)
        paint.strokeWidth = strokePx * 2.5f
        canvas.drawPath(path, paint)

        paint.color = ColorUtils.setAlphaComponent(stroke.color, 130)
        paint.strokeWidth = strokePx * 1.5f
        canvas.drawPath(path, paint)

        paint.color = ColorUtils.setAlphaComponent(Color.WHITE, 250)
        paint.strokeWidth = strokePx * 0.58f
        canvas.drawPath(path, paint)
    }

    private fun drawMarkerStroke(
        canvas: Canvas,
        width: Int,
        height: Int,
        stroke: DrawingStroke,
        strokePx: Float
    ) {
        val path = buildSmoothPath(stroke.points, width, height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = strokePx
            color = ColorUtils.setAlphaComponent(stroke.color, 220)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawArrowStroke(
        canvas: Canvas,
        width: Int,
        height: Int,
        stroke: DrawingStroke,
        strokePx: Float
    ) {
        val pts = stroke.points
        val path = buildSmoothPath(pts, width, height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = strokePx
            color = ColorUtils.setAlphaComponent(stroke.color, 235)
        }
        canvas.drawPath(path, paint)

        if (pts.size >= 2) {
            val endX = pts.last().x * width
            val endY = pts.last().y * height
            var refIdx = (pts.size - 4).coerceAtLeast(0)
            while (refIdx < pts.size - 1 &&
                hypot((endX - pts[refIdx].x * width), (endY - pts[refIdx].y * height)) < strokePx * 1.2f
            ) {
                refIdx++
            }
            val startX = pts[(refIdx - 1).coerceAtLeast(0)].x * width
            val startY = pts[(refIdx - 1).coerceAtLeast(0)].y * height
            val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
            val headLen = max(strokePx * 2.6f, 22f)
            val wingAngle = Math.toRadians(28.0)

            val leftX = endX - headLen * cos(angle - wingAngle).toFloat()
            val leftY = endY - headLen * sin(angle - wingAngle).toFloat()
            val rightX = endX - headLen * cos(angle + wingAngle).toFloat()
            val rightY = endY - headLen * sin(angle + wingAngle).toFloat()

            val headPath = Path().apply {
                moveTo(leftX, leftY)
                lineTo(endX, endY)
                lineTo(rightX, rightY)
            }
            canvas.drawPath(headPath, paint)
        }
    }

    private fun buildSmoothPath(points: List<DrawingPoint>, width: Int, height: Int): Path {
        val path = Path()
        if (points.isEmpty()) return path
        val firstX = points[0].x * width
        val firstY = points[0].y * height
        path.moveTo(firstX, firstY)
        if (points.size == 1) {
            path.lineTo(firstX + 0.5f, firstY + 0.5f)
            return path
        }
        for (i in 1 until points.size) {
            val prevX = points[i - 1].x * width
            val prevY = points[i - 1].y * height
            val curX = points[i].x * width
            val curY = points[i].y * height
            val midX = (prevX + curX) / 2f
            val midY = (prevY + curY) / 2f
            path.quadTo(prevX, prevY, midX, midY)
        }
        val last = points.last()
        path.lineTo(last.x * width, last.y * height)
        return path
    }

    private fun deterministicHash(a: Int, b: Int, c: Int): Float {
        var x = a * 374761393 + b * 668265263 + c * 2147483647
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return ((x and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()).coerceIn(0f, 1f)
    }

    private fun drawCenterGuides(canvas: Canvas, width: Int, height: Int, config: StoryConfig) {
        val nearX = abs(config.lyricPosX - 0.5f) < 0.025f
        val nearY = abs(config.lyricPosY - 0.55f) < 0.025f
        if (!nearX && !nearY) return

        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(config.accentColor, 165)
            strokeWidth = max(1.5f, width * 0.0025f)
        }
        if (nearX) {
            canvas.drawLine(width * 0.5f, 0f, width * 0.5f, height.toFloat(), guidePaint)
        }
        if (nearY) {
            canvas.drawLine(0f, height * 0.55f, width.toFloat(), height * 0.55f, guidePaint)
        }
    }

    internal fun computeCenterCropRect(srcW: Float, srcH: Float, dstW: Int, dstH: Int): RectF {
        val scale = max(dstW / srcW, dstH / srcH)
        val scaledW = srcW * scale
        val scaledH = srcH * scale
        val left = (dstW - scaledW) / 2f
        val top = (dstH - scaledH) / 2f
        return RectF(left, top, left + scaledW, top + scaledH)
    }

    private fun drawBackground(
        canvas: Canvas,
        width: Int,
        height: Int,
        photoBitmap: Bitmap?,
        dim: Float,
        photoFilter: PhotoFilter
    ) {
        if (photoBitmap != null && !photoBitmap.isRecycled) {
            val dstRect = computeCenterCropRect(photoBitmap.width.toFloat(), photoBitmap.height.toFloat(), width, height)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = colorFilterFor(photoFilter)
            }
            canvas.drawBitmap(photoBitmap, null, dstRect, paint)
        } else {
            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(0xFF141124.toInt(), 0xFF231336.toInt(), 0xFF0A0A0F.toInt()),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }

        val dimAlpha = (dim.coerceIn(0f, 0.85f) * 255).toInt()
        if (dimAlpha > 0) {
            canvas.drawColor(Color.argb(dimAlpha, 0, 0, 0))
        }

        val vignettePaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(
                    Color.argb(115, 0, 0, 0),
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    Color.argb(145, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.22f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)
    }

    private fun colorFilterFor(filter: PhotoFilter): ColorMatrixColorFilter? =
        colorMatrixFor(filter)?.let { ColorMatrixColorFilter(it) }

    private fun colorFilterForForeground(filter: PhotoFilter, dim: Float): ColorMatrixColorFilter? {
        val clampedDim = dim.coerceIn(0f, 0.85f)
        val base = colorMatrixFor(filter)
        if (base == null && clampedDim <= 0.001f) return null
        val result = base ?: ColorMatrix()
        if (clampedDim > 0.001f) {
            val scale = 1f - clampedDim
            val dimMatrix = ColorMatrix().apply { setScale(scale, scale, scale, 1f) }
            result.postConcat(dimMatrix)
        }
        return ColorMatrixColorFilter(result)
    }

    private fun colorMatrixFor(filter: PhotoFilter): ColorMatrix? = when (filter) {
        PhotoFilter.NONE -> null
        PhotoFilter.WARM -> ColorMatrix(
            floatArrayOf(
                1.12f, 0f, 0f, 0f, 10f,
                0f, 1.02f, 0f, 0f, 2f,
                0f, 0f, 0.86f, 0f, -6f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        PhotoFilter.COOL -> ColorMatrix(
            floatArrayOf(
                0.88f, 0f, 0f, 0f, -4f,
                0f, 1.02f, 0f, 0f, 4f,
                0f, 0f, 1.16f, 0f, 12f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        PhotoFilter.BW -> ColorMatrix().apply { setSaturation(0f) }
        PhotoFilter.VINTAGE -> ColorMatrix(
            floatArrayOf(
                0.96f, 0.16f, 0.06f, 0f, 12f,
                0.06f, 0.92f, 0.08f, 0f, 8f,
                0.02f, 0.08f, 0.74f, 0f, 14f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        PhotoFilter.MOODY -> ColorMatrix(
            floatArrayOf(
                0.82f, 0f, 0f, 0f, -12f,
                0f, 0.84f, 0f, 0f, -10f,
                0f, 0f, 0.96f, 0f, 4f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    private fun drawSongBadge(
        canvas: Canvas,
        width: Int,
        height: Int,
        title: String,
        artist: String,
        albumArt: Bitmap?,
        accentColor: Int,
        font: Typeface
    ) {
        val marginH = width * 0.055f
        val marginTop = height * 0.055f
        val artSize = width * 0.11f
        val pad = width * 0.024f
        val maxTextW = width * 0.62f

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = width * 0.035f
            typeface = font
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, 195)
            textSize = width * 0.028f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val truncTitle = ellipsize(title, titlePaint, maxTextW)
        val truncArtist = ellipsize(artist, artistPaint, maxTextW)
        val textW = max(titlePaint.measureText(truncTitle), artistPaint.measureText(truncArtist))
        val pillW = min(width - marginH * 2f, pad + artSize + pad * 0.8f + textW + pad * 1.3f)
        val pillH = artSize + pad * 1.4f
        val rect = RectF(marginH, marginTop, marginH + pillW, marginTop + pillH)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 16, 16, 24)
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, width * 0.0022f)
            color = ColorUtils.setAlphaComponent(accentColor, 150)
        }
        val radius = pillH * 0.34f
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val artLeft = rect.left + pad * 0.7f
        val artTop = rect.top + (pillH - artSize) / 2f
        val artRect = RectF(artLeft, artTop, artLeft + artSize, artTop + artSize)
        val artCorner = artSize * 0.22f

        canvas.save()
        val clipPath = Path().apply {
            addRoundRect(artRect, artCorner, artCorner, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)
        if (albumArt != null && !albumArt.isRecycled) {
            canvas.drawBitmap(albumArt, null, artRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            canvas.drawRect(artRect, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor })
        }
        canvas.restore()

        val textX = artRect.right + pad * 0.8f
        val centerY = rect.centerY()
        canvas.drawText(truncTitle, textX, centerY - titlePaint.textSize * 0.14f, titlePaint)
        canvas.drawText(truncArtist, textX, centerY + artistPaint.textSize * 1.05f, artistPaint)
    }

    private fun computeAnimation(
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        currentIdx: Int,
        posMs: Long
    ): AnimatedLineFrame {
        val currentLine = lines[currentIdx]
        val nextTime = lines.getOrNull(currentIdx + 1)?.timeMs ?: (currentLine.timeMs + 3_000L)
        val lineDuration = (nextTime - currentLine.timeMs).coerceIn(600L, 4_500L)
        val elapsed = (posMs - currentLine.timeMs).coerceAtLeast(0L)

        return when (config.animation) {
            LyricAnimation.FADE_UP -> {
                val t = (elapsed / 280f).coerceIn(0f, 1f)
                val eased = easeOutCubic(t)
                AnimatedLineFrame(
                    text = currentLine.text,
                    offsetY = (1f - eased) * (canvasWidth * 0.036f),
                    alpha = (255 * eased).toInt().coerceIn(45, 255),
                    glowColor = Color.argb(180, 0, 0, 0),
                    glowRadius = canvasWidth * 0.015f
                )
            }
            LyricAnimation.POP_BOUNCE -> {
                val t = (elapsed / 320f).coerceIn(0f, 1f)
                val eased = easeOutBack(t)
                val fade = easeOutCubic((elapsed / 180f).coerceIn(0f, 1f))
                AnimatedLineFrame(
                    text = currentLine.text,
                    scale = 0.78f + 0.22f * eased,
                    alpha = (255 * fade).toInt().coerceIn(50, 255),
                    glowColor = Color.argb(180, 0, 0, 0),
                    glowRadius = canvasWidth * 0.016f
                )
            }
            LyricAnimation.TYPEWRITER -> {
                val revealDuration = (lineDuration * 0.74f).coerceAtLeast(350f)
                val progress = (elapsed / revealDuration).coerceIn(0f, 1f)
                val fullText = currentLine.text
                val charCount = (fullText.length * progress).toInt().coerceIn(1, max(1, fullText.length))
                val showCursor = (posMs / 360L) % 2L == 0L && progress < 1f
                val visible = fullText.take(charCount) + if (showCursor) "|" else ""
                AnimatedLineFrame(
                    text = visible,
                    glowColor = Color.argb(180, 0, 0, 0),
                    glowRadius = canvasWidth * 0.015f
                )
            }
            LyricAnimation.WORD_BY_WORD -> {
                val words = currentLine.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val visibleText = if (words.isEmpty()) {
                    currentLine.text
                } else {
                    val revealDuration = (lineDuration * 0.76f).coerceAtLeast(400f)
                    val progress = (elapsed / revealDuration).coerceIn(0f, 1f)
                    val count = (words.size * progress).toInt().coerceIn(1, words.size)
                    words.take(count).joinToString(" ")
                }
                AnimatedLineFrame(
                    text = visibleText,
                    glowColor = Color.argb(180, 0, 0, 0),
                    glowRadius = canvasWidth * 0.015f
                )
            }
            LyricAnimation.KARAOKE -> {
                val raw = currentLine.text
                val progress = (elapsed / (lineDuration * 0.85f)).coerceIn(0f, 1f)
                val highlightEnd = findWordBoundaryIndex(raw, progress)
                val spannable = SpannableString(raw)
                if (highlightEnd > 0) {
                    spannable.setSpan(
                        ForegroundColorSpan(config.accentColor),
                        0,
                        highlightEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (highlightEnd < raw.length) {
                    spannable.setSpan(
                        ForegroundColorSpan(ColorUtils.setAlphaComponent(config.textColor, 180)),
                        highlightEnd,
                        raw.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                AnimatedLineFrame(
                    text = spannable,
                    glowColor = Color.argb(170, 0, 0, 0),
                    glowRadius = canvasWidth * 0.014f
                )
            }
            LyricAnimation.GLOW_PULSE -> {
                val pulse = ((sin(posMs / 240.0) + 1.0) * 0.5).toFloat()
                val glowAlpha = (140 + 115 * pulse).toInt().coerceIn(0, 255)
                AnimatedLineFrame(
                    text = currentLine.text,
                    scale = 1.0f + 0.025f * pulse,
                    glowColor = ColorUtils.setAlphaComponent(config.accentColor, glowAlpha),
                    glowRadius = canvasWidth * (0.022f + 0.016f * pulse)
                )
            }
        }
    }

    private fun drawLyricsLayer(
        canvas: Canvas,
        width: Int,
        height: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int,
        posMs: Long,
        typeface: Typeface,
        outlinePassOnly: Boolean,
        beatPulse: Float = 0f
    ) {
        val pulseFactor = if (config.beatPulseEnabled) beatPulse.coerceIn(0f, 1f) else 0f
        val effectiveScale = config.lyricScale * (1f + pulseFactor * 0.14f)
        canvas.save()
        canvas.translate(width * config.lyricPosX, height * config.lyricPosY)
        canvas.rotate(config.lyricRotationDeg)
        canvas.scale(effectiveScale, effectiveScale)

        when (config.style) {
            LyricStyle.SCROLL -> drawScrollStyle(canvas, width, config, lines, activeIdx, posMs, typeface, outlinePassOnly)
            LyricStyle.FOCUS -> drawFocusStyle(canvas, width, config, lines, activeIdx, posMs, typeface, outlinePassOnly)
            LyricStyle.CARD -> drawCardStyle(canvas, width, config, lines, activeIdx, posMs, typeface, outlinePassOnly)
            LyricStyle.HIGHLIGHT -> drawHighlightStyle(canvas, width, config, lines, activeIdx, posMs, typeface, outlinePassOnly)
            LyricStyle.CINEMA -> drawCinemaStyle(canvas, width, config, lines, activeIdx, posMs, typeface, outlinePassOnly)
            LyricStyle.STACK -> drawStackStyle(canvas, width, config, lines, activeIdx, posMs, typeface, outlinePassOnly)
            LyricStyle.POSTER -> drawPosterStyle(canvas, width, config, lines, activeIdx, posMs, typeface, outlinePassOnly)
        }

        canvas.restore()
    }

    private fun drawStyledLayout(
        canvas: Canvas,
        text: CharSequence,
        paint: TextPaint,
        maxWidth: Int,
        config: StoryConfig,
        posMs: Long,
        alpha: Int,
        outlinePassOnly: Boolean = false,
        lineSpacingMult: Float = 1.15f
    ) {
        val alignment = layoutAlignmentFor(config.textAlignment)
        val safeAlpha = alpha.coerceIn(0, 255)
        if (outlinePassOnly) {
            val hollowPaint = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeWidth = max(2f, paint.textSize * 0.045f)
                color = ColorUtils.setAlphaComponent(config.textColor, (safeAlpha * 0.92f).toInt().coerceIn(40, 255))
                clearShadowLayer()
                shader = null
            }
            buildLayout(text, hollowPaint, maxWidth, alignment, lineSpacingMult).draw(canvas)
            return
        }

        when (config.effect) {
            LyricEffect.NONE -> {
                buildLayout(text, paint, maxWidth, alignment, lineSpacingMult).draw(canvas)
            }
            LyricEffect.HOLLOW_OVERLAP -> {
                buildLayout(text, paint, maxWidth, alignment, lineSpacingMult).draw(canvas)
                if (!config.lyricsBehindSubject) {
                    val accentOutline = TextPaint(paint).apply {
                        style = Paint.Style.STROKE
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = max(1.8f, paint.textSize * 0.035f)
                        color = ColorUtils.setAlphaComponent(config.accentColor, (safeAlpha * 0.75f).toInt())
                        clearShadowLayer()
                    }
                    buildLayout(text, accentOutline, maxWidth, alignment, lineSpacingMult).draw(canvas)
                }
            }
            LyricEffect.OUTLINE -> {
                val lum = ColorUtils.calculateLuminance(paint.color)
                val strokePaint = TextPaint(paint).apply {
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = paint.textSize * 0.13f
                    color = ColorUtils.setAlphaComponent(if (lum > 0.45) Color.BLACK else Color.WHITE, safeAlpha)
                    clearShadowLayer()
                }
                buildLayout(text, strokePaint, maxWidth, alignment, lineSpacingMult).draw(canvas)
                buildLayout(text, paint, maxWidth, alignment, lineSpacingMult).draw(canvas)
            }
            LyricEffect.NEON -> {
                val outerPaint = TextPaint(paint).apply {
                    setShadowLayer(paint.textSize * 0.45f, 0f, 0f, ColorUtils.setAlphaComponent(config.accentColor, safeAlpha))
                }
                val innerPaint = TextPaint(paint).apply {
                    setShadowLayer(paint.textSize * 0.18f, 0f, 0f, ColorUtils.setAlphaComponent(config.accentColor, safeAlpha))
                }
                buildLayout(text, outerPaint, maxWidth, alignment, lineSpacingMult).draw(canvas)
                buildLayout(text, innerPaint, maxWidth, alignment, lineSpacingMult).draw(canvas)
            }
            LyricEffect.GLITCH -> {
                val shift = paint.textSize * 0.055f * sin(posMs / 85.0).toFloat()
                val shiftY = paint.textSize * 0.022f * cos(posMs / 60.0).toFloat()
                val cyanPaint = TextPaint(paint).apply {
                    color = ColorUtils.setAlphaComponent(0xFF00F0FF.toInt(), (safeAlpha * 0.78f).toInt())
                    clearShadowLayer()
                }
                val magentaPaint = TextPaint(paint).apply {
                    color = ColorUtils.setAlphaComponent(0xFFFF0055.toInt(), (safeAlpha * 0.78f).toInt())
                    clearShadowLayer()
                }
                canvas.save()
                canvas.translate(-shift, shiftY)
                buildLayout(text, cyanPaint, maxWidth, alignment, lineSpacingMult).draw(canvas)
                canvas.restore()

                canvas.save()
                canvas.translate(shift, -shiftY)
                buildLayout(text, magentaPaint, maxWidth, alignment, lineSpacingMult).draw(canvas)
                canvas.restore()

                buildLayout(text, paint, maxWidth, alignment, lineSpacingMult).draw(canvas)
            }
            LyricEffect.GRADIENT -> {
                val gradPaint = TextPaint(paint)
                if (config.animation != LyricAnimation.KARAOKE) {
                    gradPaint.shader = LinearGradient(
                        0f, 0f, maxWidth.toFloat(), 0f,
                        intArrayOf(
                            ColorUtils.setAlphaComponent(config.textColor, safeAlpha),
                            ColorUtils.setAlphaComponent(config.accentColor, safeAlpha),
                            ColorUtils.setAlphaComponent(0xFFFFD166.toInt(), safeAlpha)
                        ),
                        null,
                        Shader.TileMode.CLAMP
                    )
                }
                buildLayout(text, gradPaint, maxWidth, alignment, lineSpacingMult).draw(canvas)
            }
            LyricEffect.SHADOW_3D -> {
                val depthColor = ColorUtils.blendARGB(config.accentColor, Color.BLACK, 0.35f)
                val step = max(1.5f, paint.textSize * 0.028f)
                val shadowPaint = TextPaint(paint).apply {
                    color = ColorUtils.setAlphaComponent(depthColor, (safeAlpha * 0.85f).toInt())
                    clearShadowLayer()
                }
                val layout3d = buildLayout(text, shadowPaint, maxWidth, alignment, lineSpacingMult)
                for (layer in 5 downTo 1) {
                    canvas.save()
                    canvas.translate(step * layer, step * layer)
                    layout3d.draw(canvas)
                    canvas.restore()
                }
                buildLayout(text, paint, maxWidth, alignment, lineSpacingMult).draw(canvas)
            }
        }
    }

    private fun findWordBoundaryIndex(text: String, progress: Float): Int {
        if (text.isEmpty() || progress <= 0f) return 0
        if (progress >= 1f) return text.length
        val approx = (text.length * progress).toInt().coerceIn(0, text.length)
        var idx = approx
        while (idx < text.length && !text[idx].isWhitespace()) {
            idx++
        }
        return idx.coerceIn(0, text.length)
    }

    private fun drawScrollStyle(
        canvas: Canvas,
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int,
        posMs: Long,
        typeface: Typeface,
        outlinePassOnly: Boolean = false
    ) {
        val maxWidth = (canvasWidth * 0.82f).toInt().coerceAtLeast(100)
        val currentIdx = activeIdx.coerceAtLeast(0)
        val anim = computeAnimation(canvasWidth, config, lines, currentIdx, posMs)
        val prevLine = if (outlinePassOnly) null else lines.getOrNull(currentIdx - 1)
        val nextLine = if (outlinePassOnly) null else lines.getOrNull(currentIdx + 1)
        val align = layoutAlignmentFor(config.textAlignment)

        val activePaint = createTextPaint(
            color = ColorUtils.setAlphaComponent(config.textColor, anim.alpha),
            textSize = canvasWidth * 0.058f,
            typeface = typeface,
            shadowRadius = anim.glowRadius,
            shadowColor = anim.glowColor
        )
        val sidePaint = createTextPaint(
            color = ColorUtils.setAlphaComponent(config.textColor, 125),
            textSize = canvasWidth * 0.041f,
            typeface = typeface,
            shadowRadius = canvasWidth * 0.01f,
            shadowColor = Color.argb(150, 0, 0, 0)
        )

        val activeLayout = buildLayout(anim.text, activePaint, maxWidth, align)
        val prevLayout = prevLine?.let { buildLayout(it.text, sidePaint, maxWidth, align) }
        val nextLayout = nextLine?.let { buildLayout(it.text, sidePaint, maxWidth, align) }

        val gap = canvasWidth * 0.034f
        val activeTop = -activeLayout.height / 2f + anim.offsetY

        prevLayout?.let {
            canvas.save()
            canvas.translate(-maxWidth / 2f, activeTop - gap - it.height)
            it.draw(canvas)
            canvas.restore()
        }

        canvas.save()
        canvas.scale(anim.scale, anim.scale)
        canvas.translate(-maxWidth / 2f, activeTop)
        drawStyledLayout(canvas, anim.text, activePaint, maxWidth, config, posMs, anim.alpha, outlinePassOnly)
        canvas.restore()

        nextLayout?.let {
            canvas.save()
            canvas.translate(-maxWidth / 2f, activeTop + activeLayout.height + gap)
            it.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawFocusStyle(
        canvas: Canvas,
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int,
        posMs: Long,
        typeface: Typeface,
        outlinePassOnly: Boolean = false
    ) {
        val maxWidth = (canvasWidth * 0.84f).toInt().coerceAtLeast(100)
        val currentIdx = activeIdx.coerceAtLeast(0)
        val anim = computeAnimation(canvasWidth, config, lines, currentIdx, posMs)

        val paint = createTextPaint(
            color = ColorUtils.setAlphaComponent(config.textColor, anim.alpha),
            textSize = canvasWidth * 0.068f,
            typeface = typeface,
            shadowRadius = anim.glowRadius,
            shadowColor = anim.glowColor
        )
        val layout = buildLayout(anim.text, paint, maxWidth, layoutAlignmentFor(config.textAlignment))

        canvas.save()
        canvas.translate(0f, anim.offsetY)
        canvas.scale(anim.scale, anim.scale)
        canvas.translate(-maxWidth / 2f, -layout.height / 2f)
        drawStyledLayout(canvas, anim.text, paint, maxWidth, config, posMs, anim.alpha, outlinePassOnly)
        canvas.restore()
    }

    private fun drawCardStyle(
        canvas: Canvas,
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int,
        posMs: Long,
        typeface: Typeface,
        outlinePassOnly: Boolean = false
    ) {
        val maxTextW = (canvasWidth * 0.74f).toInt().coerceAtLeast(100)
        val currentIdx = activeIdx.coerceAtLeast(0)
        val anim = computeAnimation(canvasWidth, config, lines, currentIdx, posMs)
        val nextLine = lines.getOrNull(currentIdx + 1)
        val align = layoutAlignmentFor(config.textAlignment)

        val mainPaint = createTextPaint(
            color = ColorUtils.setAlphaComponent(config.textColor, anim.alpha),
            textSize = canvasWidth * 0.052f,
            typeface = typeface,
            shadowRadius = if (config.animation == LyricAnimation.GLOW_PULSE) anim.glowRadius else 0f,
            shadowColor = anim.glowColor
        )
        val mainLayout = buildLayout(anim.text, mainPaint, maxTextW, align)

        val nextPaint = createTextPaint(
            color = ColorUtils.setAlphaComponent(config.textColor, 160),
            textSize = canvasWidth * 0.036f,
            typeface = typeface,
            shadowRadius = 0f,
            shadowColor = Color.TRANSPARENT
        )
        val nextLayout = nextLine?.let { buildLayout(it.text, nextPaint, maxTextW, align) }

        val padH = canvasWidth * 0.06f
        val padV = canvasWidth * 0.048f
        val gap = if (nextLayout != null) canvasWidth * 0.022f else 0f
        val contentH = mainLayout.height + gap + (nextLayout?.height ?: 0)
        val cardW = maxTextW + padH * 2f
        val cardH = contentH + padV * 2f

        val cardRect = RectF(-cardW / 2f, -cardH / 2f, cardW / 2f, cardH / 2f)

        canvas.save()
        canvas.translate(0f, anim.offsetY)
        canvas.scale(anim.scale, anim.scale)
        if (!outlinePassOnly) {
            val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 16, 16, 26) }
            val cardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(2f, canvasWidth * 0.0032f)
                color = ColorUtils.setAlphaComponent(config.accentColor, 185)
            }
            val corner = canvasWidth * 0.048f
            canvas.drawRoundRect(cardRect, corner, corner, cardBg)
            canvas.drawRoundRect(cardRect, corner, corner, cardBorder)
        }

        canvas.save()
        canvas.translate(-maxTextW / 2f, cardRect.top + padV)
        drawStyledLayout(canvas, anim.text, mainPaint, maxTextW, config, posMs, anim.alpha, outlinePassOnly)
        if (!outlinePassOnly && nextLayout != null) {
            canvas.translate(0f, mainLayout.height + gap)
            nextLayout.draw(canvas)
        }
        canvas.restore()
        canvas.restore()
    }

    private fun drawHighlightStyle(
        canvas: Canvas,
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int,
        posMs: Long,
        typeface: Typeface,
        outlinePassOnly: Boolean = false
    ) {
        val maxTextW = (canvasWidth * 0.76f).toInt().coerceAtLeast(100)
        val currentIdx = activeIdx.coerceAtLeast(0)
        val anim = computeAnimation(canvasWidth, config, lines, currentIdx, posMs)

        val paint = createTextPaint(
            color = ColorUtils.setAlphaComponent(config.textColor, anim.alpha),
            textSize = canvasWidth * 0.054f,
            typeface = typeface,
            shadowRadius = 0f,
            shadowColor = Color.TRANSPARENT
        )
        val layout = buildLayout(anim.text, paint, maxTextW, layoutAlignmentFor(config.textAlignment))
        val padH = canvasWidth * 0.055f
        val padV = canvasWidth * 0.034f
        val boxW = maxTextW + padH * 2f
        val boxH = layout.height + padV * 2f
        val boxRect = RectF(-boxW / 2f, -boxH / 2f, boxW / 2f, boxH / 2f)

        canvas.save()
        canvas.translate(0f, anim.offsetY)
        canvas.scale(anim.scale, anim.scale)
        if (!outlinePassOnly) {
            val hasContrast = ColorUtils.calculateContrast(config.textColor, config.accentColor) >= 2.2
            val baseBoxColor = when {
                config.animation == LyricAnimation.KARAOKE -> Color.rgb(16, 16, 24)
                hasContrast -> config.accentColor
                ColorUtils.calculateLuminance(config.textColor) > 0.45 -> Color.rgb(16, 16, 24)
                else -> Color.rgb(250, 250, 252)
            }
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(baseBoxColor, (anim.alpha * 0.92f).toInt().coerceIn(60, 240))
            }
            val radius = canvasWidth * 0.036f
            canvas.drawRoundRect(boxRect, radius, radius, boxPaint)
        }
        canvas.translate(-maxTextW / 2f, -layout.height / 2f)
        drawStyledLayout(canvas, anim.text, paint, maxTextW, config, posMs, anim.alpha, outlinePassOnly)
        canvas.restore()
    }

    private fun drawCinemaStyle(
        canvas: Canvas,
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int,
        posMs: Long,
        typeface: Typeface,
        outlinePassOnly: Boolean = false
    ) {
        val maxWidth = (canvasWidth * 0.84f).toInt().coerceAtLeast(100)
        val currentIdx = activeIdx.coerceAtLeast(0)
        val anim = computeAnimation(canvasWidth, config, lines, currentIdx, posMs)

        val paint = createTextPaint(
            color = ColorUtils.setAlphaComponent(config.textColor, anim.alpha),
            textSize = canvasWidth * 0.056f,
            typeface = typeface,
            shadowRadius = anim.glowRadius,
            shadowColor = anim.glowColor
        )
        val layout = buildLayout(anim.text, paint, maxWidth, layoutAlignmentFor(config.textAlignment))

        canvas.save()
        canvas.translate(0f, anim.offsetY)
        canvas.scale(anim.scale, anim.scale)
        canvas.translate(-maxWidth / 2f, -layout.height / 2f)
        drawStyledLayout(canvas, anim.text, paint, maxWidth, config, posMs, anim.alpha, outlinePassOnly)

        if (!outlinePassOnly) {
            val barW = canvasWidth * 0.18f
            val barH = max(4f, canvasWidth * 0.007f)
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(config.accentColor, anim.alpha)
            }
            val barLeft = when (config.textAlignment) {
                LyricAlignment.LEFT -> 0f
                LyricAlignment.CENTER -> (maxWidth - barW) / 2f
                LyricAlignment.RIGHT -> maxWidth - barW
            }
            val barTop = layout.height + canvasWidth * 0.024f
            canvas.drawRoundRect(
                RectF(barLeft, barTop, barLeft + barW, barTop + barH),
                barH / 2f,
                barH / 2f,
                barPaint
            )
        }
        canvas.restore()
    }

    /**
     * Estilo Apilado tipo Instagram Stories: los versos se acumulan uno encima de otro
     * y se empujan suavemente hacia arriba conforme avanza la canción.
     */
    private fun drawStackStyle(
        canvas: Canvas,
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int,
        posMs: Long,
        typeface: Typeface,
        outlinePassOnly: Boolean = false
    ) {
        val maxWidth = (canvasWidth * 0.84f).toInt().coerceAtLeast(100)
        val currentIdx = activeIdx.coerceAtLeast(0)
        val currentLine = lines[currentIdx]
        val align = layoutAlignmentFor(config.textAlignment)

        val elapsed = (posMs - currentLine.timeMs).coerceAtLeast(0L)
        val pushProgress = easeOutCubic((elapsed / 320f).coerceIn(0f, 1f))
        val anim = computeAnimation(canvasWidth, config, lines, currentIdx, posMs)

        val activePaint = createTextPaint(
            color = ColorUtils.setAlphaComponent(config.textColor, anim.alpha),
            textSize = canvasWidth * 0.058f,
            typeface = typeface,
            shadowRadius = anim.glowRadius,
            shadowColor = anim.glowColor
        )
        val activeLayout = buildLayout(anim.text, activePaint, maxWidth, align)
        val gap = canvasWidth * 0.026f

        val baseTop = -activeLayout.height / 2f
        val slidePush = (1f - pushProgress) * (activeLayout.height + gap)

        if (!outlinePassOnly) {
            var cursorTop = baseTop + slidePush - gap
            for (age in 1..3) {
                val prevIdx = currentIdx - age
                if (prevIdx < 0) break
                val prevLine = lines[prevIdx]
                val (targetScale, targetAlpha) = when (age) {
                    1 -> 0.93f to 205
                    2 -> 0.87f to 140
                    else -> 0.81f to (85f * (1f - pushProgress * 0.5f)).toInt().coerceIn(20, 85)
                }
                val prevPaint = createTextPaint(
                    color = ColorUtils.setAlphaComponent(config.textColor, targetAlpha),
                    textSize = canvasWidth * 0.056f,
                    typeface = typeface,
                    shadowRadius = canvasWidth * 0.01f,
                    shadowColor = Color.argb(140, 0, 0, 0)
                )
                val prevLayout = buildLayout(prevLine.text, prevPaint, maxWidth, align)
                val scaledH = prevLayout.height * targetScale
                cursorTop -= scaledH

                val pivotX = when (config.textAlignment) {
                    LyricAlignment.LEFT -> -maxWidth / 2f
                    LyricAlignment.CENTER -> 0f
                    LyricAlignment.RIGHT -> maxWidth / 2f
                }
                canvas.save()
                canvas.translate(0f, cursorTop)
                canvas.scale(targetScale, targetScale, pivotX, 0f)
                canvas.translate(-maxWidth / 2f, 0f)
                prevLayout.draw(canvas)
                canvas.restore()

                cursorTop -= gap * 0.9f
            }
        }

        // Verso activo en la base de la pila
        canvas.save()
        canvas.translate(0f, baseTop + anim.offsetY * 0.5f)
        canvas.scale(anim.scale, anim.scale, 0f, activeLayout.height / 2f)
        canvas.translate(-maxWidth / 2f, 0f)
        drawStyledLayout(canvas, anim.text, activePaint, maxWidth, config, posMs, anim.alpha, outlinePassOnly)
        canvas.restore()
    }

    /**
     * Estilo Póster gigante en mayúsculas, diseñado para lucir detrás de la silueta de la persona.
     */
    private fun drawPosterStyle(
        canvas: Canvas,
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int,
        posMs: Long,
        typeface: Typeface,
        outlinePassOnly: Boolean = false
    ) {
        val maxWidth = (canvasWidth * 0.90f).toInt().coerceAtLeast(100)
        val currentIdx = activeIdx.coerceAtLeast(0)
        val anim = computeAnimation(canvasWidth, config, lines, currentIdx, posMs)
        val upperText = toUppercasePreservingSpans(anim.text)

        val paint = createTextPaint(
            color = ColorUtils.setAlphaComponent(config.textColor, anim.alpha),
            textSize = canvasWidth * 0.105f,
            typeface = typeface,
            shadowRadius = anim.glowRadius,
            shadowColor = anim.glowColor
        )
        val layout = buildLayout(upperText, paint, maxWidth, layoutAlignmentFor(config.textAlignment), lineSpacingMult = 0.94f)

        canvas.save()
        canvas.translate(0f, anim.offsetY)
        canvas.scale(anim.scale, anim.scale)
        canvas.translate(-maxWidth / 2f, -layout.height / 2f)
        drawStyledLayout(canvas, upperText, paint, maxWidth, config, posMs, anim.alpha, outlinePassOnly, lineSpacingMult = 0.94f)
        canvas.restore()
    }

    private fun toUppercasePreservingSpans(source: CharSequence): CharSequence {
        val upper = source.toString().uppercase()
        if (source !is Spanned) return upper
        val spannable = SpannableString(upper)
        val spans = source.getSpans(0, source.length, Any::class.java)
        for (span in spans) {
            val start = source.getSpanStart(span).coerceIn(0, upper.length)
            val end = source.getSpanEnd(span).coerceIn(start, upper.length)
            spannable.setSpan(span, start, end, source.getSpanFlags(span))
        }
        return spannable
    }

    private fun drawEmptyHint(
        context: Context,
        canvas: Canvas,
        width: Int,
        height: Int,
        config: StoryConfig
    ) {
        if (config.photoUri == null || config.track == null) return
        val paint = createTextPaint(
            color = ColorUtils.setAlphaComponent(Color.WHITE, 195),
            textSize = width * 0.042f,
            typeface = resolveTypeface(context, config.font),
            shadowRadius = width * 0.01f,
            shadowColor = Color.argb(170, 0, 0, 0)
        )
        val maxW = (width * 0.78f).toInt().coerceAtLeast(100)
        val layout = buildLayout("Sin letra sincronizada\nToca Tramo → Cambiar letra", paint, maxW, Layout.Alignment.ALIGN_CENTER)
        canvas.save()
        canvas.translate((width - maxW) / 2f, height * config.lyricPosY - layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun layoutAlignmentFor(alignment: LyricAlignment): Layout.Alignment = when (alignment) {
        LyricAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
        LyricAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
        LyricAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
    }

    private fun buildLayout(
        text: CharSequence,
        paint: TextPaint,
        maxWidth: Int,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_CENTER,
        lineSpacingMult: Float = 1.15f
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(alignment)
            .setLineSpacing(0f, lineSpacingMult)
            .setIncludePad(false)
            .build()

    private fun createTextPaint(
        color: Int,
        textSize: Float,
        typeface: Typeface,
        shadowRadius: Float,
        shadowColor: Int
    ): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.textSize = textSize
        this.typeface = typeface
        if (shadowRadius > 0f) {
            setShadowLayer(shadowRadius, 0f, shadowRadius * 0.2f, shadowColor)
        }
    }

    fun resolveTypeface(context: Context, font: LyricFont): Typeface {
        typefaceCache[font]?.let { return it }
        val loaded = runCatching { ResourcesCompat.getFont(context, font.fontRes) }.getOrNull()
            ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        typefaceCache[font] = loaded
        return loaded
    }

    private fun ellipsize(text: String, paint: TextPaint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + ellipsis) > maxWidth) {
            end--
        }
        return if (end <= 0) ellipsis else text.substring(0, end) + ellipsis
    }

    private fun easeOutCubic(t: Float): Float {
        val inv = 1f - t.coerceIn(0f, 1f)
        return 1f - inv * inv * inv
    }

    private fun easeOutBack(t: Float): Float {
        val x = t.coerceIn(0f, 1f) - 1f
        val c1 = 1.70158f
        val c3 = c1 + 1f
        return 1f + c3 * x * x * x + c1 * x * x
    }
}
