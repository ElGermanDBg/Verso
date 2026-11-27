package com.verso.lyrics.model

import android.net.Uri
import androidx.annotation.FontRes
import com.verso.lyrics.R

/** Estilos visuales disponibles para las letras sobre la foto. */
enum class LyricStyle(val label: String) {
    SCROLL("Scroll"),
    FOCUS("Enfoque"),
    CARD("Tarjeta"),
    HIGHLIGHT("Etiqueta"),
    CINEMA("Cine")
}

/** Animaciones de entrada y ritmo para el verso activo. */
enum class LyricAnimation(val label: String) {
    FADE_UP("Deslizar"),
    KARAOKE("Karaoke"),
    POP_BOUNCE("Rebote"),
    WORD_BY_WORD("Palabra"),
    TYPEWRITER("Máquina"),
    GLOW_PULSE("Neón")
}

/** Familias tipográficas embebidas para el bloque de letras. */
enum class LyricFont(val label: String, @FontRes val fontRes: Int) {
    MODERNA("Moderna", R.font.font_moderna),
    EDITORIAL("Editorial", R.font.font_editorial),
    IMPACTO("Impacto", R.font.font_impacto),
    MANUSCRITA("Script", R.font.font_manuscrita),
    NEON("Retro", R.font.font_neon),
    MAQUINA("Máquina", R.font.font_maquina)
}

/** Configuración completa de una historia musical editable y exportable a MP4. */
data class StoryConfig(
    val photoUri: Uri? = null,
    val track: LocalTrack? = null,
    val clipStartMs: Long = 0L,
    val clipDurationMs: Long = 15_000L,
    val lyricsOffsetMs: Int = 0,
    val style: LyricStyle = LyricStyle.SCROLL,
    val animation: LyricAnimation = LyricAnimation.FADE_UP,
    val font: LyricFont = LyricFont.MODERNA,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val accentColor: Int = 0xFFFF5C8A.toInt(),
    val backgroundDim: Float = 0.35f,
    val showSongBadge: Boolean = true,
    val lyricPosX: Float = 0.5f,
    val lyricPosY: Float = 0.55f,
    val lyricScale: Float = 1.0f,
    val lyricRotationDeg: Float = 0f
)
