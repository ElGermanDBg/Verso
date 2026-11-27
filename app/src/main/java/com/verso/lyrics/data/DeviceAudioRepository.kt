package com.verso.lyrics.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.verso.lyrics.model.LocalTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lee las canciones locales del dispositivo desde MediaStore. */
class DeviceAudioRepository(private val context: Context) {

    suspend fun loadTracks(minDurationMs: Long = 5_000L): List<LocalTrack> = withContext(Dispatchers.IO) {
        val collectionUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val folderCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.DATA
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.DATA,
            folderCol
        )

        val selection = "((${MediaStore.Audio.Media.IS_MUSIC} != 0) OR " +
            "(${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%') OR " +
            "(${MediaStore.Audio.Media.DATA} LIKE '%.mp3') OR " +
            "(${MediaStore.Audio.Media.DATA} LIKE '%.flac') OR " +
            "(${MediaStore.Audio.Media.DATA} LIKE '%.m4a') OR " +
            "(${MediaStore.Audio.Media.DATA} LIKE '%.ogg') OR " +
            "(${MediaStore.Audio.Media.DATA} LIKE '%.opus') OR " +
            "(${MediaStore.Audio.Media.DATA} LIKE '%.wav')) AND " +
            "${MediaStore.Audio.Media.DURATION} >= $minDurationMs"

        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
        val albumArtBase = Uri.parse("content://media/external/audio/albumart")
        val result = mutableListOf<LocalTrack>()

        context.contentResolver.query(collectionUri, projection, selection, null, sortOrder)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val durationIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val albumIdIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val folderIdx = cursor.getColumnIndex(folderCol)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val title = if (titleIdx >= 0) cursor.getString(titleIdx)?.takeIf { it.isNotBlank() } ?: "Sin título" else "Sin título"
                val rawArtist = if (artistIdx >= 0) cursor.getString(artistIdx).orEmpty() else ""
                val artist = if (rawArtist.isBlank() || rawArtist.equals("<unknown>", ignoreCase = true)) {
                    "Artista desconocido"
                } else {
                    rawArtist
                }
                val rawAlbum = if (albumIdx >= 0) cursor.getString(albumIdx).orEmpty() else ""
                val album = if (rawAlbum.isBlank() || rawAlbum.equals("<unknown>", ignoreCase = true)) {
                    ""
                } else {
                    rawAlbum
                }
                val duration = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L
                val albumId = if (albumIdIdx >= 0) cursor.getLong(albumIdIdx) else 0L
                val path = if (dataIdx >= 0) cursor.getString(dataIdx).orEmpty() else ""
                val rawFolder = if (folderIdx >= 0) cursor.getString(folderIdx).orEmpty() else ""
                val folderPath = resolveFolder(rawFolder)

                result.add(
                    LocalTrack(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = duration,
                        contentUri = ContentUris.withAppendedId(collectionUri, id),
                        albumArtUri = ContentUris.withAppendedId(albumArtBase, albumId),
                        path = path,
                        folderPath = folderPath
                    )
                )
            }
        }

        result
    }

    private fun resolveFolder(rawFolder: String): String {
        if (rawFolder.isBlank()) return ""
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            rawFolder.trimEnd('/')
        } else {
            val parent = rawFolder.substringBeforeLast('/', "")
            val root = runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
            if (root != null && parent.startsWith(root)) {
                parent.removePrefix(root).trimStart('/')
            } else {
                parent
            }
        }
    }
}
