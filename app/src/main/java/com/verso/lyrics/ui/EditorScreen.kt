package com.verso.lyrics.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verso.lyrics.model.BrushStyle
import com.verso.lyrics.model.LyricAlignment
import com.verso.lyrics.model.LyricAnimation
import com.verso.lyrics.model.LyricEffect
import com.verso.lyrics.model.LyricFont
import com.verso.lyrics.model.LyricLine
import com.verso.lyrics.model.LyricStyle
import com.verso.lyrics.model.LyricsState
import com.verso.lyrics.model.PhotoFilter
import com.verso.lyrics.model.StoryConfig
import com.verso.lyrics.model.activeLyricIndex
import com.verso.lyrics.render.LyricFrameRenderer
import com.verso.lyrics.ui.theme.VersoAccent
import com.verso.lyrics.ui.theme.VersoAccentSecondary
import com.verso.lyrics.ui.theme.VersoBg
import com.verso.lyrics.ui.theme.VersoBorder
import com.verso.lyrics.ui.theme.VersoGold
import com.verso.lyrics.ui.theme.VersoSurface
import com.verso.lyrics.ui.theme.VersoSurfaceElevated
import com.verso.lyrics.ui.theme.VersoTextSecondary
import com.verso.lyrics.update.AppUpdateManager
import com.verso.lyrics.update.GithubReleaseInfo

private enum class DockPanel(val title: String) {
    MUSICA("Música y tramo"),
    ESTILOS("Estilos y presets"),
    TEXTO("Texto y color"),
    FOTO("Foto y fondo"),
    DIBUJAR("Dibujar sobre foto")
}

@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val context = LocalContext.current
    val config by viewModel.config.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val isLoadingTracks by viewModel.isLoadingTracks.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val currentPositionMs by viewModel.currentPositionMs.collectAsStateWithLifecycle()
    val photoBitmap by viewModel.photoBitmap.collectAsStateWithLifecycle()
    val albumArtBitmap by viewModel.albumArtBitmap.collectAsStateWithLifecycle()
    val paletteColors by viewModel.paletteColors.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val candidatesState by viewModel.candidatesState.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val hasPendingUpdate by viewModel.hasPendingUpdate.collectAsStateWithLifecycle()
    val brushConfig by viewModel.brushConfig.collectAsStateWithLifecycle()
    val activeStroke by viewModel.activeStroke.collectAsStateWithLifecycle()
    val foregroundBitmap by viewModel.foregroundBitmap.collectAsStateWithLifecycle()
    val subjectMaskBitmap by viewModel.subjectMaskBitmap.collectAsStateWithLifecycle()
    val maskVersion by viewModel.maskVersion.collectAsStateWithLifecycle()
    val maskAddMode by viewModel.maskAddMode.collectAsStateWithLifecycle()
    val maskBrushRadiusNorm by viewModel.maskBrushRadiusNorm.collectAsStateWithLifecycle()
    val isSegmentingSubject by viewModel.isSegmentingSubject.collectAsStateWithLifecycle()
    val segmentationError by viewModel.segmentationError.collectAsStateWithLifecycle()
    val beatEnvelope by viewModel.beatEnvelope.collectAsStateWithLifecycle()
    val isAnalyzingBeat by viewModel.isAnalyzingBeat.collectAsStateWithLifecycle()
    val maskSmartBrush by viewModel.maskSmartBrush.collectAsStateWithLifecycle()
    val canUndoMask by viewModel.canUndoMask.collectAsStateWithLifecycle()
    val lrcFolderName by viewModel.lrcFolderName.collectAsStateWithLifecycle()

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var showTrackPicker by remember { mutableStateOf(false) }
    var showLinePicker by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var activePanel by remember { mutableStateOf<DockPanel?>(null) }
    var isDraggingLyric by remember { mutableStateOf(false) }
    var isMaskEditMode by remember { mutableStateOf(false) }
    var maskZoom by remember { mutableFloatStateOf(1f) }
    var maskPan by remember { mutableStateOf(Offset.Zero) }
    var activeMaskCursor by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(isMaskEditMode) {
        if (!isMaskEditMode) {
            maskZoom = 1f
            maskPan = Offset.Zero
            activeMaskCursor = null
        }
    }

    LaunchedEffect(activePanel) {
        if (activePanel != DockPanel.FOTO && activePanel != DockPanel.ESTILOS && isMaskEditMode) {
            isMaskEditMode = false
            viewModel.commitMaskToForeground()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) viewModel.loadDeviceTracks()
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.selectPhoto(uri)
    }

    val lrcFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.setLrcFolder(uri)
        }
    }

    val lyricsLines = remember(lyricsState) {
        (lyricsState as? LyricsState.Available)?.lines.orEmpty()
    }

    val isTransformed = config.lyricPosX != 0.5f ||
        config.lyricPosY != 0.55f ||
        config.lyricScale != 1.0f ||
        config.lyricRotationDeg != 0f

    val isDrawingMode = activePanel == DockPanel.DIBUJAR
    val renderConfig = remember(config, activeStroke) {
        if (activeStroke == null) config else config.copy(strokes = config.strokes + activeStroke!!)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VersoBg)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(config.accentColor).copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    radius = 900f
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Cabecera de estudio limpia y dinámica
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Verso",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            brush = Brush.linearGradient(listOf(VersoAccent, VersoGold))
                        )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Row(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(16.dp))
                            .background(VersoSurfaceElevated)
                            .border(1.dp, VersoBorder, RoundedCornerShape(16.dp))
                            .clickable {
                                if (hasAudioPermission && tracks.isEmpty()) viewModel.loadDeviceTracks()
                                showTrackPicker = true
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = if (config.track != null) VersoAccent else VersoTextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = config.track?.let { "${it.title} • ${it.artist}" } ?: "Elegir canción",
                            fontSize = 11.sp,
                            color = if (config.track != null) Color.White else VersoTextSecondary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.07f))
                    ) {
                        Icon(
                            Icons.Rounded.PhotoLibrary,
                            contentDescription = "Elegir foto",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Box {
                        IconButton(
                            onClick = { showTopMenu = true },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.07f))
                        ) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = "Más opciones",
                                tint = if (hasPendingUpdate) VersoAccent else VersoTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (hasPendingUpdate) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 5.dp, end = 5.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(VersoAccent)
                            )
                        }
                        DropdownMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false },
                            containerColor = VersoSurfaceElevated
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Carpeta de letras (.lrc)", color = Color.White, fontSize = 13.sp)
                                        Text(
                                            text = lrcFolderName ?: "Sin carpeta configurada",
                                            color = VersoTextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = VersoGold)
                                },
                                onClick = {
                                    showTopMenu = false
                                    lrcFolderLauncher.launch(null)
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (hasPendingUpdate) "Actualización disponible" else "Buscar actualizaciones",
                                        color = if (hasPendingUpdate) VersoAccent else Color.White,
                                        fontSize = 13.sp
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.SystemUpdate,
                                        contentDescription = null,
                                        tint = if (hasPendingUpdate) VersoAccent else VersoTextSecondary
                                    )
                                },
                                onClick = {
                                    showTopMenu = false
                                    viewModel.checkForUpdates(silent = false)
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                val exportEnabled = config.track != null
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (exportEnabled) {
                                Brush.horizontalGradient(listOf(VersoAccent, VersoAccentSecondary))
                            } else {
                                Brush.horizontalGradient(listOf(VersoSurfaceElevated, VersoSurfaceElevated))
                            }
                        )
                        .clickable(enabled = exportEnabled) { viewModel.startExport() }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.MovieCreation,
                            contentDescription = null,
                            tint = if (exportEnabled) Color.White else VersoTextSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Exportar",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (exportEnabled) Color.White else VersoTextSecondary
                        )
                    }
                }
            }
        }

        // Lienzo inmersivo 9:16
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            val currentMaskZoom by rememberUpdatedState(maskZoom)
            val currentMaskPan by rememberUpdatedState(maskPan)
            val currentMaskBrushRadius by rememberUpdatedState(maskBrushRadiusNorm)
            val currentSubjectMask by rememberUpdatedState(subjectMaskBitmap)
            Box(
                modifier = Modifier
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(VersoSurface)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                    .pointerInput(isDrawingMode, isMaskEditMode) {
                        if (isMaskEditMode) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var isMultiTouch = false
                                var didPaint = false
                                var prevMaskPt: Pair<Float, Float>? = null
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break

                                    if (pressed.size >= 2) {
                                        if (!isMultiTouch) {
                                            isMultiTouch = true
                                            activeMaskCursor = null
                                            if (didPaint) {
                                                viewModel.commitMaskToForeground()
                                                didPaint = false
                                            }
                                            prevMaskPt = null
                                        }
                                        val zoomFactor = event.calculateZoom()
                                        val panDelta = event.calculatePan()
                                        val newZoom = (maskZoom * zoomFactor).coerceIn(1f, 5f)
                                        val maxPanX = size.width * (newZoom - 1f) / 2f
                                        val maxPanY = size.height * (newZoom - 1f) / 2f
                                        maskZoom = newZoom
                                        maskPan = Offset(
                                            x = (maskPan.x + panDelta.x).coerceIn(-maxPanX, maxPanX),
                                            y = (maskPan.y + panDelta.y).coerceIn(-maxPanY, maxPanY)
                                        )
                                        event.changes.forEach { it.consume() }
                                    } else if (!isMultiTouch) {
                                        val change = pressed.first()
                                        val pos = change.position
                                        activeMaskCursor = pos
                                        val mask = currentSubjectMask
                                        if (mask != null && !mask.isRecycled) {
                                            val zoomVal = currentMaskZoom.coerceAtLeast(1f)
                                            val panVal = currentMaskPan
                                            val u = (pos.x - size.width / 2f - panVal.x) / zoomVal + size.width / 2f
                                            val v = (pos.y - size.height / 2f - panVal.y) / zoomVal + size.height / 2f
                                            val rect = LyricFrameRenderer.computeCenterCropRect(
                                                mask.width.toFloat(),
                                                mask.height.toFloat(),
                                                size.width,
                                                size.height
                                            )
                                            val mx = ((u - rect.left) / rect.width()) * mask.width
                                            val my = ((v - rect.top) / rect.height()) * mask.height
                                            val radiusMaskPx = (currentMaskBrushRadius * mask.width) / zoomVal
                                            if (!didPaint) {
                                                viewModel.pushMaskUndoSnapshot()
                                                didPaint = true
                                                viewModel.paintMaskStroke(mx, my, mx + 0.5f, my + 0.5f, radiusMaskPx)
                                            } else {
                                                val prev = prevMaskPt ?: (mx to my)
                                                viewModel.paintMaskStroke(prev.first, prev.second, mx, my, radiusMaskPx)
                                            }
                                            prevMaskPt = mx to my
                                        }
                                        change.consume()
                                    }
                                }
                                activeMaskCursor = null
                                if (didPaint) {
                                    viewModel.commitMaskToForeground()
                                }
                            }
                        } else if (isDrawingMode) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    viewModel.startStroke(offset.x / w, offset.y / h)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    viewModel.appendStrokePoint(change.position.x / w, change.position.y / h)
                                },
                                onDragEnd = { viewModel.finishStroke() },
                                onDragCancel = { viewModel.finishStroke() }
                            )
                        } else {
                            detectTapGestures {
                                if (activePanel != null) {
                                    activePanel = null
                                } else {
                                    viewModel.togglePlayPause()
                                }
                            }
                        }
                    }
                    .pointerInput(isDrawingMode, isMaskEditMode) {
                        if (!isDrawingMode && !isMaskEditMode) {
                            detectTransformGestures { _, pan, zoom, rotation ->
                                isDraggingLyric = true
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                viewModel.updateLyricTransform(
                                    dxFrac = pan.x / w,
                                    dyFrac = pan.y / h,
                                    scaleFactor = zoom,
                                    rotationDelta = rotation
                                )
                            }
                        }
                    }
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = if (isMaskEditMode) maskZoom else 1f
                            scaleY = if (isMaskEditMode) maskZoom else 1f
                            translationX = if (isMaskEditMode) maskPan.x else 0f
                            translationY = if (isMaskEditMode) maskPan.y else 0f
                        }
                ) {
                    // Leer maskVersion asegura redibujo inmediato mientras se pinta la silueta
                    val currentMaskVer = maskVersion
                    val currentBeatPulse = if (renderConfig.beatPulseEnabled) {
                        beatEnvelope?.pulseAt(currentPositionMs) ?: 0f
                    } else {
                        0f
                    }
                    drawIntoCanvas { canvas ->
                        LyricFrameRenderer.drawFrame(
                            context = context,
                            canvas = canvas.nativeCanvas,
                            width = size.width.toInt(),
                            height = size.height.toInt(),
                            config = renderConfig,
                            lines = lyricsLines,
                            positionMs = currentPositionMs,
                            photoBitmap = photoBitmap,
                            albumArtBitmap = albumArtBitmap,
                            foregroundBitmap = if (currentMaskVer >= 0) foregroundBitmap else null,
                            maskOverlayBitmap = subjectMaskBitmap,
                            showMaskOverlay = isMaskEditMode,
                            showCenterGuides = isDraggingLyric && !isDrawingMode && !isMaskEditMode,
                            beatPulse = currentBeatPulse
                        )
                    }
                }

                // Guía visual del tamaño exacto del pincel al editar el contorno
                if (isMaskEditMode && activeMaskCursor != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = activeMaskCursor ?: return@Canvas
                        val radiusPx = maskBrushRadiusNorm * size.width
                        val ringColor = if (maskAddMode) Color(0xFFFF5C8A) else Color(0xFF4CC9F0)
                        drawCircle(
                            color = ringColor.copy(alpha = 0.9f),
                            radius = radiusPx,
                            center = center,
                            style = Stroke(width = 2.dp.toPx())
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.85f),
                            radius = 2.dp.toPx(),
                            center = center
                        )
                    }
                }

                // Indicador de modo dibujo o edición de silueta sobre la foto
                if (isDrawingMode || isMaskEditMode) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isMaskEditMode) "2 dedos: zoom • 1 dedo: pintar" else "Traza sobre la foto",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (isMaskEditMode && maskZoom > 1.05f) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${String.format(java.util.Locale.US, "%.1f", maskZoom)}x • 1x",
                                color = VersoGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    maskZoom = 1f
                                    maskPan = Offset.Zero
                                }
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Listo",
                            color = VersoAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                if (isMaskEditMode) {
                                    isMaskEditMode = false
                                    viewModel.commitMaskToForeground()
                                } else {
                                    activePanel = null
                                }
                            }
                        )
                    }
                }

                // Onboarding visual directo si falta foto o canción
                if (config.photoUri == null || config.track == null) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        OnboardingStepCard(
                            icon = Icons.Rounded.PhotoLibrary,
                            title = if (config.photoUri == null) "1. Elegir foto" else "Foto lista",
                            completed = config.photoUri != null,
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        )
                        OnboardingStepCard(
                            icon = Icons.Rounded.MusicNote,
                            title = config.track?.title ?: "2. Elegir canción",
                            completed = config.track != null,
                            onClick = {
                                if (hasAudioPermission && tracks.isEmpty()) viewModel.loadDeviceTracks()
                                showTrackPicker = true
                            }
                        )
                    }
                }

                // Rail flotante de acceso rápido sobre el lienzo (3D, Contorno, Latido, Centrar)
                if (!isMaskEditMode && !isDrawingMode && (config.photoUri != null || config.track != null)) {
                    val railBeatPulse = if (config.beatPulseEnabled) {
                        beatEnvelope?.pulseAt(currentPositionMs) ?: 0f
                    } else {
                        0f
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (photoBitmap != null) {
                            FloatingCanvasPillButton(
                                icon = Icons.Rounded.Layers,
                                label = "3D",
                                active = config.lyricsBehindSubject,
                                onClick = { if (!isSegmentingSubject) viewModel.toggleLyricsBehindSubject() }
                            )
                            if (config.lyricsBehindSubject) {
                                FloatingCanvasPillButton(
                                    icon = Icons.Rounded.Tune,
                                    label = "Contorno",
                                    active = true,
                                    activeColor = VersoGold,
                                    onClick = {
                                        activePanel = DockPanel.FOTO
                                        viewModel.ensureMaskReadyForManualEdit()
                                        isMaskEditMode = true
                                    }
                                )
                            }
                        }
                        if (config.track != null) {
                            FloatingCanvasPillButton(
                                icon = Icons.Rounded.GraphicEq,
                                label = "Latido",
                                active = config.beatPulseEnabled,
                                activeColor = VersoGold,
                                pulseScale = if (config.beatPulseEnabled) 1f + railBeatPulse * 0.14f else 1f,
                                onClick = viewModel::toggleBeatPulse
                            )
                        }
                        if (isTransformed && config.track != null) {
                            FloatingCanvasPillButton(
                                icon = Icons.Rounded.CenterFocusStrong,
                                label = "Centrar",
                                active = false,
                                onClick = {
                                    isDraggingLyric = false
                                    viewModel.resetLyricTransform()
                                }
                            )
                        }
                    }
                }

                // Mini barra inferior interactiva del lienzo: Play/Pause + slider deslizable
                if (config.track != null) {
                    val clipProgress = if (config.clipDurationMs > 0L) {
                        ((currentPositionMs - config.clipStartMs).toFloat() / config.clipDurationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(10.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.Black.copy(alpha = 0.62f))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(VersoAccent)
                                .clickable { viewModel.togglePlayPause() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Slider(
                            value = clipProgress,
                            onValueChange = { viewModel.seekWithinClip(it) },
                            colors = SliderDefaults.colors(
                                thumbColor = VersoAccent,
                                activeTrackColor = VersoAccent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = formatMs(currentPositionMs),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Panel expandible dinámico con cabecera limpia
        AnimatedVisibility(
            visible = activePanel != null,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(VersoSurface.copy(alpha = 0.96f))
                    .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(22.dp))
            ) {
                activePanel?.let { current ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 6.dp, top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = current.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        IconButton(onClick = { activePanel = null }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Cerrar panel",
                                tint = VersoTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 265.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    AnimatedContent(
                        targetState = activePanel,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "panelContent"
                    ) { panel ->
                        when (panel) {
                            DockPanel.MUSICA -> Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(VersoSurfaceElevated)
                                        .border(1.dp, VersoBorder, RoundedCornerShape(14.dp))
                                        .clickable {
                                            if (hasAudioPermission && tracks.isEmpty()) viewModel.loadDeviceTracks()
                                            showTrackPicker = true
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = VersoAccent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = config.track?.let { "${it.title} • ${it.artist}" }
                                                ?: "Toca para elegir una canción",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (config.track == null) "Elegir" else "Cambiar",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = VersoAccent
                                    )
                                }
                                TramoControls(
                                    config = config,
                                    lyricsState = lyricsState,
                                    currentPositionMs = currentPositionMs,
                                    onClipStartChange = viewModel::setClipStart,
                                    onNudgeStart = viewModel::nudgeClipStart,
                                    onDurationSelect = viewModel::setClipDuration,
                                    onAdjustOffset = viewModel::adjustLyricsOffset,
                                    onResetOffset = viewModel::resetLyricsOffset,
                                    onOpenLinePicker = { showLinePicker = true },
                                    onChangeLyrics = viewModel::openCandidatesPicker
                                )
                            }
                            DockPanel.ESTILOS -> EstiloYAnimacionControls(
                                config = config,
                                hasPhoto = photoBitmap != null,
                                isSegmenting = isSegmentingSubject,
                                segmentationError = segmentationError,
                                isAnalyzingBeat = isAnalyzingBeat,
                                onApplyPreset = viewModel::applyPreset,
                                onSelectStyle = viewModel::setStyle,
                                onSelectAnimation = viewModel::setAnimation,
                                onSelectEffect = viewModel::setEffect,
                                onToggleBehindSubject = viewModel::toggleLyricsBehindSubject,
                                onToggleBeatPulse = viewModel::toggleBeatPulse,
                                onOpenManualMaskEdit = {
                                    activePanel = DockPanel.FOTO
                                    viewModel.ensureMaskReadyForManualEdit()
                                    isMaskEditMode = true
                                },
                                onToggleBadge = viewModel::toggleSongBadge
                            )
                            DockPanel.TEXTO -> TextoYColorControls(
                                config = config,
                                paletteColors = paletteColors,
                                onSelectFont = viewModel::setFont,
                                onSelectAlignment = viewModel::setTextAlignment,
                                onScaleChange = viewModel::setLyricScale,
                                onSelectTextColor = viewModel::setTextColor,
                                onSelectAccentColor = viewModel::setAccentColor
                            )
                            DockPanel.FOTO -> if (isMaskEditMode) {
                                MaskEditControls(
                                    addMode = maskAddMode,
                                    smartBrush = maskSmartBrush,
                                    canUndo = canUndoMask,
                                    brushRadiusNorm = maskBrushRadiusNorm,
                                    isSegmenting = isSegmentingSubject,
                                    onSetAddMode = viewModel::setMaskAddMode,
                                    onToggleSmartBrush = viewModel::setMaskSmartBrush,
                                    onBrushRadiusChange = viewModel::setMaskBrushRadius,
                                    onUndo = viewModel::undoMaskStroke,
                                    onAutoAi = viewModel::resetMaskWithAi,
                                    onClear = viewModel::clearMask,
                                    onDone = {
                                        isMaskEditMode = false
                                        viewModel.commitMaskToForeground()
                                    }
                                )
                            } else {
                                FotoYFondoControls(
                                    config = config,
                                    hasPhoto = photoBitmap != null,
                                    isSegmenting = isSegmentingSubject,
                                    segmentationError = segmentationError,
                                    onPickPhoto = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    onToggleBehindSubject = viewModel::toggleLyricsBehindSubject,
                                    onOpenManualMaskEdit = {
                                        viewModel.ensureMaskReadyForManualEdit()
                                        isMaskEditMode = true
                                    },
                                    onSelectPhotoFilter = viewModel::setPhotoFilter,
                                    onDimChange = viewModel::setBackgroundDim
                                )
                            }
                            DockPanel.DIBUJAR -> DibujarControls(
                                brushConfig = brushConfig,
                                paletteColors = paletteColors,
                                hasStrokes = config.strokes.isNotEmpty(),
                                onSelectBrush = viewModel::setBrushStyle,
                                onSelectColor = viewModel::setBrushColor,
                                onWidthChange = viewModel::setBrushWidth,
                                onUndo = viewModel::undoLastStroke,
                                onClear = viewModel::clearAllStrokes
                            )
                            null -> Unit
                        }
                    }
                }
            }
        }

        // Dock inferior fijo de 5 pestañas equilibradas
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(VersoSurface)
                .border(1.dp, VersoBorder, RoundedCornerShape(22.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockItem(
                icon = Icons.Rounded.MusicNote,
                label = "Música",
                selected = activePanel == DockPanel.MUSICA,
                modifier = Modifier.weight(1f)
            ) {
                activePanel = if (activePanel == DockPanel.MUSICA) null else DockPanel.MUSICA
            }
            DockItem(
                icon = Icons.Rounded.AutoAwesome,
                label = "Estilos",
                selected = activePanel == DockPanel.ESTILOS,
                modifier = Modifier.weight(1f)
            ) {
                activePanel = if (activePanel == DockPanel.ESTILOS) null else DockPanel.ESTILOS
            }
            DockItem(
                icon = Icons.Rounded.TextFields,
                label = "Texto",
                selected = activePanel == DockPanel.TEXTO,
                modifier = Modifier.weight(1f)
            ) {
                activePanel = if (activePanel == DockPanel.TEXTO) null else DockPanel.TEXTO
            }
            DockItem(
                icon = Icons.Rounded.PhotoLibrary,
                label = "Foto",
                selected = activePanel == DockPanel.FOTO,
                modifier = Modifier.weight(1f)
            ) {
                activePanel = if (activePanel == DockPanel.FOTO) null else DockPanel.FOTO
            }
            DockItem(
                icon = Icons.Rounded.Brush,
                label = "Dibujar",
                selected = activePanel == DockPanel.DIBUJAR,
                modifier = Modifier.weight(1f)
            ) {
                activePanel = if (activePanel == DockPanel.DIBUJAR) null else DockPanel.DIBUJAR
            }
        }
        }
    }

    if (showTrackPicker) {
        TrackPickerSheet(
            tracks = tracks,
            isLoading = isLoadingTracks,
            hasAudioPermission = hasAudioPermission,
            onRequestPermission = { permissionLauncher.launch(audioPermission) },
            onSelectTrack = viewModel::selectTrack,
            onDismiss = { showTrackPicker = false }
        )
    }

    if (showLinePicker) {
        val synced = (lyricsState as? LyricsState.Available)?.synced == true
        LyricLinePickerSheet(
            lines = lyricsLines,
            isSynced = synced,
            clipStartMs = config.clipStartMs,
            clipDurationMs = config.clipDurationMs,
            lyricsOffsetMs = config.lyricsOffsetMs,
            onSelectStartMs = viewModel::setClipStart,
            onOpenSearchLyrics = viewModel::openCandidatesPicker,
            onDismiss = { showLinePicker = false }
        )
    }

    LyricsCandidatesSheet(
        state = candidatesState,
        initialTitle = config.track?.title.orEmpty(),
        initialArtist = config.track?.artist.orEmpty(),
        onSearchCustom = viewModel::searchCandidatesCustom,
        onSelectCandidate = viewModel::selectCandidate,
        onDismiss = viewModel::dismissCandidates
    )

    ExportDialogs(
        state = exportState,
        onCancel = viewModel::cancelExport,
        onDismiss = viewModel::dismissExportDialog,
        onShare = { uri ->
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Compartir historia de Verso"))
        }
    )

    UpdateDialogs(
        state = updateState,
        onDownload = viewModel::downloadUpdate,
        onRetry = { viewModel.checkForUpdates(silent = false) },
        onDismiss = viewModel::dismissUpdateDialog
    )
}

@Composable
private fun OnboardingStepCard(
    icon: ImageVector,
    title: String,
    completed: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .border(
                width = 1.dp,
                color = if (completed) Color(0xFF4ADE80).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (completed) Icons.Rounded.CheckCircle else icon,
            contentDescription = null,
            tint = if (completed) Color(0xFF4ADE80) else VersoAccent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun DockItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) VersoAccent.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) VersoAccent else Color.White,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Color.White else VersoTextSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun TramoControls(
    config: StoryConfig,
    lyricsState: LyricsState,
    currentPositionMs: Long,
    onClipStartChange: (Long) -> Unit,
    onNudgeStart: (Long) -> Unit,
    onDurationSelect: (Long) -> Unit,
    onAdjustOffset: (Int) -> Unit,
    onResetOffset: () -> Unit,
    onOpenLinePicker: () -> Unit,
    onChangeLyrics: () -> Unit
) {
    var showSyncTools by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        val track = config.track
        if (track == null) {
            Text(
                text = "Elige primero una canción en Música para recortar el tramo.",
                style = MaterialTheme.typography.bodySmall,
                color = VersoTextSecondary
            )
            return
        }

        val lines = (lyricsState as? LyricsState.Available)?.lines.orEmpty()
        val startLyricText = remember(lines, config.clipStartMs, config.lyricsOffsetMs) {
            val idx = activeLyricIndex(lines, (config.clipStartMs + config.lyricsOffsetMs).coerceAtLeast(0L))
            if (idx >= 0) lines[idx].text else lines.firstOrNull()?.text
        }

        // Vista previa del verso inicial + botón para elegir por verso
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (!startLyricText.isNullOrBlank()) {
                    "♫ $startLyricText"
                } else {
                    "Tramo: ${formatMs(config.clipStartMs)} — ${formatMs(config.clipStartMs + config.clipDurationMs)}"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(VersoAccent.copy(alpha = 0.2f))
                    .clickable(onClick = onOpenLinePicker)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.FormatListBulleted,
                    contentDescription = null,
                    tint = VersoAccent,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Elegir verso",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = VersoAccent
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        InstagramClipScrubber(
            trackId = track.id,
            totalDurationMs = track.durationMs,
            clipStartMs = config.clipStartMs,
            clipDurationMs = config.clipDurationMs,
            currentPositionMs = currentPositionMs,
            onClipStartChange = onClipStartChange
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatMs(config.clipStartMs)} — ${formatMs((config.clipStartMs + config.clipDurationMs).coerceAtMost(track.durationMs))}",
                fontSize = 11.sp,
                color = VersoTextSecondary,
                fontWeight = FontWeight.Medium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OffsetChip("-1s") { onNudgeStart(-1_000L) }
                OffsetChip("+1s") { onNudgeStart(1_000L) }
                listOf(15_000L to "15s", 30_000L to "30s", 45_000L to "45s").forEach { (dur, label) ->
                    val selected = config.clipDurationMs == dur
                    OffsetChip(text = label, highlight = selected) { onDurationSelect(dur) }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { showSyncTools = !showSyncTools },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (showSyncTools) "Ocultar sincronía" else "Ajustar sincronía",
                    fontSize = 11.sp,
                    color = VersoTextSecondary
                )
            }
            TextButton(onClick = onChangeLyrics, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Icon(Icons.Rounded.SwapVert, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (lyricsState is LyricsState.Loading) "Buscando…" else "Cambiar letra",
                    fontSize = 11.sp
                )
            }
        }

        AnimatedVisibility(visible = showSyncTools) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Offset:", fontSize = 11.sp, color = VersoTextSecondary)
                OffsetChip("-0.5s") { onAdjustOffset(-500) }
                OffsetChip("-0.1s") { onAdjustOffset(-100) }
                OffsetChip(
                    text = if (config.lyricsOffsetMs == 0) "0.0s" else "%+.1fs".format(config.lyricsOffsetMs / 1000f),
                    highlight = config.lyricsOffsetMs != 0
                ) { onResetOffset() }
                OffsetChip("+0.1s") { onAdjustOffset(100) }
                OffsetChip("+0.5s") { onAdjustOffset(500) }
            }
        }
    }
}

@Composable
private fun MaskEditControls(
    addMode: Boolean,
    smartBrush: Boolean,
    canUndo: Boolean,
    brushRadiusNorm: Float,
    isSegmenting: Boolean,
    onSetAddMode: (Boolean) -> Unit,
    onToggleSmartBrush: (Boolean) -> Unit,
    onBrushRadiusChange: (Float) -> Unit,
    onUndo: () -> Unit,
    onAutoAi: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = addMode,
                    onClick = { onSetAddMode(true) },
                    label = { Text("+ Persona", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = !addMode,
                    onClick = { onSetAddMode(false) },
                    label = { Text("- Fondo", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = smartBrush,
                    onClick = { onToggleSmartBrush(!smartBrush) },
                    label = { Text("Borde IA", fontSize = 11.sp) }
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Button(
                onClick = onDone,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Listo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Pincel", style = MaterialTheme.typography.labelSmall, color = VersoTextSecondary)
            Spacer(modifier = Modifier.width(10.dp))
            Slider(
                value = brushRadiusNorm,
                onValueChange = onBrushRadiusChange,
                valueRange = 0.01f..0.12f,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onUndo, enabled = canUndo) {
                Text("Deshacer", fontSize = 12.sp)
            }
            TextButton(onClick = onAutoAi, enabled = !isSegmenting) {
                Text(if (isSegmenting) "Detectando…" else "Auto IA", fontSize = 12.sp)
            }
            TextButton(onClick = onClear) {
                Text("Limpiar", fontSize = 12.sp, color = VersoAccent)
            }
        }
    }
}

@Composable
private fun EstiloYAnimacionControls(
    config: StoryConfig,
    hasPhoto: Boolean,
    isSegmenting: Boolean,
    segmentationError: String?,
    isAnalyzingBeat: Boolean,
    onApplyPreset: (StoryPreset) -> Unit,
    onSelectStyle: (LyricStyle) -> Unit,
    onSelectAnimation: (LyricAnimation) -> Unit,
    onSelectEffect: (LyricEffect) -> Unit,
    onToggleBehindSubject: () -> Unit,
    onToggleBeatPulse: () -> Unit,
    onOpenManualMaskEdit: () -> Unit,
    onToggleBadge: () -> Unit
) {
    var subTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        StudioSegmentedBar(
            options = listOf("Presets", "Diseño", "Efectos"),
            selectedIndex = subTab,
            onSelect = { subTab = it }
        )
        Spacer(modifier = Modifier.height(10.dp))

        when (subTab) {
            0 -> {
                StudioSectionLabel("Combinaciones rápidas en 1 toque")
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    VERSO_PRESETS.forEach { preset ->
                        val isMatch = config.style == preset.style &&
                            config.animation == preset.animation &&
                            config.effect == preset.effect &&
                            config.font == preset.font &&
                            config.beatPulseEnabled == preset.beatPulse
                        PresetCard(
                            preset = preset,
                            isSelected = isMatch,
                            onClick = { onApplyPreset(preset) }
                        )
                    }
                }
            }
            1 -> {
                StudioSectionLabel("Estilo")
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LyricStyle.entries.forEach { style ->
                        FilterChip(
                            selected = config.style == style,
                            onClick = { onSelectStyle(style) },
                            label = { Text(style.label, fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                StudioSectionLabel("Animación")
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LyricAnimation.entries.forEach { anim ->
                        FilterChip(
                            selected = config.animation == anim,
                            onClick = { onSelectAnimation(anim) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VersoGold.copy(alpha = 0.22f),
                                selectedLabelColor = VersoGold
                            ),
                            label = { Text(anim.label, fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tarjeta de canción", style = MaterialTheme.typography.bodySmall, color = Color.White)
                    Switch(checked = config.showSongBadge, onCheckedChange = { onToggleBadge() })
                }
            }
            else -> {
                StudioSectionLabel("Efecto de texto")
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LyricEffect.entries.forEach { effect ->
                        FilterChip(
                            selected = config.effect == effect,
                            onClick = { onSelectEffect(effect) },
                            label = { Text(effect.label, fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                val hasTrack = config.track != null
                StudioToggleCard(
                    icon = Icons.Rounded.GraphicEq,
                    title = "Latido al ritmo",
                    subtitle = when {
                        isAnalyzingBeat -> "Analizando ritmo del tramo…"
                        !hasTrack -> "Elige una canción primero"
                        config.beatPulseEnabled -> "Palpita con los graves de la canción"
                        else -> "Sincroniza el pulso con la música"
                    },
                    checked = config.beatPulseEnabled,
                    loading = isAnalyzingBeat,
                    accentColor = VersoGold,
                    onToggle = onToggleBeatPulse
                )

                Spacer(modifier = Modifier.height(8.dp))
                StudioToggleCard(
                    icon = Icons.Rounded.Layers,
                    title = "Letras detrás de la persona",
                    subtitle = when {
                        !hasPhoto -> "Elige una foto primero"
                        isSegmenting -> "Recortando persona…"
                        segmentationError != null -> segmentationError
                        config.lyricsBehindSubject -> "Efecto profundidad activo"
                        else -> "IA local sobre tu foto"
                    },
                    checked = config.lyricsBehindSubject,
                    loading = isSegmenting,
                    enabled = hasPhoto,
                    onToggle = onToggleBehindSubject
                )

                if (hasPhoto) {
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onOpenManualMaskEdit,
                        enabled = !isSegmenting,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ajustar contorno manualmente", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun FotoYFondoControls(
    config: StoryConfig,
    hasPhoto: Boolean,
    isSegmenting: Boolean,
    segmentationError: String?,
    onPickPhoto: () -> Unit,
    onToggleBehindSubject: () -> Unit,
    onOpenManualMaskEdit: () -> Unit,
    onSelectPhotoFilter: (PhotoFilter) -> Unit,
    onDimChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onPickPhoto,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (hasPhoto) "Cambiar foto" else "Elegir foto", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            if (hasPhoto) {
                OutlinedButton(
                    onClick = onOpenManualMaskEdit,
                    enabled = !isSegmenting,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Contorno", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        StudioToggleCard(
            icon = Icons.Rounded.Layers,
            title = "Letras detrás de la persona",
            subtitle = when {
                !hasPhoto -> "Elige una foto primero"
                isSegmenting -> "Recortando persona…"
                segmentationError != null -> segmentationError
                config.lyricsBehindSubject -> "Efecto profundidad activo"
                else -> "IA local sobre tu foto"
            },
            checked = config.lyricsBehindSubject,
            loading = isSegmenting,
            enabled = hasPhoto,
            onToggle = onToggleBehindSubject
        )

        Spacer(modifier = Modifier.height(8.dp))
        StudioSectionLabel("Filtro de foto")
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PhotoFilter.entries.forEach { filter ->
                FilterChip(
                    selected = config.photoFilter == filter,
                    onClick = { onSelectPhotoFilter(filter) },
                    label = { Text(filter.label, fontSize = 11.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StudioSectionLabel("Oscurecer fondo")
            Spacer(modifier = Modifier.width(10.dp))
            Slider(
                value = config.backgroundDim,
                onValueChange = onDimChange,
                valueRange = 0f..0.85f,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DibujarControls(
    brushConfig: BrushConfig,
    paletteColors: List<Int>,
    hasStrokes: Boolean,
    onSelectBrush: (BrushStyle) -> Unit,
    onSelectColor: (Int) -> Unit,
    onWidthChange: (Float) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit
) {
    val brushPalette = remember(paletteColors) {
        (listOf(
            0xFFFFFFFF.toInt(),
            0xFFFF5C8A.toInt(),
            0xFFFFD166.toInt(),
            0xFF38BDF8.toInt(),
            0xFF4ADE80.toInt(),
            0xFFA855F7.toInt(),
            0xFF111118.toInt()
        ) + paletteColors).distinct()
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrushStyle.entries.forEach { brush ->
                    FilterChip(
                        selected = brushConfig.style == brush,
                        onClick = { onSelectBrush(brush) },
                        label = { Text(brush.label, fontSize = 12.sp) }
                    )
                }
            }
            Row {
                IconButton(onClick = onUndo, enabled = hasStrokes) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Undo,
                        contentDescription = "Deshacer",
                        tint = if (hasStrokes) Color.White else VersoTextSecondary
                    )
                }
                IconButton(onClick = onClear, enabled = hasStrokes) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "Borrar trazos",
                        tint = if (hasStrokes) VersoAccent else VersoTextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Color:", style = MaterialTheme.typography.labelSmall, color = VersoTextSecondary)
            brushPalette.forEach { argb ->
                val selected = brushConfig.color == argb
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (selected) 2.5.dp else 1.dp,
                            color = if (selected) VersoAccent else Color.White.copy(alpha = 0.25f),
                            shape = CircleShape
                        )
                        .clickable { onSelectColor(argb) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Grosor", style = MaterialTheme.typography.labelSmall, color = VersoTextSecondary)
            Spacer(modifier = Modifier.width(10.dp))
            Slider(
                value = brushConfig.widthNorm,
                onValueChange = onWidthChange,
                valueRange = 0.008f..0.055f,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TextoYColorControls(
    config: StoryConfig,
    paletteColors: List<Int>,
    onSelectFont: (LyricFont) -> Unit,
    onSelectAlignment: (LyricAlignment) -> Unit,
    onScaleChange: (Float) -> Unit,
    onSelectTextColor: (Int) -> Unit,
    onSelectAccentColor: (Int) -> Unit
) {
    var subTab by remember { mutableStateOf(0) }
    var editingAccent by remember { mutableStateOf(false) }

    val colorChoices = remember(paletteColors) {
        (listOf(
            0xFFFFFFFF.toInt(),
            0xFF111118.toInt(),
            0xFFFF5C8A.toInt(),
            0xFFEF4444.toInt(),
            0xFFFF8A5B.toInt(),
            0xFFFFD166.toInt(),
            0xFFA3E635.toInt(),
            0xFF4ADE80.toInt(),
            0xFF2DD4BF.toInt(),
            0xFF38BDF8.toInt(),
            0xFF60A5FA.toInt(),
            0xFFA855F7.toInt(),
            0xFFE879F9.toInt()
        ) + paletteColors).distinct()
    }

    val activeColor = if (editingAccent) config.accentColor else config.textColor
    val onPickColor: (Int) -> Unit = { argb ->
        if (editingAccent) onSelectAccentColor(argb) else onSelectTextColor(argb)
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        StudioSegmentedBar(
            options = listOf("Fuente y tamaño", "Colores"),
            selectedIndex = subTab,
            onSelect = { subTab = it }
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (subTab == 0) {
            StudioSectionLabel("Tipografía")
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LyricFont.entries.forEach { font ->
                    val family = remember(font) { FontFamily(Font(font.fontRes)) }
                    FilterChip(
                        selected = config.font == font,
                        onClick = { onSelectFont(font) },
                        label = {
                            Text(
                                text = font.label,
                                style = TextStyle(fontFamily = family, fontSize = 14.sp)
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StudioSectionLabel("Tamaño")
                Spacer(modifier = Modifier.width(8.dp))
                Slider(
                    value = config.lyricScale,
                    onValueChange = onScaleChange,
                    valueRange = 0.6f..1.8f,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StudioSectionLabel("Alineación")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LyricAlignment.entries.forEach { align ->
                        val selected = config.textAlignment == align
                        val alignIcon = when (align) {
                            LyricAlignment.LEFT -> Icons.AutoMirrored.Rounded.FormatAlignLeft
                            LyricAlignment.CENTER -> Icons.Rounded.FormatAlignCenter
                            LyricAlignment.RIGHT -> Icons.AutoMirrored.Rounded.FormatAlignRight
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) VersoAccent.copy(alpha = 0.24f) else VersoSurfaceElevated)
                                .border(
                                    width = 1.dp,
                                    color = if (selected) VersoAccent else VersoBorder,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { onSelectAlignment(align) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = alignIcon,
                                contentDescription = align.label,
                                tint = if (selected) VersoAccent else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        } else {
            StudioSegmentedBar(
                options = listOf("Color de letra", "Brillo y acento"),
                selectedIndex = if (editingAccent) 1 else 0,
                onSelect = { editingAccent = it == 1 }
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                colorChoices.forEach { argb ->
                    val selected = activeColor == argb
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (selected) 2.5.dp else 1.dp,
                                color = if (selected) VersoAccent else Color.White.copy(alpha = 0.25f),
                                shape = CircleShape
                            )
                            .clickable { onPickColor(argb) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HueColorBar(
                selectedColor = activeColor,
                onColorSelected = onPickColor
            )
        }
    }
}

@Composable
private fun HueColorBar(
    selectedColor: Int,
    onColorSelected: (Int) -> Unit
) {
    val hueColors = remember {
        listOf(
            Color(0xFFFF0000),
            Color(0xFFFFFF00),
            Color(0xFF00FF00),
            Color(0xFF00FFFF),
            Color(0xFF0000FF),
            Color(0xFFFF00FF),
            Color(0xFFFF0000)
        )
    }

    val hsv = remember(selectedColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(selectedColor, it) }
    }
    val thumbFrac = (hsv[0] / 360f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Brush.horizontalGradient(hueColors))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(11.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val argb = android.graphics.Color.HSVToColor(floatArrayOf(frac * 360f, 0.85f, 1f))
                    onColorSelected(argb)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val frac = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val argb = android.graphics.Color.HSVToColor(floatArrayOf(frac * 360f, 0.85f, 1f))
                    onColorSelected(argb)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = thumbFrac * size.width
            val cy = size.height / 2f
            drawCircle(color = Color.White, radius = size.height * 0.44f, center = androidx.compose.ui.geometry.Offset(cx, cy))
            drawCircle(color = Color(selectedColor), radius = size.height * 0.30f, center = androidx.compose.ui.geometry.Offset(cx, cy))
        }
    }
}

@Composable
private fun OffsetChip(text: String, highlight: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlight) VersoAccent.copy(alpha = 0.22f) else VersoSurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
            color = if (highlight) VersoAccent else Color.White
        )
    }
}

@Composable
private fun ExportDialogs(
    state: ExportUiState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onShare: (android.net.Uri) -> Unit
) {
    when (state) {
        ExportUiState.Idle -> Unit
        is ExportUiState.Exporting -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Exportando historia…") },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${(state.progress * 100).toInt()}%", color = VersoTextSecondary)
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onCancel) { Text("Cancelar") }
                }
            )
        }
        is ExportUiState.Success -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("¡Video listo!") },
                text = { Text("Se guardó en Movies/Verso. Ya puedes compartirlo en Instagram o WhatsApp.") },
                confirmButton = {
                    Button(onClick = { onShare(state.result.shareUri) }) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Compartir")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = onDismiss) { Text("Cerrar") }
                }
            )
        }
        is ExportUiState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("No se pudo exportar") },
                text = { Text(state.message) },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("Entendido") }
                }
            )
        }
    }
}

@Composable
private fun UpdateDialogs(
    state: UpdateUiState,
    onDownload: (GithubReleaseInfo) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var permissionNotice by remember { mutableStateOf(false) }

    when (state) {
        UpdateUiState.Idle -> Unit
        UpdateUiState.Checking -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Buscando actualizaciones…") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Text("Consultando GitHub Releases…", color = VersoTextSecondary)
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                }
            )
        }
        is UpdateUiState.UpToDate -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Todo al día") },
                text = { Text("Verso v${state.currentVersion} está al día.") },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
            )
        }
        is UpdateUiState.Available -> {
            val sizeMb = if (state.release.apkSizeBytes > 0L) {
                " • %.1f MB".format(state.release.apkSizeBytes / (1024f * 1024f))
            } else {
                ""
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Actualización disponible") },
                text = {
                    Column {
                        Text(
                            text = "${state.release.title} (v${state.release.versionName}$sizeMb)",
                            fontWeight = FontWeight.Bold
                        )
                        if (state.release.notes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(state.release.notes, color = VersoTextSecondary)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { onDownload(state.release) }) {
                        Text("Descargar e instalar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Después") }
                }
            )
        }
        is UpdateUiState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Descargando v${state.release.versionName}…") },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${(state.progress * 100).toInt()}%", color = VersoTextSecondary)
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                }
            )
        }
        is UpdateUiState.ReadyToInstall -> {
            LaunchedEffect(state.apkFile) {
                val launched = AppUpdateManager.promptInstallApk(context, state.apkFile)
                permissionNotice = !launched
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Actualización lista") },
                text = {
                    Text(
                        if (permissionNotice) {
                            "Activa el permiso para instalar aplicaciones de esta fuente y luego pulsa Instalar ahora."
                        } else {
                            "Verso v${state.release.versionName} se descargó correctamente."
                        }
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        val launched = AppUpdateManager.promptInstallApk(context, state.apkFile)
                        permissionNotice = !launched
                    }) {
                        Text("Instalar ahora")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
            )
        }
        is UpdateUiState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Error de actualización") },
                text = { Text(state.message) },
                confirmButton = {
                    Button(onClick = onRetry) { Text("Reintentar") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
            )
        }
    }
}
