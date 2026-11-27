package com.verso.lyrics.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.palette.graphics.Palette
import com.verso.lyrics.BuildConfig
import com.verso.lyrics.data.DeviceAudioRepository
import com.verso.lyrics.data.LyricsRepository
import com.verso.lyrics.export.ExportResult
import com.verso.lyrics.export.VideoExporter
import com.verso.lyrics.model.LocalTrack
import com.verso.lyrics.model.LyricFont
import com.verso.lyrics.model.LyricLine
import com.verso.lyrics.model.LyricStyle
import com.verso.lyrics.model.LyricsCandidate
import com.verso.lyrics.model.LyricsFetchResult
import com.verso.lyrics.model.LyricsState
import com.verso.lyrics.model.StoryConfig
import com.verso.lyrics.update.AppUpdateManager
import com.verso.lyrics.update.GithubReleaseInfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ExportUiState {
    data object Idle : ExportUiState
    data class Exporting(val progress: Float) : ExportUiState
    data class Success(val result: ExportResult) : ExportUiState
    data class Error(val message: String) : ExportUiState
}

sealed interface CandidatesUiState {
    data object Hidden : CandidatesUiState
    data object Loading : CandidatesUiState
    data class Ready(val candidates: List<LyricsCandidate>, val hasError: Boolean = false) : CandidatesUiState
}

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val currentVersion: String) : UpdateUiState
    data class Available(val release: GithubReleaseInfo) : UpdateUiState
    data class Downloading(val release: GithubReleaseInfo, val progress: Float) : UpdateUiState
    data class ReadyToInstall(val release: GithubReleaseInfo, val apkFile: File) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val audioRepo = DeviceAudioRepository(appContext)
    private val lyricsRepo = LyricsRepository(appContext)
    private val videoExporter = VideoExporter(appContext)

    private val player: ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
    }

    private val _config = MutableStateFlow(StoryConfig())
    val config: StateFlow<StoryConfig> = _config.asStateFlow()

    private val _lyricsState = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val lyricsState: StateFlow<LyricsState> = _lyricsState.asStateFlow()

    private val _tracks = MutableStateFlow<List<LocalTrack>>(emptyList())
    val tracks: StateFlow<List<LocalTrack>> = _tracks.asStateFlow()

    private val _isLoadingTracks = MutableStateFlow(false)
    val isLoadingTracks: StateFlow<Boolean> = _isLoadingTracks.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _photoBitmap = MutableStateFlow<Bitmap?>(null)
    val photoBitmap: StateFlow<Bitmap?> = _photoBitmap.asStateFlow()

    private val _albumArtBitmap = MutableStateFlow<Bitmap?>(null)
    val albumArtBitmap: StateFlow<Bitmap?> = _albumArtBitmap.asStateFlow()

    private val _paletteColors = MutableStateFlow(
        listOf(0xFFFFFFFF.toInt(), 0xFFFF5C8A.toInt(), 0xFFFFD166.toInt(), 0xFF72EFDD.toInt(), 0xFFA29BFE.toInt())
    )
    val paletteColors: StateFlow<List<Int>> = _paletteColors.asStateFlow()

    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    private val _candidatesState = MutableStateFlow<CandidatesUiState>(CandidatesUiState.Hidden)
    val candidatesState: StateFlow<CandidatesUiState> = _candidatesState.asStateFlow()

    private val _lrcFolderName = MutableStateFlow(lyricsRepo.localFolderName())
    val lrcFolderName: StateFlow<String?> = _lrcFolderName.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _hasPendingUpdate = MutableStateFlow(false)
    val hasPendingUpdate: StateFlow<Boolean> = _hasPendingUpdate.asStateFlow()

    private var exportJob: Job? = null
    private var updateJob: Job? = null

    init {
        checkForUpdates(silent = true)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
        })

        viewModelScope.launch {
            while (isActive) {
                val cfg = _config.value
                if (cfg.track != null) {
                    val pos = player.currentPosition
                    val clipEnd = (cfg.clipStartMs + cfg.clipDurationMs).coerceAtMost(cfg.track.durationMs)
                    if (_isPlaying.value && clipEnd > cfg.clipStartMs && pos >= clipEnd) {
                        player.seekTo(cfg.clipStartMs)
                        _currentPositionMs.value = cfg.clipStartMs
                    } else {
                        _currentPositionMs.value = pos
                    }
                }
                delay(33L)
            }
        }
    }

    fun loadDeviceTracks() {
        viewModelScope.launch {
            _isLoadingTracks.value = true
            _tracks.value = runCatching { audioRepo.loadTracks() }.getOrDefault(emptyList())
            _isLoadingTracks.value = false
        }
    }

    fun selectPhoto(uri: Uri) {
        _config.update { it.copy(photoUri = uri) }
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeBitmap(uri, 1080, 1920) }
            _photoBitmap.value = bmp
            if (bmp != null) {
                val extracted = withContext(Dispatchers.Default) {
                    val palette = Palette.from(bmp).generate()
                    listOfNotNull(
                        0xFFFFFFFF.toInt(),
                        palette.vibrantSwatch?.rgb,
                        palette.lightVibrantSwatch?.rgb,
                        palette.dominantSwatch?.rgb,
                        palette.mutedSwatch?.rgb,
                        0xFFFF5C8A.toInt(),
                        0xFFFFD166.toInt()
                    ).distinct()
                }
                _paletteColors.value = extracted
                extracted.getOrNull(1)?.let { firstAccent ->
                    _config.update { it.copy(accentColor = firstAccent) }
                }
            }
        }
    }

    fun selectTrack(track: LocalTrack) {
        val savedOffset = lyricsRepo.getTrackOffset(track)
        val clipDur = _config.value.clipDurationMs.coerceAtMost(track.durationMs.coerceAtLeast(5_000L))
        _config.update {
            it.copy(
                track = track,
                clipStartMs = 0L,
                clipDurationMs = clipDur,
                lyricsOffsetMs = savedOffset
            )
        }
        _currentPositionMs.value = 0L
        player.setMediaItem(MediaItem.fromUri(track.contentUri))
        player.prepare()
        player.seekTo(0L)
        player.play()

        viewModelScope.launch {
            _albumArtBitmap.value = withContext(Dispatchers.IO) { decodeBitmap(track.albumArtUri, 256, 256) }
        }
        fetchLyricsForTrack(track, forceOnline = false)
    }

    fun fetchLyricsForTrack(track: LocalTrack, forceOnline: Boolean = false) {
        viewModelScope.launch {
            _lyricsState.value = LyricsState.Loading
            when (val res = lyricsRepo.fetch(track, allowOnline = true, forceOnline = forceOnline)) {
                is LyricsFetchResult.Found -> {
                    _lyricsState.value = LyricsState.Available(res.lines, res.synced, res.source)
                }
                LyricsFetchResult.NotFound -> _lyricsState.value = LyricsState.NotFound
                is LyricsFetchResult.Error -> _lyricsState.value = LyricsState.Error
            }
        }
    }

    fun togglePlayPause() {
        if (_config.value.track == null) return
        if (player.isPlaying) {
            player.pause()
        } else {
            val start = _config.value.clipStartMs
            val end = start + _config.value.clipDurationMs
            if (player.currentPosition < start || player.currentPosition >= end) {
                player.seekTo(start)
            }
            player.play()
        }
    }

    fun setClipStart(startMs: Long) {
        val track = _config.value.track ?: return
        val maxStart = (track.durationMs - _config.value.clipDurationMs).coerceAtLeast(0L)
        val clamped = startMs.coerceIn(0L, maxStart)
        _config.update { it.copy(clipStartMs = clamped) }
        player.seekTo(clamped)
        _currentPositionMs.value = clamped
    }

    fun setClipDuration(durationMs: Long) {
        val track = _config.value.track
        val clampedDur = if (track != null) {
            durationMs.coerceIn(5_000L, track.durationMs.coerceAtLeast(5_000L))
        } else {
            durationMs
        }
        val maxStart = if (track != null) (track.durationMs - clampedDur).coerceAtLeast(0L) else 0L
        _config.update {
            it.copy(
                clipDurationMs = clampedDur,
                clipStartMs = it.clipStartMs.coerceIn(0L, maxStart)
            )
        }
    }

    fun adjustLyricsOffset(deltaMs: Int) {
        val track = _config.value.track
        val next = (_config.value.lyricsOffsetMs + deltaMs).coerceIn(-10_000, 10_000)
        _config.update { it.copy(lyricsOffsetMs = next) }
        if (track != null) lyricsRepo.setTrackOffset(track, next)
    }

    fun resetLyricsOffset() {
        val track = _config.value.track
        _config.update { it.copy(lyricsOffsetMs = 0) }
        if (track != null) lyricsRepo.setTrackOffset(track, 0)
    }

    fun setStyle(style: LyricStyle) = _config.update { it.copy(style = style) }
    fun setFont(font: LyricFont) = _config.update { it.copy(font = font) }
    fun setTextColor(color: Int) = _config.update { it.copy(textColor = color) }
    fun setAccentColor(color: Int) = _config.update { it.copy(accentColor = color) }
    fun setBackgroundDim(dim: Float) = _config.update { it.copy(backgroundDim = dim.coerceIn(0f, 0.85f)) }
    fun toggleSongBadge() = _config.update { it.copy(showSongBadge = !it.showSongBadge) }

    fun updateLyricTransform(dxFrac: Float, dyFrac: Float, scaleFactor: Float, rotationDelta: Float) {
        _config.update { cfg ->
            cfg.copy(
                lyricPosX = (cfg.lyricPosX + dxFrac).coerceIn(0.15f, 0.85f),
                lyricPosY = (cfg.lyricPosY + dyFrac).coerceIn(0.18f, 0.85f),
                lyricScale = (cfg.lyricScale * scaleFactor).coerceIn(0.55f, 2.0f),
                lyricRotationDeg = (cfg.lyricRotationDeg + rotationDelta).coerceIn(-45f, 45f)
            )
        }
    }

    fun setLrcFolder(uri: Uri) {
        lyricsRepo.setLocalFolder(uri)
        _lrcFolderName.value = lyricsRepo.localFolderName()
        _config.value.track?.let { fetchLyricsForTrack(it, forceOnline = false) }
    }

    fun openCandidatesPicker() {
        val track = _config.value.track ?: return
        _candidatesState.value = CandidatesUiState.Loading
        viewModelScope.launch {
            val result = lyricsRepo.searchTopCandidates(track)
            _candidatesState.value = result.fold(
                onSuccess = { CandidatesUiState.Ready(it) },
                onFailure = { CandidatesUiState.Ready(emptyList(), hasError = true) }
            )
        }
    }

    fun searchCandidatesCustom(title: String, artist: String) {
        val track = _config.value.track ?: return
        _candidatesState.value = CandidatesUiState.Loading
        viewModelScope.launch {
            val result = lyricsRepo.searchCandidatesByQuery(title, artist, track.durationMs)
            _candidatesState.value = result.fold(
                onSuccess = { CandidatesUiState.Ready(it) },
                onFailure = { CandidatesUiState.Ready(emptyList(), hasError = true) }
            )
        }
    }

    fun selectCandidate(candidate: LyricsCandidate) {
        val track = _config.value.track ?: return
        val saved = lyricsRepo.saveSelectedCandidate(track, candidate)
        _lyricsState.value = LyricsState.Available(saved.lines, saved.synced, saved.source)
        _candidatesState.value = CandidatesUiState.Hidden
    }

    fun dismissCandidates() {
        _candidatesState.value = CandidatesUiState.Hidden
    }

    fun startExport() {
        val cfg = _config.value
        if (cfg.track == null) {
            _exportState.value = ExportUiState.Error("Primero elige una canción de tu dispositivo.")
            return
        }
        val lines: List<LyricLine> = when (val st = _lyricsState.value) {
            is LyricsState.Available -> st.lines
            else -> emptyList()
        }
        player.pause()
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _exportState.value = ExportUiState.Exporting(0f)
            runCatching {
                videoExporter.exportStory(cfg, lines) { p ->
                    _exportState.value = ExportUiState.Exporting(p)
                }
            }.onSuccess { result ->
                _exportState.value = ExportUiState.Success(result)
            }.onFailure { err ->
                _exportState.value = ExportUiState.Error(err.message ?: "Error al exportar el video")
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        _exportState.value = ExportUiState.Idle
    }

    fun dismissExportDialog() {
        _exportState.value = ExportUiState.Idle
    }

    fun checkForUpdates(silent: Boolean = false) {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            if (!silent) {
                _updateState.value = UpdateUiState.Checking
            }
            AppUpdateManager.checkLatestRelease(BuildConfig.VERSION_NAME)
                .onSuccess { release ->
                    _hasPendingUpdate.value = release.isNewer
                    if (release.isNewer) {
                        _updateState.value = UpdateUiState.Available(release)
                    } else if (!silent) {
                        _updateState.value = UpdateUiState.UpToDate(BuildConfig.VERSION_NAME)
                    }
                }
                .onFailure { err ->
                    if (!silent) {
                        _updateState.value = UpdateUiState.Error(
                            err.message ?: "No se pudo comprobar si hay actualizaciones."
                        )
                    }
                }
        }
    }

    fun downloadUpdate(release: GithubReleaseInfo) {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _updateState.value = UpdateUiState.Downloading(release, 0f)
            AppUpdateManager.downloadApk(appContext, release) { progress ->
                _updateState.value = UpdateUiState.Downloading(release, progress)
            }.onSuccess { apkFile ->
                _updateState.value = UpdateUiState.ReadyToInstall(release, apkFile)
            }.onFailure { err ->
                _updateState.value = UpdateUiState.Error(
                    err.message ?: "Error al descargar la actualización."
                )
            }
        }
    }

    fun dismissUpdateDialog() {
        updateJob?.cancel()
        _updateState.value = UpdateUiState.Idle
    }

    private fun decodeBitmap(uri: Uri, reqW: Int, reqH: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(appContext.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                if (w > reqW || h > reqH) {
                    val ratio = maxOf(w.toFloat() / reqW, h.toFloat() / reqH)
                    decoder.setTargetSize((w / ratio).toInt().coerceAtLeast(1), (h / ratio).toInt().coerceAtLeast(1))
                }
            }
        } else {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / sample > reqW * 2 || opts.outHeight / sample > reqH * 2) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        }
    }.getOrNull()

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
