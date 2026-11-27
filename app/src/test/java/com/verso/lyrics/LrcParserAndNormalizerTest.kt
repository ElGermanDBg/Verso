package com.verso.lyrics

import com.verso.lyrics.model.LrcParser
import com.verso.lyrics.model.LyricAnimation
import com.verso.lyrics.model.LyricFont
import com.verso.lyrics.model.LyricLine
import com.verso.lyrics.model.LyricStyle
import com.verso.lyrics.model.LyricsNormalizer
import com.verso.lyrics.model.activeLyricIndex
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
        assertEquals(5, LyricStyle.entries.size)
        assertEquals(6, LyricAnimation.entries.size)
    }
}
