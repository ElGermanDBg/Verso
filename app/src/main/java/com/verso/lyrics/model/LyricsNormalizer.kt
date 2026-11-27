package com.verso.lyrics.model

import java.text.Normalizer

data class NormalizedTitle(
    val cleanTitle: String,
    val titleWithVersion: String,
    val versionTag: String? = null
)

/** Normaliza títulos y artistas antes de consultar LRCLIB o comparar archivos `.lrc`. */
object LyricsNormalizer {

    private val PROMO_JUNK_SUFFIX = Regex(
        """(?i)\s*[\(\[\{]\s*(?:official\s*(?:video|audio|music\s*video|lyric\s*video|visualizer)?|video\s*oficial|audio\s*oficial|remaster(?:ed|izado)?(?:\s*\d{4})?|video\s*clip|lyric\s*video|audio|visualizer|deluxe(?:\s*edition)?|bonus\s*track|explicit|clean|hd|hq|4k|prod\.?\s*by\s*[^)\]}]+)\s*[\)\]\}]"""
    )

    private val BRACKETED_VERSION_SUFFIX = Regex(
        """(?i)\s*[\(\[\{]\s*([^\)\]\}]*\b(?:live|en\s+vivo|en\s+directo|ao\s+vivo|remix|re-mix|vip\s+mix|club\s+mix|dub\s+mix|acoustic|ac[uú]stic[oa]|unplugged|sped\s*up|nightcore|slowed|instrumental)\b[^\)\]\}]*)\s*[\)\]\}]"""
    )

    private val DASH_VERSION_SUFFIX = Regex(
        """(?i)\s+[-–—]\s+([^\(\[\{]*\b(?:live|en\s+vivo|en\s+directo|ao\s+vivo|remix|re-mix|vip\s+mix|club\s+mix|dub\s+mix|acoustic|ac[uú]stic[oa]|unplugged|sped\s*up|nightcore|slowed|instrumental)\b.*)$"""
    )

    private val LIVE_TOKEN = Regex("""(?i)\b(?:live|en\s+vivo|en\s+directo|ao\s+vivo)\b""")
    private val REMIX_TOKEN = Regex("""(?i)\b(?:remix|re-mix|vip\s+mix|club\s+mix|dub\s+mix)\b""")
    private val ACOUSTIC_TOKEN = Regex("""(?i)\b(?:acoustic|ac[uú]stic[oa]|unplugged)\b""")
    private val SPED_UP_TOKEN = Regex("""(?i)\b(?:sped\s*up|nightcore)\b""")
    private val SLOWED_TOKEN = Regex("""(?i)\bslowed\b""")
    private val INSTRUMENTAL_TOKEN = Regex("""(?i)\binstrumental\b""")

    private val FEAT_PARENS = Regex("""(?i)\s*[\(\[]\s*(?:feat\.?|ft\.?|featuring)\b[^\)\]]*[\)\]]?""")
    private val FEAT_STANDALONE = Regex("""(?i)\s*\b(?:feat\.?|ft\.?|featuring)\b.*""")

    private val TRACK_NUMBER_PREFIX_STRICT = Regex("""^(?:\d{1,3}\s+[-–—.]\s+|\d{1,2}\.\s+)""")
    private val TRACK_NUMBER_PREFIX_FILENAME = Regex("""^\d{1,3}(?:\s*[-–—._]\s*|\s+)(?=\p{L})""")

    private val FILE_EXT = Regex("""\.(?:mp3|flac|m4a|aac|wav|ogg|opus|lrc|txt)$""", RegexOption.IGNORE_CASE)
    private val EMPTY_PARENS = Regex("""\s*[\(\[\{]\s*[\)\]\}]""")
    private val MULTI_SPACE = Regex("""\s{2,}""")
    private val COMBINING_DIACRITICAL_MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_LETTER_OR_DIGIT = Regex("""[^\p{L}\p{N}]""")

    fun extractVersionTag(rawText: String): String? {
        if (rawText.isBlank()) return null
        val withoutExt = rawText.replace(FILE_EXT, "")
        val versionFragments = buildList {
            BRACKETED_VERSION_SUFFIX.findAll(withoutExt).forEach { add(it.groupValues[1]) }
            DASH_VERSION_SUFFIX.find(withoutExt)?.let { add(it.groupValues[1]) }
        }
        val candidateText = if (versionFragments.isNotEmpty()) {
            versionFragments.joinToString(" ")
        } else {
            return null
        }
        return classifyVersionToken(candidateText)
    }

    fun extractAlbumVersionHint(albumName: String): String? {
        if (albumName.isBlank()) return null
        return classifyVersionToken(albumName)
    }

    private fun classifyVersionToken(text: String): String? = when {
        REMIX_TOKEN.containsMatchIn(text) -> "remix"
        LIVE_TOKEN.containsMatchIn(text) -> "live"
        ACOUSTIC_TOKEN.containsMatchIn(text) -> "acoustic"
        SPED_UP_TOKEN.containsMatchIn(text) -> "sped_up"
        SLOWED_TOKEN.containsMatchIn(text) -> "slowed"
        INSTRUMENTAL_TOKEN.containsMatchIn(text) -> "instrumental"
        else -> null
    }

    fun normalizeTitle(title: String, fromFileName: Boolean = false): NormalizedTitle {
        var working = title.replace(FILE_EXT, "")
        working = if (fromFileName) {
            working.replace(TRACK_NUMBER_PREFIX_STRICT, "").replace(TRACK_NUMBER_PREFIX_FILENAME, "")
        } else {
            working.replace(TRACK_NUMBER_PREFIX_STRICT, "")
        }
        val versionTag = extractVersionTag(working)

        val keepVersion = working
            .replace(PROMO_JUNK_SUFFIX, "")
            .replace(FEAT_PARENS, "")
            .replace(FEAT_STANDALONE, "")
            .replace(EMPTY_PARENS, "")
            .replace(MULTI_SPACE, " ")
            .trim()

        val baseClean = keepVersion
            .replace(BRACKETED_VERSION_SUFFIX, "")
            .replace(DASH_VERSION_SUFFIX, "")
            .replace(EMPTY_PARENS, "")
            .replace(MULTI_SPACE, " ")
            .trim()

        val effectiveClean = baseClean.ifEmpty { keepVersion }
        return NormalizedTitle(
            cleanTitle = effectiveClean,
            titleWithVersion = keepVersion.ifEmpty { effectiveClean },
            versionTag = versionTag
        )
    }

    fun cleanTitle(title: String, fromFileName: Boolean = false): String =
        normalizeTitle(title, fromFileName = fromFileName).cleanTitle

    fun cleanArtist(artist: String): String {
        val lower = artist.trim().lowercase()
        if (lower == "<unknown>" || lower == "unknown" || lower == "desconocido" || lower == "<desconocido>") {
            return ""
        }
        var clean = artist.replace(FEAT_PARENS, "")
        clean = clean.replace(FEAT_STANDALONE, "")
        clean = clean.replace(PROMO_JUNK_SUFFIX, "")
        clean = clean.replace(BRACKETED_VERSION_SUFFIX, "")
        clean = clean.replace(EMPTY_PARENS, "")
        return clean.replace(MULTI_SPACE, " ").trim()
    }

    fun normalizeForSim(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(COMBINING_DIACRITICAL_MARKS, "")
            .replace(NON_LETTER_OR_DIGIT, "")

    fun similarity(s1: String, s2: String): Float {
        val n1 = normalizeForSim(s1)
        val n2 = normalizeForSim(s2)
        if (n1.isEmpty() || n2.isEmpty()) {
            return if (s1.isNotEmpty() && s1.equals(s2, ignoreCase = true)) 1f else 0f
        }
        if (n1 == n2) return 1f
        val maxLen = maxOf(n1.length, n2.length)
        val dist = levenshtein(n1, n2)
        return (1f - dist.toFloat() / maxLen.toFloat()).coerceIn(0f, 1f)
    }

    fun fileNameMatches(fileName: String, title: String, artist: String): Boolean {
        val titleNorm = normalizeTitle(title, fromFileName = false)
        if (titleNorm.cleanTitle.isEmpty()) return false
        val normT = normalizeForSim(titleNorm.cleanTitle)
        if (normT.isEmpty()) return false

        val fileStrict = normalizeTitle(fileName, fromFileName = false)
        val fileStripped = normalizeTitle(fileName, fromFileName = true)

        val fileVersion = fileStrict.versionTag ?: fileStripped.versionTag
        if (titleNorm.versionTag != fileVersion) return false

        val cleanA = cleanArtist(artist)
        val normA = normalizeForSim(cleanA)

        val candidateNormFiles = listOf(
            normalizeForSim(fileStrict.cleanTitle),
            normalizeForSim(fileStripped.cleanTitle)
        ).filter { it.isNotEmpty() }.distinct()

        if (candidateNormFiles.isEmpty()) return false

        return candidateNormFiles.any { normFile ->
            if (normFile == normT) return@any true
            if (normA.isNotEmpty()) {
                if (normFile == normA + normT || normFile == normT + normA) return@any true
            }
            if (normT.length >= 4 && normA.isNotEmpty()) {
                if (normFile.contains(normT) && normFile.contains(normA)) {
                    return@any true
                }
            }
            false
        }
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                dp[j] = if (s1[i - 1] == s2[j - 1]) prev else minOf(prev, dp[j], dp[j - 1]) + 1
                prev = temp
            }
        }
        return dp[s2.length]
    }
}
