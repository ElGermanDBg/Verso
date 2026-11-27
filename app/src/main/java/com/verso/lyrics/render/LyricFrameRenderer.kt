package com.verso.lyrics.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import com.verso.lyrics.model.LyricFont
import com.verso.lyrics.model.LyricLine
import com.verso.lyrics.model.LyricStyle
import com.verso.lyrics.model.StoryConfig
import com.verso.lyrics.model.activeLyricIndex
import kotlin.math.max
import kotlin.math.min

/**
 * Renderizador unificado sobre [Canvas] utilizado tanto para la vista previa en tiempo real
 * como para la generación de frames del video MP4 exportado.
 */
object LyricFrameRenderer {

    fun drawFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        positionMs: Long,
        photoBitmap: Bitmap?,
        albumArtBitmap: Bitmap?
    ) {
        if (width <= 0 || height <= 0) return

        drawBackground(canvas, width, height, photoBitmap, config.backgroundDim)

        if (config.showSongBadge && config.track != null) {
            drawSongBadge(
                canvas = canvas,
                width = width,
                height = height,
                title = config.track.title,
                artist = config.track.artist,
                albumArt = albumArtBitmap,
                accentColor = config.accentColor
            )
        }

        if (lines.isEmpty()) {
            drawEmptyHint(canvas, width, height, config)
            return
        }

        val effectivePosMs = (positionMs + config.lyricsOffsetMs).coerceAtLeast(0L)
        val activeIdx = activeLyricIndex(lines, effectivePosMs)

        canvas.save()
        val anchorX = width * config.lyricPosX
        val anchorY = height * config.lyricPosY
        canvas.translate(anchorX, anchorY)
        canvas.rotate(config.lyricRotationDeg)
        canvas.scale(config.lyricScale, config.lyricScale)

        when (config.style) {
            LyricStyle.SCROLL -> drawScrollStyle(canvas, width, config, lines, activeIdx, effectivePosMs)
            LyricStyle.TYPEWRITER -> drawTypewriterStyle(canvas, width, config, lines, activeIdx, effectivePosMs)
            LyricStyle.DYNAMIC -> drawDynamicStyle(canvas, width, config, lines, activeIdx, effectivePosMs)
            LyricStyle.CARD -> drawCardStyle(canvas, width, config, lines, activeIdx)
        }

        canvas.restore()
    }

    private fun drawBackground(
        canvas: Canvas,
        width: Int,
        height: Int,
        photoBitmap: Bitmap?,
        dim: Float
    ) {
        if (photoBitmap != null && !photoBitmap.isRecycled) {
            val srcW = photoBitmap.width.toFloat()
            val srcH = photoBitmap.height.toFloat()
            val scale = max(width / srcW, height / srcH)
            val scaledW = srcW * scale
            val scaledH = srcH * scale
            val left = (width - scaledW) / 2f
            val top = (height - scaledH) / 2f
            val dstRect = RectF(left, top, left + scaledW, top + scaledH)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(photoBitmap, null, dstRect, paint)
        } else {
            val bgPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(0xFF141124.toInt(), 0xFF1E132B.toInt(), 0xFF0A0A0F.toInt()),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }

        // Capa de oscurecimiento configurable
        val dimAlpha = (dim.coerceIn(0f, 0.85f) * 255).toInt()
        if (dimAlpha > 0) {
            canvas.drawColor(Color.argb(dimAlpha, 0, 0, 0))
        }

        // Viñeta vertical sutil para mejorar el contraste de badge y controles
        val vignettePaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(
                    Color.argb(110, 0, 0, 0),
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    Color.argb(135, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.22f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)
    }

    private fun drawSongBadge(
        canvas: Canvas,
        width: Int,
        height: Int,
        title: String,
        artist: String,
        albumArt: Bitmap?,
        accentColor: Int
    ) {
        val marginH = width * 0.055f
        val marginTop = height * 0.055f
        val artSize = width * 0.115f
        val pad = width * 0.025f
        val maxTextW = width * 0.62f

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = width * 0.036f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, 195)
            textSize = width * 0.029f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val truncTitle = ellipsize(title, titlePaint, maxTextW)
        val truncArtist = ellipsize(artist, artistPaint, maxTextW)
        val textW = max(titlePaint.measureText(truncTitle), artistPaint.measureText(truncArtist))
        val pillW = min(width - marginH * 2f, pad + artSize + pad * 0.8f + textW + pad * 1.3f)
        val pillH = artSize + pad * 1.4f
        val rect = RectF(marginH, marginTop, marginH + pillW, marginTop + pillH)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(145, 16, 16, 24)
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, width * 0.0022f)
            color = ColorUtils.setAlphaComponent(accentColor, 140)
        }
        val radius = pillH * 0.34f
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        // Carátula mini redondeada
        val artLeft = rect.left + pad * 0.7f
        val artTop = rect.top + (pillH - artSize) / 2f
        val artRect = RectF(artLeft, artTop, artLeft + artSize, artTop + artSize)
        val artCorner = artSize * 0.22f

        canvas.save()
        val clipPath = android.graphics.Path().apply {
            addRoundRect(artRect, artCorner, artCorner, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(clipPath)
        if (albumArt != null && !albumArt.isRecycled) {
            canvas.drawBitmap(albumArt, null, artRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
            canvas.drawRect(artRect, fallbackPaint)
        }
        canvas.restore()

        val textX = artRect.right + pad * 0.8f
        val centerY = rect.centerY()
        canvas.drawText(truncTitle, textX, centerY - titlePaint.textSize * 0.15f, titlePaint)
        canvas.drawText(truncArtist, textX, centerY + artistPaint.textSize * 1.05f, artistPaint)
    }

    private fun drawScrollStyle(
        canvas: Canvas,
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int,
        posMs: Long
    ) {
        val maxWidth = (canvasWidth * 0.82f).toInt().coerceAtLeast(100)
        val tf = typefaceFor(config.font)
        val baseSize = canvasWidth * 0.056f
        val sideSize = canvasWidth * 0.041f

        val currentIdx = activeIdx.coerceAtLeast(0)
        val currentLine = lines[currentIdx]
        val prevLine = lines.getOrNull(currentIdx - 1)
        val nextLine = lines.getOrNull(currentIdx + 1)

        val elapsed = (posMs - currentLine.timeMs).coerceAtLeast(0L)
        val enterProgress = (elapsed / 260f).coerceIn(0f, 1f)
        val slideOffset = (1f - easeOutCubic(enterProgress)) * (canvasWidth * 0.032f)

        val activePaint = createTextPaint(
            color = config.textColor,
            textSize = baseSize,
            typeface = tf,
            shadowRadius = canvasWidth * 0.015f
        )
        val sidePaint = createTextPaint(
            color = ColorUtils.setAlphaComponent(config.textColor, 130),
            textSize = sideSize,
            typeface = tf,
            shadowRadius = canvasWidth * 0.01f
        )

        val activeLayout = buildLayout(currentLine.text, activePaint, maxWidth)
        val prevLayout = prevLine?.let { buildLayout(it.text, sidePaint, maxWidth) }
        val nextLayout = nextLine?.let { buildLayout(it.text, sidePaint, maxWidth) }

        val gap = canvasWidth * 0.032f
        val activeTop = -activeLayout.height / 2f + slideOffset

        prevLayout?.let {
            canvas.save()
            canvas.translate(-maxWidth / 2f, activeTop - gap - it.height)
            it.draw(canvas)
            canvas.restore()
        }

        canvas.save()
        canvas.translate(-maxWidth / 2f, activeTop)
        activeLayout.draw(canvas)
        canvas.restore()

        nextLayout?.let {
            canvas.save()
            canvas.translate(-maxWidth / 2f, activeTop + activeLayout.height + gap)
            it.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawTypewriterStyle(
        canvas: Canvas,
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int,
        posMs: Long
    ) {
        val maxWidth = (canvasWidth * 0.82f).toInt().coerceAtLeast(100)
        val currentIdx = activeIdx.coerceAtLeast(0)
        val currentLine = lines[currentIdx]
        val nextLineTime = lines.getOrNull(currentIdx + 1)?.timeMs ?: (currentLine.timeMs + 3_000L)
        val lineDuration = (nextLineTime - currentLine.timeMs).coerceIn(600L, 4_500L)
        val revealDuration = (lineDuration * 0.72f).coerceAtLeast(350f)
        val elapsed = (posMs - currentLine.timeMs).coerceAtLeast(0L)
        val progress = (elapsed / revealDuration).coerceIn(0f, 1f)

        val fullText = currentLine.text
        val charCount = (fullText.length * progress).toInt().coerceIn(1, max(1, fullText.length))
        val showCursor = (posMs / 400L) % 2L == 0L && progress < 1f
        val visibleText = fullText.take(charCount) + if (showCursor) "|" else ""

        val paint = createTextPaint(
            color = config.textColor,
            textSize = canvasWidth * 0.056f,
            typeface = typefaceFor(config.font),
            shadowRadius = canvasWidth * 0.015f
        )

        val layout = buildLayout(visibleText, paint, maxWidth)
        canvas.save()
        canvas.translate(-maxWidth / 2f, -layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawDynamicStyle(
        canvas: Canvas,
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int,
        posMs: Long
    ) {
        val maxWidth = (canvasWidth * 0.84f).toInt().coerceAtLeast(100)
        val currentIdx = activeIdx.coerceAtLeast(0)
        val currentLine = lines[currentIdx]
        val elapsed = (posMs - currentLine.timeMs).coerceAtLeast(0L)
        val t = (elapsed / 240f).coerceIn(0f, 1f)
        val eased = easeOutCubic(t)
        val popScale = 0.86f + 0.14f * eased
        val alpha = (255 * eased).toInt().coerceIn(40, 255)

        val paint = createTextPaint(
            color = ColorUtils.setAlphaComponent(config.textColor, alpha),
            textSize = canvasWidth * 0.064f,
            typeface = typefaceFor(config.font),
            shadowRadius = canvasWidth * 0.02f
        )
        val layout = buildLayout(currentLine.text, paint, maxWidth)

        canvas.save()
        canvas.scale(popScale, popScale)
        canvas.translate(-maxWidth / 2f, -layout.height / 2f)
        layout.draw(canvas)

        // Barra de acento inferior sutil
        val barW = canvasWidth * 0.16f * eased
        val barH = max(4f, canvasWidth * 0.007f)
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(config.accentColor, alpha)
        }
        val barLeft = (maxWidth - barW) / 2f
        val barTop = layout.height + canvasWidth * 0.022f
        canvas.drawRoundRect(
            RectF(barLeft, barTop, barLeft + barW, barTop + barH),
            barH / 2f,
            barH / 2f,
            barPaint
        )
        canvas.restore()
    }

    private fun drawCardStyle(
        canvas: Canvas,
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int
    ) {
        val maxTextW = (canvasWidth * 0.74f).toInt().coerceAtLeast(100)
        val currentIdx = activeIdx.coerceAtLeast(0)
        val currentLine = lines[currentIdx]
        val nextLine = lines.getOrNull(currentIdx + 1)

        val mainPaint = createTextPaint(
            color = config.textColor,
            textSize = canvasWidth * 0.052f,
            typeface = typefaceFor(config.font),
            shadowRadius = 0f
        )
        val mainLayout = buildLayout(currentLine.text, mainPaint, maxTextW)

        val nextPaint = createTextPaint(
            color = ColorUtils.setAlphaComponent(config.textColor, 165),
            textSize = canvasWidth * 0.036f,
            typeface = typefaceFor(config.font),
            shadowRadius = 0f
        )
        val nextLayout = nextLine?.let { buildLayout(it.text, nextPaint, maxTextW) }

        val padH = canvasWidth * 0.06f
        val padV = canvasWidth * 0.048f
        val gap = if (nextLayout != null) canvasWidth * 0.022f else 0f
        val contentH = mainLayout.height + gap + (nextLayout?.height ?: 0)
        val cardW = maxTextW + padH * 2f
        val cardH = contentH + padV * 2f

        val cardRect = RectF(-cardW / 2f, -cardH / 2f, cardW / 2f, cardH / 2f)
        val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(185, 16, 16, 26)
        }
        val cardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, canvasWidth * 0.003f)
            color = ColorUtils.setAlphaComponent(config.accentColor, 180)
        }
        val corner = canvasWidth * 0.048f
        canvas.drawRoundRect(cardRect, corner, corner, cardBg)
        canvas.drawRoundRect(cardRect, corner, corner, cardBorder)

        canvas.save()
        canvas.translate(-maxTextW / 2f, cardRect.top + padV)
        mainLayout.draw(canvas)
        if (nextLayout != null) {
            canvas.translate(0f, mainLayout.height + gap)
            nextLayout.draw(canvas)
        }
        canvas.restore()
    }

    private fun drawEmptyHint(
        canvas: Canvas,
        width: Int,
        height: Int,
        config: StoryConfig
    ) {
        val msg = if (config.track == null) {
            "Elige una foto y una canción\npara ver las letras aquí"
        } else {
            "Sin letra sincronizada\nPrueba buscar otra en Tramo"
        }
        val paint = createTextPaint(
            color = ColorUtils.setAlphaComponent(Color.WHITE, 190),
            textSize = width * 0.042f,
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
            shadowRadius = width * 0.01f
        )
        val maxW = (width * 0.78f).toInt().coerceAtLeast(100)
        val layout = buildLayout(msg, paint, maxW)
        canvas.save()
        canvas.translate((width - maxW) / 2f, height * config.lyricPosY - layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildLayout(text: String, paint: TextPaint, maxWidth: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()

    private fun createTextPaint(
        color: Int,
        textSize: Float,
        typeface: Typeface,
        shadowRadius: Float
    ): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.textSize = textSize
        this.typeface = typeface
        if (shadowRadius > 0f) {
            setShadowLayer(shadowRadius, 0f, shadowRadius * 0.25f, Color.argb(180, 0, 0, 0))
        }
    }

    private fun typefaceFor(font: LyricFont): Typeface = when (font) {
        LyricFont.SANS_BOLD -> Typeface.create("sans-serif", Typeface.BOLD)
        LyricFont.SERIF -> Typeface.create("serif", Typeface.BOLD)
        LyricFont.MONOSPACE -> Typeface.create("monospace", Typeface.BOLD)
        LyricFont.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
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
}
