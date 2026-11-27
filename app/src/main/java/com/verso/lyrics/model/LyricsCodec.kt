package com.verso.lyrics.model

import org.json.JSONArray
import org.json.JSONObject

/** Serializa letras a JSON compacto para persistencia offline. */
object LyricsCodec {

    const val VERSION = 1

    fun encode(lines: List<LyricLine>, synced: Boolean, source: String): String {
        val root = JSONObject()
        root.put("v", VERSION)
        root.put("synced", synced)
        root.put("source", source)
        val array = JSONArray()
        lines.forEach { line ->
            array.put(
                JSONObject().apply {
                    put("t", line.timeMs)
                    put("s", line.text)
                }
            )
        }
        root.put("lines", array)
        return root.toString()
    }

    fun encodeNotFound(timestamp: Long = System.currentTimeMillis()): String {
        val root = JSONObject()
        root.put("v", VERSION)
        root.put("notFound", true)
        root.put("ts", timestamp)
        return root.toString()
    }

    fun isRecentNotFound(json: String?, maxAgeMs: Long = 7L * 24 * 3600 * 1000): Boolean {
        if (json.isNullOrBlank()) return false
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return false
        if (!root.optBoolean("notFound", false)) return false
        val ts = root.optLong("ts", 0L)
        return (System.currentTimeMillis() - ts) < maxAgeMs
    }

    fun decode(json: String?): LyricsResult? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optInt("v", 0) > VERSION) return null
        if (root.optBoolean("notFound", false)) return null

        val array = root.optJSONArray("lines") ?: return null
        val lines = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val text = item.optString("s")
                if (text.isEmpty()) continue
                add(LyricLine(timeMs = item.optLong("t", 0L), text = text))
            }
        }
        if (lines.isEmpty()) return null

        return LyricsResult(
            lines = lines,
            synced = root.optBoolean("synced", false),
            source = root.optString("source").takeIf { it.isNotBlank() } ?: "Guardada"
        )
    }
}
