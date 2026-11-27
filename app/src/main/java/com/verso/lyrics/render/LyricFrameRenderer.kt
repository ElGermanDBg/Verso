package com.verso.lyrics.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
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
import com.verso.lyrics.model.LyricAnimation
import com.verso.lyrics.model.LyricFont
import com.verso.lyrics.model.LyricLine
import com.verso.lyrics.model.LyricStyle
import com.verso.lyrics.model.StoryConfig
import com.verso.lyrics.model.activeLyricIndex
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renderizador unificado sobre [Canvas] utilizado tanto para la vista previa en tiempo real
 * como para la generación de frames del video MP4 exportado.
 */
object LyricFrameRenderer {

    private val typefaceCache = ConcurrentHashMap<LyricFont, Typeface>()

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
        showCenterGuides: Boolean = false
    ) {
        if (width <= 0 || height <= 0) return

        drawBackground(canvas, width, height, photoBitmap, config.backgroundDim)

        if (showCenterGuides) {
            drawCenterGuides(canvas, width, height, config)
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

        if (lines.isEmpty()) {
            drawEmptyHint(context, canvas, width, height, config)
            return
        }

        val effectivePosMs = (positionMs + config.lyricsOffsetMs).coerceAtLeast(0L)
        val activeIdx = activeLyricIndex(lines, effectivePosMs)
        val typeface = resolveTypeface(context, config.font)

        canvas.save()
        val anchorX = width * config.lyricPosX
        val anchorY = height * config.lyricPosY
        canvas.translate(anchorX, anchorY)
        canvas.rotate(config.lyricRotationDeg)
        canvas.scale(config.lyricScale, config.lyricScale)

        when (config.style) {
            LyricStyle.SCROLL -> drawScrollStyle(canvas, width, config, lines, activeIdx, effectivePosMs, typeface)
            LyricStyle.FOCUS -> drawFocusStyle(canvas, width, config, lines, activeIdx, effectivePosMs, typeface)
            LyricStyle.CARD -> drawCardStyle(canvas, width, config, lines, activeIdx, effectivePosMs, typeface)
            LyricStyle.HIGHLIGHT -> drawHighlightStyle(canvas, width, config, lines, activeIdx, effectivePosMs, typeface)
            LyricStyle.CINEMA -> drawCinemaStyle(canvas, width, config, lines, activeIdx, effectivePosMs, typeface)
        }

        canvas.restore()
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
        val clipPath = android.graphics.Path().apply {
            addRoundRect(artRect, artCorner, artCorner, android.graphics.Path.Direction.CW)
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
        typeface: Typeface
    ) {
        val maxWidth = (canvasWidth * 0.82f).toInt().coerceAtLeast(100)
        val currentIdx = activeIdx.coerceAtLeast(0)
        val anim = computeAnimation(canvasWidth, config, lines, currentIdx, posMs)
        val prevLine = lines.getOrNull(currentIdx - 1)
        val nextLine = lines.getOrNull(currentIdx + 1)

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

        val activeLayout = buildLayout(anim.text, activePaint, maxWidth)
        val prevLayout = prevLine?.let { buildLayout(it.text, sidePaint, maxWidth) }
        val nextLayout = nextLine?.let { buildLayout(it.text, sidePaint, maxWidth) }

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
        activeLayout.draw(canvas)
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
        typeface: Typeface
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
        val layout = buildLayout(anim.text, paint, maxWidth)

        canvas.save()
        canvas.translate(0f, anim.offsetY)
        canvas.scale(anim.scale, anim.scale)
        canvas.translate(-maxWidth / 2f, -layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawCardStyle(
        canvas: Canvas,
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int,
        posMs: Long,
        typeface: Typeface
    ) {
        val maxTextW = (canvasWidth * 0.74f).toInt().coerceAtLeast(100)
        val currentIdx = activeIdx.coerceAtLeast(0)
        val anim = computeAnimation(canvasWidth, config, lines, currentIdx, posMs)
        val nextLine = lines.getOrNull(currentIdx + 1)

        val mainPaint = createTextPaint(
            color = ColorUtils.setAlphaComponent(config.textColor, anim.alpha),
            textSize = canvasWidth * 0.052f,
            typeface = typeface,
            shadowRadius = if (config.animation == LyricAnimation.GLOW_PULSE) anim.glowRadius else 0f,
            shadowColor = anim.glowColor
        )
        val mainLayout = buildLayout(anim.text, mainPaint, maxTextW)

        val nextPaint = createTextPaint(
            color = ColorUtils.setAlphaComponent(config.textColor, 160),
            textSize = canvasWidth * 0.036f,
            typeface = typeface,
            shadowRadius = 0f,
            shadowColor = Color.TRANSPARENT
        )
        val nextLayout = nextLine?.let { buildLayout(it.text, nextPaint, maxTextW) }

        val padH = canvasWidth * 0.06f
        val padV = canvasWidth * 0.048f
        val gap = if (nextLayout != null) canvasWidth * 0.022f else 0f
        val contentH = mainLayout.height + gap + (nextLayout?.height ?: 0)
        val cardW = maxTextW + padH * 2f
        val cardH = contentH + padV * 2f

        val cardRect = RectF(-cardW / 2f, -cardH / 2f, cardW / 2f, cardH / 2f)
        val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 16, 16, 26) }
        val cardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, canvasWidth * 0.0032f)
            color = ColorUtils.setAlphaComponent(config.accentColor, 185)
        }
        val corner = canvasWidth * 0.048f

        canvas.save()
        canvas.translate(0f, anim.offsetY)
        canvas.scale(anim.scale, anim.scale)
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
        canvas.restore()
    }

    private fun drawHighlightStyle(
        canvas: Canvas,
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int,
        posMs: Long,
        typeface: Typeface
    ) {
        val maxTextW = (canvasWidth * 0.76f).toInt().coerceAtLeast(100)
        val currentIdx = activeIdx.coerceAtLeast(0)
        val anim = computeAnimation(canvasWidth, config, lines, currentIdx, posMs)

        // Si el fondo es blanco o claro, texto oscuro para contraste; si es oscuro, texto blanco
        val lum = ColorUtils.calculateLuminance(config.accentColor)
        val autoTextColor = if (config.animation == LyricAnimation.KARAOKE) {
            config.textColor
        } else if (lum > 0.52) {
            Color.BLACK
        } else {
            Color.WHITE
        }

        val paint = createTextPaint(
            color = ColorUtils.setAlphaComponent(autoTextColor, anim.alpha),
            textSize = canvasWidth * 0.054f,
            typeface = typeface,
            shadowRadius = 0f,
            shadowColor = Color.TRANSPARENT
        )
        val layout = buildLayout(anim.text, paint, maxTextW)
        val padH = canvasWidth * 0.055f
        val padV = canvasWidth * 0.034f
        val boxW = maxTextW + padH * 2f
        val boxH = layout.height + padV * 2f
        val boxRect = RectF(-boxW / 2f, -boxH / 2f, boxW / 2f, boxH / 2f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (config.animation == LyricAnimation.KARAOKE) {
                Color.argb(215, 16, 16, 24)
            } else {
                ColorUtils.setAlphaComponent(config.accentColor, (anim.alpha * 0.92f).toInt().coerceIn(60, 240))
            }
        }
        val radius = canvasWidth * 0.036f

        canvas.save()
        canvas.translate(0f, anim.offsetY)
        canvas.scale(anim.scale, anim.scale)
        canvas.drawRoundRect(boxRect, radius, radius, boxPaint)
        canvas.translate(-maxTextW / 2f, -layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawCinemaStyle(
        canvas: Canvas,
        canvasWidth: Int,
        config: StoryConfig,
        lines: List<LyricLine>,
        activeIdx: Int,
        posMs: Long,
        typeface: Typeface
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
        val layout = buildLayout(anim.text, paint, maxWidth)

        canvas.save()
        canvas.translate(0f, anim.offsetY)
        canvas.scale(anim.scale, anim.scale)
        canvas.translate(-maxWidth / 2f, -layout.height / 2f)
        layout.draw(canvas)

        val barW = canvasWidth * 0.18f
        val barH = max(4f, canvasWidth * 0.007f)
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(config.accentColor, anim.alpha)
        }
        val barLeft = (maxWidth - barW) / 2f
        val barTop = layout.height + canvasWidth * 0.024f
        canvas.drawRoundRect(
            RectF(barLeft, barTop, barLeft + barW, barTop + barH),
            barH / 2f,
            barH / 2f,
            barPaint
        )
        canvas.restore()
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
        val layout = buildLayout("Sin letra sincronizada\nToca Tramo → Cambiar letra", paint, maxW)
        canvas.save()
        canvas.translate((width - maxW) / 2f, height * config.lyricPosY - layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildLayout(text: CharSequence, paint: TextPaint, maxWidth: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.15f)
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
