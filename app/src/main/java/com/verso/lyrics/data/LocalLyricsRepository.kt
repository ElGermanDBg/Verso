package com.verso.lyrics.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.verso.lyrics.model.LocalTrack
import com.verso.lyrics.model.LrcParser
import com.verso.lyrics.model.LyricLine
import com.verso.lyrics.model.LyricsNormalizer
import com.verso.lyrics.model.LyricsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lee archivos `.lrc` desde una carpeta concedida por el usuario vía SAF. */
class LocalLyricsRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val indexLock = Any()

    @Volatile
    private var cachedTree: String? = null

    @Volatile
    private var cachedIndex: Map<String, Uri>? = null

    fun hasFolder(): Boolean = prefs.getString(KEY_TREE_URI, null) != null

    fun folderName(): String? = prefs.getString(KEY_TREE_NAME, null)

    fun setFolder(treeUri: Uri) {
        val name = treeUri.lastPathSegment
            ?.substringAfterLast(':')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: treeUri.lastPathSegment

        prefs.edit()
            .putString(KEY_TREE_URI, treeUri.toString())
            .putString(KEY_TREE_NAME, name)
            .apply()

        synchronized(indexLock) {
            cachedTree = null
            cachedIndex = null
        }
    }

    suspend fun find(track: LocalTrack): LyricsResult? = withContext(Dispatchers.IO) {
        val treeUri = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse) ?: return@withContext null
        val fileUri = matchTrack(indexFor(treeUri), track) ?: return@withContext null
        val content = readText(fileUri) ?: return@withContext null

        val synced = LrcParser.parse(content)
        if (synced.isNotEmpty()) {
            LyricsResult(lines = synced, synced = true, source = "Archivo .lrc")
        } else {
            val plain = content.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { LyricLine(timeMs = 0L, text = it) }
                .toList()
            if (plain.isEmpty()) null else LyricsResult(plain, synced = false, source = "Archivo .lrc")
        }
    }

    private fun indexFor(treeUri: Uri): Map<String, Uri> {
        val key = treeUri.toString()
        synchronized(indexLock) {
            cachedIndex?.let { if (cachedTree == key) return it }
        }

        val index = mutableMapOf<String, Uri>()
        runCatching { scan(treeUri, index, depth = 0) }

        synchronized(indexLock) {
            cachedTree = key
            cachedIndex = index
        }
        return index
    }

    private fun scan(treeUri: Uri, out: MutableMap<String, Uri>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        scanChildren(treeUri, DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId), out, depth)
    }

    private fun scanChildren(
        treeUri: Uri,
        childrenUri: Uri,
        out: MutableMap<String, Uri>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val displayName = cursor.getString(1) ?: continue
                val mimeType = cursor.getString(2)

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    scanChildren(
                        treeUri,
                        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId),
                        out,
                        depth + 1
                    )
                } else if (displayName.lowercase().endsWith(".lrc")) {
                    val key = displayName.substringBeforeLast('.').trim().lowercase()
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    out.putIfAbsent(key, documentUri)
                }
            }
        }
    }

    private fun matchTrack(index: Map<String, Uri>, track: LocalTrack): Uri? {
        if (index.isEmpty()) return null
        val title = track.title.trim().lowercase()
        val artist = track.artist.trim().lowercase()

        listOf(title, "$artist - $title", "$title - $artist").forEach { candidate ->
            index[candidate]?.let { return it }
        }

        return index.entries
            .filter { LyricsNormalizer.fileNameMatches(it.key, track.title, track.artist) }
            .minByOrNull { it.key.length }
            ?.value
    }

    private fun readText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    private companion object {
        const val PREFS_NAME = "verso_lyrics_prefs"
        const val KEY_TREE_URI = "lrc_tree_uri"
        const val KEY_TREE_NAME = "lrc_tree_name"
        const val MAX_DEPTH = 8
    }
}
