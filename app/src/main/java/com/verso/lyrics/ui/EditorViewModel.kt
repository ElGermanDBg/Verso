package com.verso.lyrics.ui

import android.app.Application
import android.content.Context
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
import com.verso.lyrics.audio.AudioBeatAnalyzer
import com.verso.lyrics.audio.BeatEnvelope
import com.verso.lyrics.data.DeviceAudioRepository
import com.verso.lyrics.data.LyricsRepository
import com.verso.lyrics.export.ExportResult
import com.verso.lyrics.export.VideoExporter
import com.verso.lyrics.model.BrushStyle
import com.verso.lyrics.model.DrawingPoint
import com.verso.lyrics.model.DrawingStroke
import com.verso.lyrics.model.LocalTrack
import com.verso.lyrics.model.LyricAlignment
import com.verso.lyrics.model.LyricAnimation
import com.verso.lyrics.model.LyricEffect
import com.verso.lyrics.model.LyricFont
import com.verso.lyrics.model.LyricLine
import com.verso.lyrics.model.LyricStyle
import com.verso.lyrics.model.LyricsCandidate
import com.verso.lyrics.model.LyricsFetchResult
import com.verso.lyrics.model.LyricsState
import com.verso.lyrics.model.PhotoFilter
import com.verso.lyrics.model.StoryConfig
import com.verso.lyrics.render.SubjectSegmenter
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

data class BrushConfig(
    val style: BrushStyle = BrushStyle.CHALK,
    val color: Int = 0xFFFFFFFF.toInt(),
    val widthNorm: Float = 0.024f
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val prefs = appContext.getSharedPreferences("verso_prefs", Context.MODE_PRIVATE)
    private val audioRepo = DeviceAudioRepository(appContext)
    private val lyricsRepo = LyricsRepository(appContext)
    private val videoExporter = VideoExporter(appContext)

    private val player: ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
    }

    private val _config = MutableStateFlow(loadSavedConfig())
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

    private val _brushConfig = MutableStateFlow(BrushConfig())
    val brushConfig: StateFlow<BrushConfig> = _brushConfig.asStateFlow()

    private val _activeStroke = MutableStateFlow<DrawingStroke?>(null)
    val activeStroke: StateFlow<DrawingStroke?> = _activeStroke.asStateFlow()

    private val _foregroundBitmap = MutableStateFlow<Bitmap?>(null)
    val foregroundBitmap: StateFlow<Bitmap?> = _foregroundBitmap.asStateFlow()

    private val _subjectMaskBitmap = MutableStateFlow<Bitmap?>(null)
    val subjectMaskBitmap: StateFlow<Bitmap?> = _subjectMaskBitmap.asStateFlow()

    private val _maskVersion = MutableStateFlow(0)
    val maskVersion: StateFlow<Int> = _maskVersion.asStateFlow()

    private val _maskAddMode = MutableStateFlow(true)
    val maskAddMode: StateFlow<Boolean> = _maskAddMode.asStateFlow()

    private val _maskBrushRadiusNorm = MutableStateFlow(0.055f)
    val maskBrushRadiusNorm: StateFlow<Float> = _maskBrushRadiusNorm.asStateFlow()

    private val _isSegmentingSubject = MutableStateFlow(false)
    val isSegmentingSubject: StateFlow<Boolean> = _isSegmentingSubject.asStateFlow()

    private val _segmentationError = MutableStateFlow<String?>(null)
    val segmentationError: StateFlow<String?> = _segmentationError.asStateFlow()

    private val _maskSmartBrush = MutableStateFlow(true)
    val maskSmartBrush: StateFlow<Boolean> = _maskSmartBrush.asStateFlow()

    private val _canUndoMask = MutableStateFlow(false)
    val canUndoMask: StateFlow<Boolean> = _canUndoMask.asStateFlow()

    private val maskUndoStack = ArrayDeque<Bitmap>()

    private val _beatEnvelope = MutableStateFlow<BeatEnvelope?>(null)
    val beatEnvelope: StateFlow<BeatEnvelope?> = _beatEnvelope.asStateFlow()

    private val _isAnalyzingBeat = MutableStateFlow(false)
    val isAnalyzingBeat: StateFlow<Boolean> = _isAnalyzingBeat.asStateFlow()

    private var exportJob: Job? = null
    private var updateJob: Job? = null
    private var segmentJob: Job? = null
    private var beatJob: Job? = null

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
                _foregroundBitmap.value = null
                _subjectMaskBitmap.value = null
                clearMaskUndoHistory()
                _maskVersion.value += 1
                _segmentationError.value = null
                if (_config.value.lyricsBehindSubject) {
                    runSegmentation(bmp)
                }
            } else {
                _foregroundBitmap.value = null
                _subjectMaskBitmap.value = null
            }
        }
    }

    fun toggleLyricsBehindSubject() {
        val currentlyOn = _config.value.lyricsBehindSubject
        if (currentlyOn) {
            segmentJob?.cancel()
            _isSegmentingSubject.value = false
            _segmentationError.value = null
            _config.update { it.copy(lyricsBehindSubject = false) }
            return
        }
        val bmp = _photoBitmap.value ?: return
        _segmentationError.value = null
        if (_foregroundBitmap.value != null) {
            _config.update { it.copy(lyricsBehindSubject = true) }
        } else {
            runSegmentation(bmp)
        }
    }

    fun ensureMaskReadyForManualEdit() {
        val photo = _photoBitmap.value ?: return
        val current = _subjectMaskBitmap.value
        if (current == null || current.isRecycled) {
            _subjectMaskBitmap.value = SubjectSegmenter.createEmptyMask(photo)
            _maskVersion.value += 1
        } else if (!current.isMutable) {
            _subjectMaskBitmap.value = current.copy(Bitmap.Config.ARGB_8888, true)
                ?: SubjectSegmenter.createEmptyMask(photo)
            _maskVersion.value += 1
        }
        _segmentationError.value = null
        _config.update { it.copy(lyricsBehindSubject = true) }
    }

    fun setMaskAddMode(add: Boolean) {
        _maskAddMode.value = add
    }

    fun setMaskSmartBrush(enabled: Boolean) {
        _maskSmartBrush.value = enabled
    }

    fun setMaskBrushRadius(norm: Float) {
        _maskBrushRadiusNorm.value = norm.coerceIn(0.01f, 0.12f)
    }

    fun pushMaskUndoSnapshot() {
        val current = _subjectMaskBitmap.value ?: return
        if (current.isRecycled) return
        val snapshot = current.copy(Bitmap.Config.ARGB_8888, true) ?: return
        maskUndoStack.addLast(snapshot)
        while (maskUndoStack.size > 10) {
            val oldest = maskUndoStack.removeFirst()
            if (!oldest.isRecycled) oldest.recycle()
        }
        _canUndoMask.value = maskUndoStack.isNotEmpty()
    }

    fun undoMaskStroke() {
        if (maskUndoStack.isEmpty()) return
        val prev = maskUndoStack.removeLast()
        _subjectMaskBitmap.value = prev
        _canUndoMask.value = maskUndoStack.isNotEmpty()
        _maskVersion.value += 1
        commitMaskToForeground()
    }

    private fun clearMaskUndoHistory() {
        while (maskUndoStack.isNotEmpty()) {
            val bmp = maskUndoStack.removeFirst()
            if (!bmp.isRecycled) bmp.recycle()
        }
        _canUndoMask.value = false
    }

    fun paintMaskStroke(x1: Float, y1: Float, x2: Float, y2: Float, radiusMaskPx: Float) {
        val mask = _subjectMaskBitmap.value ?: return
        val photo = _photoBitmap.value
        if (_maskSmartBrush.value && photo != null) {
            SubjectSegmenter.paintSmartMaskSegment(
                sourceBitmap = photo,
                maskBitmap = mask,
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2,
                radiusPx = radiusMaskPx,
                addMode = _maskAddMode.value
            )
        } else {
            SubjectSegmenter.paintMaskSegment(
                maskBitmap = mask,
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2,
                radiusPx = radiusMaskPx,
                addMode = _maskAddMode.value
            )
        }
        _maskVersion.value += 1
    }

    fun commitMaskToForeground() {
        val photo = _photoBitmap.value ?: return
        val mask = _subjectMaskBitmap.value ?: return
        viewModelScope.launch {
            val fg = runCatching {
                withContext(Dispatchers.Default) {
                    SubjectSegmenter.applyMask(photo, mask)
                }
            }.getOrNull() ?: return@launch
            _foregroundBitmap.value = fg
            _config.update { it.copy(lyricsBehindSubject = true) }
        }
    }

    fun resetMaskWithAi() {
        val photo = _photoBitmap.value ?: return
        pushMaskUndoSnapshot()
        runSegmentation(photo)
    }

    fun clearMask() {
        val photo = _photoBitmap.value ?: return
        pushMaskUndoSnapshot()
        val empty = SubjectSegmenter.createEmptyMask(photo)
        _subjectMaskBitmap.value = empty
        _maskVersion.value += 1
        commitMaskToForeground()
    }

    private fun runSegmentation(bmp: Bitmap) {
        segmentJob?.cancel()
        segmentJob = viewModelScope.launch {
            _isSegmentingSubject.value = true
            _segmentationError.value = null
            val result = SubjectSegmenter.extractMaskAndForeground(bmp)
            _isSegmentingSubject.value = false
            if (result != null) {
                _subjectMaskBitmap.value = result.maskBitmap
                _foregroundBitmap.value = result.foregroundBitmap
                _maskVersion.value += 1
                _config.update { it.copy(lyricsBehindSubject = true) }
            } else {
                _subjectMaskBitmap.value = null
                _foregroundBitmap.value = null
                _config.update { it.copy(lyricsBehindSubject = false) }
                _segmentationError.value = "No se detectó persona. Puedes pintarla manualmente."
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
        _beatEnvelope.value = null
        player.setMediaItem(MediaItem.fromUri(track.contentUri))
        player.prepare()
        player.seekTo(0L)
        player.play()

        viewModelScope.launch {
            _albumArtBitmap.value = withContext(Dispatchers.IO) { decodeBitmap(track.albumArtUri, 256, 256) }
        }
        fetchLyricsForTrack(track, forceOnline = false)
        if (_config.value.beatPulseEnabled) {
            analyzeBeatForCurrentClip(debounceMs = 0L)
        }
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
        if (_config.value.beatPulseEnabled) {
            analyzeBeatForCurrentClip(debounceMs = 250L)
        }
    }

    fun nudgeClipStart(deltaMs: Long) {
        setClipStart(_config.value.clipStartMs + deltaMs)
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
        if (_config.value.beatPulseEnabled) {
            analyzeBeatForCurrentClip(debounceMs = 250L)
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

    fun setStyle(style: LyricStyle) {
        _config.update { it.copy(style = style) }
        prefs.edit().putString("style", style.name).apply()
    }

    fun setAnimation(animation: LyricAnimation) {
        _config.update { it.copy(animation = animation) }
        prefs.edit().putString("animation", animation.name).apply()
    }

    fun setEffect(effect: LyricEffect) {
        _config.update { it.copy(effect = effect) }
        prefs.edit().putString("effect", effect.name).apply()
    }

    fun setFont(font: LyricFont) {
        _config.update { it.copy(font = font) }
        prefs.edit().putString("font", font.name).apply()
    }

    fun setPhotoFilter(filter: PhotoFilter) {
        _config.update { it.copy(photoFilter = filter) }
        prefs.edit().putString("photoFilter", filter.name).apply()
    }

    fun setTextAlignment(alignment: LyricAlignment) {
        _config.update { it.copy(textAlignment = alignment) }
        prefs.edit().putString("textAlignment", alignment.name).apply()
    }

    fun setLyricScale(scale: Float) {
        _config.update { it.copy(lyricScale = scale.coerceIn(0.6f, 1.8f)) }
    }

    fun toggleBeatPulse() {
        val next = !_config.value.beatPulseEnabled
        _config.update { it.copy(beatPulseEnabled = next) }
        prefs.edit().putBoolean("beatPulseEnabled", next).apply()
        if (next) {
            analyzeBeatForCurrentClip(debounceMs = 0L)
        } else {
            beatJob?.cancel()
            _isAnalyzingBeat.value = false
        }
    }

    private fun analyzeBeatForCurrentClip(debounceMs: Long = 250L) {
        val cfg = _config.value
        val track = cfg.track ?: return
        val startMs = cfg.clipStartMs.coerceAtLeast(0L)
        val durationMs = cfg.clipDurationMs.coerceIn(3_000L, 60_000L)
        beatJob?.cancel()
        beatJob = viewModelScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            _isAnalyzingBeat.value = true
            val envelope = AudioBeatAnalyzer.analyzeClip(getApplication(), track.contentUri, startMs, durationMs)
            _beatEnvelope.value = envelope
            _isAnalyzingBeat.value = false
        }
    }

    private fun loadSavedConfig(): StoryConfig = StoryConfig(
        style = enumOrDefault(prefs.getString("style", null), LyricStyle.SCROLL),
        animation = enumOrDefault(prefs.getString("animation", null), LyricAnimation.FADE_UP),
        effect = enumOrDefault(prefs.getString("effect", null), LyricEffect.NONE),
        font = enumOrDefault(prefs.getString("font", null), LyricFont.MODERNA),
        photoFilter = enumOrDefault(prefs.getString("photoFilter", null), PhotoFilter.NONE),
        textAlignment = enumOrDefault(prefs.getString("textAlignment", null), LyricAlignment.CENTER),
        showSongBadge = prefs.getBoolean("showSongBadge", true),
        beatPulseEnabled = prefs.getBoolean("beatPulseEnabled", false)
    )

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        raw?.let { name -> runCatching { enumValueOf<T>(name) }.getOrNull() } ?: fallback

    fun setBrushStyle(style: BrushStyle) = _brushConfig.update { it.copy(style = style) }
    fun setBrushColor(color: Int) = _brushConfig.update { it.copy(color = color) }
    fun setBrushWidth(widthNorm: Float) = _brushConfig.update { it.copy(widthNorm = widthNorm.coerceIn(0.008f, 0.06f)) }

    fun startStroke(xNorm: Float, yNorm: Float) {
        val brush = _brushConfig.value
        _activeStroke.value = DrawingStroke(
            points = listOf(DrawingPoint(xNorm.coerceIn(0f, 1f), yNorm.coerceIn(0f, 1f))),
            color = brush.color,
            widthNorm = brush.widthNorm,
            style = brush.style
        )
    }

    fun appendStrokePoint(xNorm: Float, yNorm: Float) {
        _activeStroke.update { current ->
            current?.copy(
                points = current.points + DrawingPoint(xNorm.coerceIn(0f, 1f), yNorm.coerceIn(0f, 1f))
            )
        }
    }

    fun finishStroke() {
        val finished = _activeStroke.value ?: return
        _activeStroke.value = null
        if (finished.points.isNotEmpty()) {
            _config.update { it.copy(strokes = it.strokes + finished) }
        }
    }

    fun undoLastStroke() {
        _config.update { cfg ->
            if (cfg.strokes.isEmpty()) cfg else cfg.copy(strokes = cfg.strokes.dropLast(1))
        }
    }

    fun clearAllStrokes() {
        _activeStroke.value = null
        _config.update { it.copy(strokes = emptyList()) }
    }
    fun setTextColor(color: Int) = _config.update { it.copy(textColor = color) }
    fun setAccentColor(color: Int) = _config.update { it.copy(accentColor = color) }
    fun setBackgroundDim(dim: Float) = _config.update { it.copy(backgroundDim = dim.coerceIn(0f, 0.85f)) }
    fun toggleSongBadge() = _config.update { it.copy(showSongBadge = !it.showSongBadge) }

    fun resetLyricTransform() = _config.update {
        it.copy(lyricPosX = 0.5f, lyricPosY = 0.55f, lyricScale = 1.0f, lyricRotationDeg = 0f)
    }

    fun updateLyricTransform(dxFrac: Float, dyFrac: Float, scaleFactor: Float, rotationDelta: Float) {
        _config.update { cfg ->
            val rawX = (cfg.lyricPosX + dxFrac).coerceIn(0.15f, 0.85f)
            val rawY = (cfg.lyricPosY + dyFrac).coerceIn(0.18f, 0.85f)
            val snappedX = if (kotlin.math.abs(rawX - 0.5f) < 0.015f) 0.5f else rawX
            val snappedY = if (kotlin.math.abs(rawY - 0.55f) < 0.015f) 0.55f else rawY
            cfg.copy(
                lyricPosX = snappedX,
                lyricPosY = snappedY,
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
                videoExporter.exportStory(cfg, lines, _foregroundBitmap.value, _beatEnvelope.value) { p ->
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
        clearMaskUndoHistory()
        player.release()
    }
}
