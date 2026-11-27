package com.verso.lyrics.data

import android.content.Context
import android.content.SharedPreferences
import com.verso.lyrics.model.LyricLine
import com.verso.lyrics.model.LyricsCodec
import com.verso.lyrics.model.LyricsResult
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/** Persiste letras y offsets en el dispositivo para uso offline. */
class LyricsStore internal constructor(
    private val dir: File,
    private val offsetPrefs: SharedPreferences?
) {

    constructor(context: Context) : this(
        dir = File(context.filesDir, DIR_NAME),
        offsetPrefs = context.getSharedPreferences("verso_lyrics_offsets", Context.MODE_PRIVATE)
    )

    private val indexState = ConcurrentHashMap<String, Boolean>()
    private val indexLock = Any()

    @Volatile
    private var isIndexLoaded = false

    private fun ensureIndexLoaded() {
        if (isIndexLoaded) return
        synchronized(indexLock) {
            if (isIndexLoaded) return
            indexState.clear()
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    if (!file.isFile || !file.name.endsWith(".json")) return@forEach
                    if (file.name.startsWith(NOT_FOUND_PREFIX)) {
                        val baseHash = file.name.removePrefix(NOT_FOUND_PREFIX).removeSuffix(".json")
                        if (baseHash.isNotEmpty()) indexState.putIfAbsent(baseHash, false)
                    } else {
                        val baseHash = file.name.removeSuffix(".json")
                        if (baseHash.isEmpty()) return@forEach
                        if (file.length() <= 0L) {
                            indexState[baseHash] = false
                        } else {
                            val content = runCatching { file.readText() }.getOrNull()
                            val valid = !content.isNullOrBlank() &&
                                !isNotFoundContent(content) &&
                                LyricsCodec.decode(content) != null
                            indexState[baseHash] = valid
                        }
                    }
                }
            }
            isIndexLoaded = true
        }
    }

    fun isRecentNotFound(key: String): Boolean {
        val notFound = runCatching { notFoundFileFor(key).readText() }.getOrNull()
        if (notFound != null) return LyricsCodec.isRecentNotFound(notFound)
        val legacy = runCatching { fileFor(key).readText() }.getOrNull() ?: return false
        return LyricsCodec.isRecentNotFound(legacy)
    }

    fun read(key: String): LyricsResult? {
        ensureIndexLoaded()
        val h = hash(key)
        if (indexState[h] == false) return null
        val content = runCatching { fileFor(key).readText() }.getOrNull()
        LyricsCodec.decode(content)?.let { decoded ->
            indexState[h] = true
            return decoded
        }
        if (content != null && isNotFoundContent(content)) {
            indexState[h] = false
            return null
        }
        return LyricsCodec.decode(runCatching { notFoundFileFor(key).readText() }.getOrNull())
    }

    fun write(key: String, lines: List<LyricLine>, synced: Boolean, source: String) {
        ensureIndexLoaded()
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val target = fileFor(key)
            val tmp = File(dir, target.name + TMP_SUFFIX)
            tmp.writeText(LyricsCodec.encode(lines, synced, source))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
            notFoundFileFor(key).delete()
            indexState[hash(key)] = lines.isNotEmpty()
        }
    }

    fun writeNotFound(key: String) {
        ensureIndexLoaded()
        runCatching {
            indexState[hash(key)] = false
            if (!dir.exists()) dir.mkdirs()
            val target = notFoundFileFor(key)
            val tmp = File(dir, target.name + TMP_SUFFIX)
            tmp.writeText(LyricsCodec.encodeNotFound())
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
            val legacy = fileFor(key)
            if (legacy.exists()) legacy.delete()
        }
    }

    fun delete(key: String) {
        ensureIndexLoaded()
        indexState.remove(hash(key))
        runCatching {
            fileFor(key).delete()
            notFoundFileFor(key).delete()
        }
    }

    fun getTrackOffset(key: String): Int = offsetPrefs?.getInt(key, 0) ?: 0

    fun setTrackOffset(key: String, offsetMs: Int) {
        offsetPrefs?.edit()?.putInt(key, offsetMs)?.apply()
    }

    private fun fileFor(key: String): File = File(dir, hash(key) + ".json")

    private fun notFoundFileFor(key: String): File = File(dir, NOT_FOUND_PREFIX + hash(key) + ".json")

    private fun isNotFoundContent(content: String): Boolean =
        content.isNotBlank() && (
            content.trim().equals("NONE", ignoreCase = true) ||
                runCatching { JSONObject(content).optBoolean("notFound", false) }.getOrDefault(false)
            )

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private companion object {
        const val DIR_NAME = "lyrics"
        const val TMP_SUFFIX = ".tmp"
        const val NOT_FOUND_PREFIX = "nf_"
    }
}
