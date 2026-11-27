package com.verso.lyrics.model

import android.net.Uri

/** Representa una pista de audio local indexada desde MediaStore. */
data class LocalTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val contentUri: Uri,
    val albumArtUri: Uri,
    val path: String = "",
    val folderPath: String = ""
)
