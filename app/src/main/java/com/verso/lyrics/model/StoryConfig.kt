package com.verso.lyrics.model

import android.net.Uri

/** Estilos de animación disponibles para las letras sobre la foto. */
enum class LyricStyle(val label: String) {
    SCROLL("Scroll"),
    TYPEWRITER("Máquina"),
    DYNAMIC("Dinámico"),
    CARD("Tarjeta")
}

/** Familias tipográficas disponibles para el bloque de letras. */
enum class LyricFont(val label: String) {
    SANS_BOLD("Moderna"),
    SERIF("Editorial"),
    MONOSPACE("Máquina"),
    CONDENSED("Cine")
}

/** Configuración completa de una historia musical editable y exportable a MP4. */
data class StoryConfig(
    val photoUri: Uri? = null,
    val track: LocalTrack? = null,
    val clipStartMs: Long = 0L,
    val clipDurationMs: Long = 15_000L,
    val lyricsOffsetMs: Int = 0,
    val style: LyricStyle = LyricStyle.SCROLL,
    val font: LyricFont = LyricFont.SANS_BOLD,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val accentColor: Int = 0xFFFF5C8A.toInt(),
    val backgroundDim: Float = 0.35f,
    val showSongBadge: Boolean = true,
    val lyricPosX: Float = 0.5f,
    val lyricPosY: Float = 0.55f,
    val lyricScale: Float = 1.0f,
    val lyricRotationDeg: Float = 0f
)
