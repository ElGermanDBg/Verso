package com.verso.lyrics.data

import android.content.Context
import android.net.Uri
import com.verso.lyrics.model.LocalTrack
import com.verso.lyrics.model.LrcParser
import com.verso.lyrics.model.LyricLine
import com.verso.lyrics.model.LyricsCandidate
import com.verso.lyrics.model.LyricsFetchResult
import com.verso.lyrics.model.LyricsNormalizer
import com.verso.lyrics.model.LyricsResult
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resuelve letras para una pista en cascada:
 * 1. Archivos `.lrc` adyacentes al audio.
 * 2. Carpeta `.lrc` configurada vía SAF.
 * 3. Caché offline en [LyricsStore].
 * 4. API abierta de LRCLIB (https://lrclib.net).
 */
class LyricsRepository(
    private val local: LocalLyricsRepository?,
    private val store: LyricsStore
) {

    constructor(context: Context) : this(
        local = LocalLyricsRepository(context),
        store = LyricsStore(context)
    )

    companion object {
        const val MATCHER_VERSION = "m2"
        const val SYNC_DURATION_TOLERANCE_SEC = 5
        const val MIN_ARTIST_SIM = 0.45f
        const val STRICT_ARTIST_SIM = 0.65f
        val ARTIST_SEPARATORS = Regex("""(?i)\s*(?:,|;|\s+x\s+|\s+&\s+|\s+y\s+|\s+con\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+)\s*""")
    }

    internal data class HttpResponse(
        val code: Int,
        val body: String?,
        val retryAfterHeader: String? = null
    )

    internal class LrclibHttpException(
        val statusCode: Int,
        val retryAfterMs: Long? = null,
        message: String
    ) : IOException(message)

    internal var httpTransport: (String) -> HttpResponse = ::defaultHttpTransport
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val cache = ConcurrentHashMap<String, LyricsFetchResult>()
    private val folderLrcCache = ConcurrentHashMap<String, FolderLrcCache>()

    private data class FolderLrcCache(
        val lastModified: Long,
        val lrcFiles: List<File>
    )

    fun hasLocalFolder(): Boolean = local?.hasFolder() ?: false
    fun localFolderName(): String? = local?.folderName()

    fun setLocalFolder(treeUri: Uri) {
        local?.setFolder(treeUri)
        cache.clear()
    }

    fun saveSelectedCandidate(track: LocalTrack, candidate: LyricsCandidate): LyricsFetchResult.Found {
        val key = cacheKey(track)
        val sourceLabel = if (candidate.synced) "LRCLIB Sincronizada" else "LRCLIB Texto"
        val found = LyricsFetchResult.Found(
            lines = candidate.lines,
            synced = candidate.synced,
            source = sourceLabel
        )
        cacheAndSave(key, found)
        return found
    }

    fun getTrackOffset(track: LocalTrack): Int = store.getTrackOffset(legacyCacheKey(track))

    fun setTrackOffset(track: LocalTrack, offsetMs: Int) {
        store.setTrackOffset(legacyCacheKey(track), offsetMs)
    }

    suspend fun fetch(
        track: LocalTrack,
        allowOnline: Boolean = true,
        forceOnline: Boolean = false
    ): LyricsFetchResult = withContext(ioDispatcher) {
        val key = cacheKey(track)
        if (!forceOnline) {
            cache[key]?.let { if (it !is LyricsFetchResult.Error) return@withContext it }

            findSidecarFile(track)?.let { result ->
                return@withContext cacheAndSave(
                    key,
                    LyricsFetchResult.Found(result.lines, result.synced, result.source)
                )
            }

            local?.let { safRepo ->
                runCatching { safRepo.find(track) }.getOrNull()?.let { result ->
                    return@withContext cacheAndSave(
                        key,
                        LyricsFetchResult.Found(result.lines, result.synced, result.source)
                    )
                }
            }

            store.read(key)?.let { result ->
                return@withContext cacheAndSave(
                    key,
                    LyricsFetchResult.Found(result.lines, result.synced, result.source)
                )
            }

            if (store.isRecentNotFound(key)) {
                return@withContext LyricsFetchResult.NotFound
            }
        }

        if (!allowOnline && !forceOnline) return@withContext LyricsFetchResult.NotFound

        val online = try {
            this@LyricsRepository.online(track)
        } catch (e: LrclibHttpException) {
            LyricsFetchResult.Error(
                retryable = true,
                isRateLimited = e.statusCode == 429,
                retryAfterMs = e.retryAfterMs,
                httpCode = e.statusCode
            )
        } catch (_: IOException) {
            LyricsFetchResult.Error(retryable = true)
        } catch (_: Exception) {
            LyricsFetchResult.Error(retryable = false)
        }

        if (online is LyricsFetchResult.Error) return@withContext online
        if (online == LyricsFetchResult.NotFound) {
            store.writeNotFound(key)
        }
        cacheAndSave(key, online)
    }

    suspend fun searchTopCandidates(
        track: LocalTrack,
        limit: Int = 5
    ): Result<List<LyricsCandidate>> = withContext(ioDispatcher) {
        runCatching {
            val titleNorm = LyricsNormalizer.normalizeTitle(track.title)
            val cleanTitle = titleNorm.cleanTitle
            val searchTitle = if (titleNorm.versionTag != null) titleNorm.titleWithVersion else cleanTitle
            val cleanArtist = LyricsNormalizer.cleanArtist(track.artist)
            val primaryArtist = primaryArtistOf(track.artist)
            val trackDurSec = (track.durationMs / 1000).toInt()

            val urls = buildList {
                if (searchTitle.isNotEmpty() && cleanArtist.isNotEmpty()) {
                    add(
                        "https://lrclib.net/api/search?" + query(
                            "track_name" to searchTitle,
                            "artist_name" to cleanArtist
                        )
                    )
                }
                if (cleanTitle.isNotEmpty() && primaryArtist.isNotEmpty() &&
                    (primaryArtist != cleanArtist || searchTitle != cleanTitle)
                ) {
                    add(
                        "https://lrclib.net/api/search?" + query(
                            "track_name" to cleanTitle,
                            "artist_name" to primaryArtist
                        )
                    )
                }
                val qText = listOf(searchTitle.ifEmpty { cleanTitle }, primaryArtist.ifEmpty { cleanArtist })
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                if (qText.isNotBlank()) {
                    add("https://lrclib.net/api/search?" + query("q" to qText))
                }
            }.distinct()

            val byId = LinkedHashMap<String, LyricsCandidate>()
            for (url in urls) {
                val body = httpGet(url) ?: continue
                val array = runCatching { JSONArray(body) }.getOrNull() ?: continue
                for (i in 0 until array.length()) {
                    val record = array.optJSONObject(i) ?: continue
                    val parsed = parseRecord(record, allowSynced = true) ?: continue
                    val candId = record.optLong("id", 0L)
                    val candTitle = record.optString("trackName", "").trim()
                    val candArtist = record.optString("artistName", "").trim()
                    val candAlbum = record.optString("albumName", "").trim()
                    val candDurSec = record.optInt("duration", 0)
                    val dedupeKey = if (candId > 0L) {
                        "id:$candId"
                    } else {
                        "${candTitle.lowercase()}|${candArtist.lowercase()}|$candDurSec|${parsed.synced}"
                    }
                    if (byId.containsKey(dedupeKey)) continue

                    val titleSim = if (cleanTitle.isNotEmpty()) {
                        LyricsNormalizer.similarity(LyricsNormalizer.cleanTitle(candTitle), cleanTitle)
                    } else 0.5f
                    val artistSim = if (cleanArtist.isNotEmpty()) {
                        maxOf(
                            LyricsNormalizer.similarity(candArtist, cleanArtist),
                            LyricsNormalizer.similarity(primaryArtistOf(candArtist), primaryArtist)
                        )
                    } else 0.5f
                    val durDiff = if (trackDurSec > 0 && candDurSec > 0) abs(candDurSec - trackDurSec) else 0
                    val candVer = LyricsNormalizer.extractVersionTag(candTitle)
                        ?: LyricsNormalizer.extractAlbumVersionHint(candAlbum)
                    val versionBonus = when {
                        titleNorm.versionTag != null && candVer == titleNorm.versionTag -> 2.0f
                        titleNorm.versionTag != null && candVer != titleNorm.versionTag -> -2.0f
                        titleNorm.versionTag == null && candVer == null -> 0.8f
                        else -> -1.0f
                    }
                    val score = titleSim * 3.5f +
                        artistSim * 2.5f +
                        (if (parsed.synced) 2.5f else 0f) +
                        versionBonus -
                        (durDiff * 0.15f)

                    val preview = parsed.lines.take(2).joinToString(" / ") { it.text }.take(120)

                    byId[dedupeKey] = LyricsCandidate(
                        id = candId,
                        trackName = candTitle.ifBlank { track.title },
                        artistName = candArtist.ifBlank { track.artist },
                        albumName = candAlbum,
                        durationSec = candDurSec,
                        synced = parsed.synced,
                        lines = parsed.lines,
                        previewText = preview,
                        score = score
                    )
                }
                if (byId.size >= limit * 2) break
            }

            byId.values.sortedByDescending { it.score }.take(limit)
        }
    }

    suspend fun searchCandidatesByQuery(
        title: String,
        artist: String,
        trackDurationMs: Long = 0L,
        limit: Int = 10
    ): Result<List<LyricsCandidate>> = withContext(ioDispatcher) {
        runCatching {
            val qTitle = title.trim()
            val qArtist = artist.trim()
            val trackDurSec = (trackDurationMs / 1000).toInt()

            val urls = buildList {
                if (qTitle.isNotBlank() && qArtist.isNotBlank()) {
                    add(
                        "https://lrclib.net/api/search?" + query(
                            "track_name" to qTitle,
                            "artist_name" to qArtist
                        )
                    )
                }
                val combo = listOf(qTitle, qArtist).filter { it.isNotBlank() }.joinToString(" ")
                if (combo.isNotBlank()) {
                    add("https://lrclib.net/api/search?" + query("q" to combo))
                }
                if (qTitle.isNotBlank()) {
                    add("https://lrclib.net/api/search?" + query("track_name" to qTitle))
                }
            }.distinct()

            val byId = LinkedHashMap<String, LyricsCandidate>()
            for (url in urls) {
                val body = httpGet(url) ?: continue
                val array = runCatching { JSONArray(body) }.getOrNull() ?: continue
                for (i in 0 until array.length()) {
                    val record = array.optJSONObject(i) ?: continue
                    val parsed = parseRecord(record, allowSynced = true) ?: continue
                    val candId = record.optLong("id", 0L)
                    val candTitle = record.optString("trackName", "").trim()
                    val candArtist = record.optString("artistName", "").trim()
                    val candAlbum = record.optString("albumName", "").trim()
                    val candDurSec = record.optInt("duration", 0)
                    val dedupeKey = if (candId > 0L) {
                        "id:$candId"
                    } else {
                        "${candTitle.lowercase()}|${candArtist.lowercase()}|$candDurSec|${parsed.synced}"
                    }
                    if (byId.containsKey(dedupeKey)) continue

                    val durDiff = if (trackDurSec > 0 && candDurSec > 0) abs(candDurSec - trackDurSec) else 0
                    val score = (if (parsed.synced) 3.0f else 1.0f) - (durDiff * 0.08f)
                    val preview = parsed.lines.take(2).joinToString(" / ") { it.text }.take(120)

                    byId[dedupeKey] = LyricsCandidate(
                        id = candId,
                        trackName = candTitle.ifBlank { qTitle },
                        artistName = candArtist.ifBlank { qArtist },
                        albumName = candAlbum,
                        durationSec = candDurSec,
                        synced = parsed.synced,
                        lines = parsed.lines,
                        previewText = preview,
                        score = score
                    )
                }
                if (byId.size >= limit * 2) break
            }

            byId.values.sortedByDescending { it.score }.take(limit)
        }
    }

    private fun findSidecarFile(track: LocalTrack): LyricsResult? {
        val folder = if (track.path.isNotBlank()) {
            File(track.path).parentFile
        } else if (track.folderPath.isNotBlank()) {
            File(track.folderPath)
        } else {
            null
        }
        val cacheFolderKey = folder?.absolutePath ?: track.folderPath
        if (folder != null && folder.exists() && folder.isDirectory) {
            val currentModified = folder.lastModified()
            val cached = folderLrcCache[cacheFolderKey]
            val lrcFiles = if (cached != null && cached.lastModified == currentModified) {
                cached.lrcFiles
            } else {
                val files = folder.listFiles { f -> f.isFile && f.extension.equals("lrc", ignoreCase = true) }?.toList() ?: emptyList()
                folderLrcCache[cacheFolderKey] = FolderLrcCache(currentModified, files)
                files
            }

            val candidate = lrcFiles.filter {
                LyricsNormalizer.fileNameMatches(it.name, track.title, track.artist)
            }.minByOrNull { it.name.length }

            if (candidate != null && candidate.canRead()) {
                val text = candidate.readText()
                val parsed = LrcParser.parse(text)
                if (parsed.isNotEmpty()) return LyricsResult(parsed, synced = true, source = "Archivo local .lrc")
                val plain = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { LyricLine(0L, it) }
                if (plain.isNotEmpty()) return LyricsResult(plain, synced = false, source = "Archivo local .lrc")
            }
        }
        return null
    }

    private fun legacyCacheKey(track: LocalTrack): String {
        val cleanArtist = LyricsNormalizer.cleanArtist(track.artist)
        val titleNorm = LyricsNormalizer.normalizeTitle(track.title)
        val versionSuffix = titleNorm.versionTag?.let { "|v=$it" } ?: ""
        return "$cleanArtist|${titleNorm.cleanTitle}$versionSuffix|${track.durationMs}"
    }

    private fun cacheKey(track: LocalTrack): String = "$MATCHER_VERSION|${legacyCacheKey(track)}"

    private fun cacheAndSave(key: String, result: LyricsFetchResult): LyricsFetchResult {
        if (result != LyricsFetchResult.NotFound && result !is LyricsFetchResult.Error) {
            cache[key] = result
        }
        (result as? LyricsFetchResult.Found)?.let { found ->
            store.write(key, found.lines, found.synced, found.source)
        }
        return result
    }

    private fun primaryArtistOf(artist: String): String {
        val clean = LyricsNormalizer.cleanArtist(artist)
        return clean.split(ARTIST_SEPARATORS).firstOrNull()?.trim().orEmpty()
    }

    private fun matchesVersionRequirement(
        requiredVersionTag: String?,
        candTitle: String,
        candAlbum: String
    ): Boolean {
        val candTitleTag = LyricsNormalizer.extractVersionTag(candTitle)
        return if (requiredVersionTag != null) {
            if (candTitleTag == requiredVersionTag) return true
            if (candTitleTag != null) return false
            LyricsNormalizer.extractAlbumVersionHint(candAlbum) == requiredVersionTag
        } else {
            candTitleTag == null
        }
    }

    private fun exactMatch(
        title: String,
        artist: String,
        album: String,
        durSec: Int,
        requiredVersionTag: String?
    ): LyricsResult? {
        val url = "https://lrclib.net/api/get?" + query(
            "track_name" to title,
            "artist_name" to artist,
            "album_name" to album,
            "duration" to durSec.toString()
        )
        val body = httpGet(url) ?: return null
        val record = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val candTitle = record.optString("trackName", title)
        val candAlbum = record.optString("albumName", album)
        if (!matchesVersionRequirement(requiredVersionTag, candTitle, candAlbum)) return null
        return parseRecord(record, allowSynced = true)
    }

    private fun online(track: LocalTrack): LyricsFetchResult {
        val titleNorm = LyricsNormalizer.normalizeTitle(track.title)
        val cleanTitle = titleNorm.cleanTitle
        val queryTitle = if (titleNorm.versionTag != null) titleNorm.titleWithVersion else cleanTitle
        val requiredVersionTag = titleNorm.versionTag
        val cleanArtist = LyricsNormalizer.cleanArtist(track.artist)
        val primaryArtist = primaryArtistOf(track.artist)
        val trackDurSec = (track.durationMs / 1000).toInt()
        val cleanAlbum = track.album.trim().takeIf {
            it.isNotBlank() &&
                !it.equals("unknown", ignoreCase = true) &&
                !it.equals("<unknown>", ignoreCase = true) &&
                !it.equals("desconocido", ignoreCase = true)
        }

        if (queryTitle.isNotEmpty() && cleanAlbum != null && trackDurSec > 0) {
            if (cleanArtist.isNotEmpty()) {
                exactMatch(queryTitle, cleanArtist, cleanAlbum, trackDurSec, requiredVersionTag)?.let {
                    return it.toFetchResult()
                }
            }
            if (primaryArtist.isNotEmpty() && primaryArtist != cleanArtist) {
                exactMatch(queryTitle, primaryArtist, cleanAlbum, trackDurSec, requiredVersionTag)?.let {
                    return it.toFetchResult()
                }
            }
        }

        if (queryTitle.isNotEmpty() && cleanArtist.isNotEmpty()) {
            val url = "https://lrclib.net/api/search?" + query(
                "track_name" to queryTitle,
                "artist_name" to cleanArtist
            )
            searchAndPickBest(
                url = url,
                targetTitle = cleanTitle,
                targetArtist = cleanArtist,
                targetDurSec = trackDurSec,
                requiredVersionTag = requiredVersionTag,
                minArtistSim = MIN_ARTIST_SIM
            )?.let { return it.toFetchResult() }
        }

        if (cleanTitle.isNotEmpty() && primaryArtist.isNotEmpty() &&
            (primaryArtist != cleanArtist || queryTitle != cleanTitle)
        ) {
            val url = "https://lrclib.net/api/search?" + query(
                "track_name" to queryTitle,
                "artist_name" to primaryArtist
            )
            searchAndPickBest(
                url = url,
                targetTitle = cleanTitle,
                targetArtist = primaryArtist,
                targetDurSec = trackDurSec,
                requiredVersionTag = requiredVersionTag,
                minArtistSim = MIN_ARTIST_SIM
            )?.let { return it.toFetchResult() }
        }

        val queryText = listOfNotNull(
            queryTitle.takeIf { it.isNotEmpty() },
            primaryArtist.ifEmpty { cleanArtist }.takeIf { it.isNotEmpty() }
        ).joinToString(" ")
        if (queryText.isNotBlank()) {
            val url = "https://lrclib.net/api/search?" + query("q" to queryText)
            searchAndPickBest(
                url = url,
                targetTitle = cleanTitle,
                targetArtist = primaryArtist.ifEmpty { cleanArtist },
                targetDurSec = trackDurSec,
                requiredVersionTag = requiredVersionTag,
                minArtistSim = MIN_ARTIST_SIM
            )?.let { return it.toFetchResult() }
        }

        if (queryTitle.isNotEmpty() && cleanArtist.isNotEmpty() && trackDurSec > 0) {
            val url = "https://lrclib.net/api/search?" + query("track_name" to queryTitle)
            searchAndPickBest(
                url = url,
                targetTitle = cleanTitle,
                targetArtist = cleanArtist,
                targetDurSec = trackDurSec,
                requiredVersionTag = requiredVersionTag,
                minArtistSim = STRICT_ARTIST_SIM,
                maxDurDiffSec = 4
            )?.let { return it.toFetchResult() }
        }

        return LyricsFetchResult.NotFound
    }

    private fun searchAndPickBest(
        url: String,
        targetTitle: String,
        targetArtist: String,
        targetDurSec: Int,
        requiredVersionTag: String?,
        minArtistSim: Float = MIN_ARTIST_SIM,
        maxDurDiffSec: Int = Int.MAX_VALUE
    ): LyricsResult? {
        val body = httpGet(url) ?: return null
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null

        data class ScoredCandidate(val result: LyricsResult, val score: Float)
        val candidates = mutableListOf<ScoredCandidate>()

        val normTargetTitle = LyricsNormalizer.normalizeForSim(LyricsNormalizer.cleanTitle(targetTitle))
        val normTargetArtist = LyricsNormalizer.normalizeForSim(LyricsNormalizer.cleanArtist(targetArtist))

        for (i in 0 until array.length()) {
            val record = array.optJSONObject(i) ?: continue
            val candTitle = record.optString("trackName", "")
            val candArtist = record.optString("artistName", "")
            val candAlbum = record.optString("albumName", "")
            val candDurSec = record.optInt("duration", 0)

            if (!matchesVersionRequirement(requiredVersionTag, candTitle, candAlbum)) continue

            val cleanCandTitle = LyricsNormalizer.cleanTitle(candTitle)
            val normCandTitle = LyricsNormalizer.normalizeForSim(cleanCandTitle)
            val titleSim = LyricsNormalizer.similarity(cleanCandTitle, targetTitle)

            val oneContainsOther = normTargetTitle.isNotEmpty() && normCandTitle.isNotEmpty() &&
                (normTargetTitle.contains(normCandTitle) || normCandTitle.contains(normTargetTitle))
            val minNormLen = minOf(normTargetTitle.length, normCandTitle.length)
            val containsAllowed = oneContainsOther && minNormLen >= 6

            if (titleSim < 0.60f && !containsAllowed) continue

            val artistSim = if (targetArtist.isNotBlank()) {
                val candPrimary = primaryArtistOf(candArtist)
                val normCandArtist = LyricsNormalizer.normalizeForSim(LyricsNormalizer.cleanArtist(candArtist))
                val normCandPrimary = LyricsNormalizer.normalizeForSim(candPrimary)

                val containsArtist = normTargetArtist.isNotEmpty() && normCandArtist.isNotEmpty() &&
                    (
                        normTargetArtist.contains(normCandArtist) ||
                            normCandArtist.contains(normTargetArtist) ||
                            (normCandPrimary.isNotEmpty() && (normCandPrimary.contains(normTargetArtist) || normTargetArtist.contains(normCandPrimary)))
                        )

                val simDirect = LyricsNormalizer.similarity(candArtist, targetArtist)
                val simPrimary = if (candPrimary.isNotEmpty()) LyricsNormalizer.similarity(candPrimary, targetArtist) else 0f
                val simContains = if (containsArtist) 0.85f else 0f

                val maxSim = maxOf(simDirect, simPrimary, simContains)
                if (maxSim < minArtistSim) continue
                maxSim
            } else {
                1.0f
            }

            val durDiffSec = if (targetDurSec > 0 && candDurSec > 0) abs(candDurSec - targetDurSec) else 0
            if (targetDurSec > 0 && candDurSec > 0 && durDiffSec > maxDurDiffSec) continue

            val allowSynced = targetDurSec <= 0 || durDiffSec <= SYNC_DURATION_TOLERANCE_SEC
            val result = parseRecord(record, allowSynced = allowSynced) ?: continue

            val score = titleSim * 3.5f +
                artistSim * 2.5f +
                (if (result.synced) 3.0f else 0.0f) -
                (durDiffSec * 0.35f)

            candidates += ScoredCandidate(result, score)
        }

        return candidates.maxByOrNull { it.score }?.result
    }

    private fun LyricsResult.toFetchResult(): LyricsFetchResult =
        LyricsFetchResult.Found(lines, synced, source)

    private fun parseRecord(record: JSONObject, allowSynced: Boolean = true): LyricsResult? {
        if (record.optBoolean("instrumental", false)) return null

        if (allowSynced) {
            val synced = record.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            if (synced != null) {
                val lines = LrcParser.parse(synced)
                if (lines.isNotEmpty()) {
                    return LyricsResult(lines, synced = true, source = "LRCLIB Sincronizada")
                }
            }
        }

        val plain = record.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (plain != null) {
            val lines = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { LyricLine(0L, it) }
            if (lines.isNotEmpty()) {
                return LyricsResult(lines, synced = false, source = "LRCLIB Texto")
            }
        }

        return null
    }

    internal fun parseRetryAfterHeaderMs(retryAfterHeader: String?): Long? {
        val raw = retryAfterHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seconds = raw.toLongOrNull() ?: return null
        return (seconds.coerceAtLeast(1L) * 1_000L).coerceAtMost(60_000L)
    }

    private fun httpGet(url: String): String? {
        val response = httpTransport(url)
        val code = response.code
        if (code == 429 || code >= 500) {
            throw LrclibHttpException(
                statusCode = code,
                retryAfterMs = parseRetryAfterHeaderMs(response.retryAfterHeader),
                message = "LRCLIB HTTP $code"
            )
        }
        return if (code in 200..299) response.body else null
    }

    private fun defaultHttpTransport(url: String): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Verso/1.0 (Android)")
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            val retryAfter = connection.getHeaderField("Retry-After")
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            HttpResponse(code = code, body = body, retryAfterHeader = retryAfter)
        } finally {
            connection.disconnect()
        }
    }

    private fun query(vararg params: Pair<String, String>): String =
        params.joinToString("&") { (name, value) ->
            val encoded = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
            "$name=$encoded"
        }
}
