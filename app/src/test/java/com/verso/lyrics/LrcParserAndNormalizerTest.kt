package com.verso.lyrics

import com.verso.lyrics.audio.AudioBeatAnalyzer
import com.verso.lyrics.audio.BeatEnvelope
import com.verso.lyrics.model.BrushStyle
import com.verso.lyrics.model.LrcParser
import com.verso.lyrics.model.LyricAlignment
import com.verso.lyrics.model.LyricAnimation
import com.verso.lyrics.model.LyricEffect
import com.verso.lyrics.model.LyricFont
import com.verso.lyrics.model.LyricLine
import com.verso.lyrics.model.LyricStyle
import com.verso.lyrics.model.LyricsNormalizer
import com.verso.lyrics.model.PhotoFilter
import com.verso.lyrics.model.activeLyricIndex
import com.verso.lyrics.render.SubjectSegmenter
import com.verso.lyrics.ui.VERSO_PRESETS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrcParserAndNormalizerTest {

    @Test
    fun parseLrcAndFindActiveLine() {
        val lrc = """
            [00:10.50] Primera línea
            [00:15.00] Segunda línea
            [00:20.25] Tercera línea
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(3, lines.size)
        assertEquals(10_500L, lines[0].timeMs)
        assertEquals("Segunda línea", lines[1].text)

        assertEquals(-1, activeLyricIndex(lines, 5_000L))
        assertEquals(0, activeLyricIndex(lines, 12_000L))
        assertEquals(1, activeLyricIndex(lines, 15_000L))
        assertEquals(2, activeLyricIndex(lines, 25_000L))
    }

    @Test
    fun normalizeTitlePreservesVersionAndStripsPromoJunk() {
        val studio = LyricsNormalizer.normalizeTitle("01 - Mi Canción (Official Video)")
        assertEquals("Mi Canción", studio.cleanTitle)
        assertNull(studio.versionTag)

        val live = LyricsNormalizer.normalizeTitle("Mi Canción (En Vivo) [Audio Oficial]")
        assertEquals("Mi Canción", live.cleanTitle)
        assertEquals("live", live.versionTag)

        val digitsTitle = LyricsNormalizer.normalizeTitle("7-Eleven")
        assertEquals("7-Eleven", digitsTitle.cleanTitle)
    }

    @Test
    fun activeLyricIndexHandlesEmptyList() {
        assertEquals(-1, activeLyricIndex(emptyList<LyricLine>(), 1000L))
    }

    @Test
    fun storyStylesFontsAndAnimationsAreAvailable() {
        assertEquals(6, LyricFont.entries.size)
        assertEquals(7, LyricStyle.entries.size)
        assertEquals(6, LyricAnimation.entries.size)
        assertEquals(7, LyricEffect.entries.size)
        assertEquals(4, BrushStyle.entries.size)
        assertEquals(6, PhotoFilter.entries.size)
        assertEquals(3, LyricAlignment.entries.size)
    }

    @Test
    fun beatEnvelopeInterpolatesAndClampsCorrectly() {
        val env = BeatEnvelope(
            startMs = 1_000L,
            frameStepMs = 100L,
            levels = floatArrayOf(0.0f, 1.0f, 0.4f)
        )
        assertEquals(0.0f, env.pulseAt(500L), 0.001f)
        assertEquals(0.0f, env.pulseAt(1_000L), 0.001f)
        assertEquals(0.5f, env.pulseAt(1_050L), 0.001f)
        assertEquals(1.0f, env.pulseAt(1_100L), 0.001f)
        assertEquals(0.7f, env.pulseAt(1_150L), 0.001f)
        assertEquals(0.4f, env.pulseAt(1_500L), 0.001f)

        val empty = BeatEnvelope(startMs = 0L, frameStepMs = 33L, levels = FloatArray(0))
        assertEquals(0.0f, empty.pulseAt(100L), 0.001f)
    }

    @Test
    fun computeBeatLevelsDetectsPeakAndDecaysSmoothly() {
        val rms = FloatArray(30) { 0.05f }
        rms[12] = 0.85f // golpe de grave fuerte
        val levels = AudioBeatAnalyzer.computeBeatLevels(rms)
        assertEquals(30, levels.size)
        assertEquals(1.0f, levels[12], 0.05f)
        // Decaimiento suave inmediatamente después del golpe
        assert(levels[13] > levels[14] && levels[14] > 0.3f)
        // Silencio produce ceros
        val silent = AudioBeatAnalyzer.computeBeatLevels(FloatArray(10) { 0f })
        assertEquals(0.0f, silent.max(), 0.001f)
    }

    @Test
    fun smartBrushRespectsColorBoundariesAndRadius() {
        // Mismo color en el centro: agrega persona con alpha alto
        val sameColor = SubjectSegmenter.computeSmartBrushAlpha(
            oldAlpha = 0,
            dist = 2f,
            radius = 20f,
            colorDist = 5f,
            colorTolerance = 46f,
            addMode = true
        )
        assert(sameColor >= 240)

        // Color muy distinto (fondo) dentro del radio: no lo agrega
        val diffColor = SubjectSegmenter.computeSmartBrushAlpha(
            oldAlpha = 0,
            dist = 5f,
            radius = 20f,
            colorDist = 110f,
            colorTolerance = 46f,
            addMode = true
        )
        assertEquals(0, diffColor)

        // Modo borrar (- Fondo) con color similar: borra la máscara
        val eraseSimilar = SubjectSegmenter.computeSmartBrushAlpha(
            oldAlpha = 255,
            dist = 2f,
            radius = 20f,
            colorDist = 6f,
            colorTolerance = 46f,
            addMode = false
        )
        assert(eraseSimilar <= 15)

        // Fuera del radio: conserva el valor anterior
        val outside = SubjectSegmenter.computeSmartBrushAlpha(
            oldAlpha = 180,
            dist = 25f,
            radius = 20f,
            colorDist = 0f,
            colorTolerance = 46f,
            addMode = true
        )
        assertEquals(180, outside)
    }

    @Test
    fun versoPresetsAreWellFormed() {
        assertEquals(6, VERSO_PRESETS.size)
        val ids = VERSO_PRESETS.map { it.id }.toSet()
        assertEquals(VERSO_PRESETS.size, ids.size)
        VERSO_PRESETS.forEach { preset ->
            assert(preset.name.isNotBlank())
            assert(preset.tagline.isNotBlank())
            assert(preset.previewColors.size >= 2)
        }
    }
}
