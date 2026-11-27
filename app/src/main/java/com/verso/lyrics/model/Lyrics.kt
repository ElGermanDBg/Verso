package com.verso.lyrics.model

/** Línea individual de letra asociada a una marca de tiempo (0 si no está sincronizada). */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

/** Resultado resuelto para una pista, sincronizada (LRC) o texto plano. */
data class LyricsResult(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: String
)

/** Estado de UI para las letras cargadas. */
sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Available(
        val lines: List<LyricLine>,
        val synced: Boolean,
        val source: String
    ) : LyricsState
    data object NotFound : LyricsState
    data object Error : LyricsState
}

/** Resultado de búsqueda de letras diferenciando NotFound de errores transitorios. */
sealed interface LyricsFetchResult {
    data class Found(
        val lines: List<LyricLine>,
        val synced: Boolean,
        val source: String
    ) : LyricsFetchResult

    data object NotFound : LyricsFetchResult

    data class Error(
        val retryable: Boolean = true,
        val isRateLimited: Boolean = false,
        val retryAfterMs: Long? = null,
        val httpCode: Int? = null
    ) : LyricsFetchResult
}

/** Candidato devuelto por LRCLIB para selección manual. */
data class LyricsCandidate(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Int,
    val synced: Boolean,
    val lines: List<LyricLine>,
    val previewText: String,
    val score: Float
)

/** Búsqueda binaria del índice de la línea activa para [positionMs]. */
fun activeLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var low = 0
    var high = lines.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].timeMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

/** Parser para formato LRC: `[mm:ss.xx] línea` (múltiples tags, offset y marcas de pausa). */
object LrcParser {

    private val TIME_TAG = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val OFFSET_TAG = Regex("\\[offset:\\s*([+-]?\\d+)\\]", RegexOption.IGNORE_CASE)
    private val ENHANCED_TAG = Regex("<\\d{1,2}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private val WHITESPACE = Regex("\\s+")

    fun parse(lrc: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()

        var fileOffsetMs = 0L
        lrc.lineSequence().forEach { rawLine ->
            OFFSET_TAG.find(rawLine)?.let { match ->
                fileOffsetMs = match.groupValues[1].toLongOrNull() ?: 0L
            }
        }

        lrc.lineSequence().forEach { rawLine ->
            val tags = TIME_TAG.findAll(rawLine).toList()
            if (tags.isEmpty()) return@forEach

            val lastTagEnd = tags.last().range.last + 1
            val text = if (lastTagEnd < rawLine.length) {
                rawLine.substring(lastTagEnd)
                    .replace(ENHANCED_TAG, "")
                    .replace(WHITESPACE, " ")
                    .trim()
            } else {
                ""
            }

            if (text.isEmpty()) return@forEach

            tags.forEach { tag ->
                val minutes = tag.groupValues[1].toLongOrNull() ?: 0L
                val seconds = tag.groupValues[2].toLongOrNull() ?: 0L
                val fraction = tag.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                val time = (minutes * 60_000 + seconds * 1_000 + millis - fileOffsetMs).coerceAtLeast(0L)
                result.add(LyricLine(timeMs = time, text = text))
            }
        }

        return result.sortedBy { it.timeMs }
    }
}
