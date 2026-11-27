package com.verso.lyrics.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verso.lyrics.model.LyricFont
import com.verso.lyrics.model.LyricStyle
import com.verso.lyrics.model.LyricsState
import com.verso.lyrics.render.LyricFrameRenderer
import com.verso.lyrics.update.AppUpdateManager
import com.verso.lyrics.update.GithubReleaseInfo
import com.verso.lyrics.ui.theme.VersoAccent
import com.verso.lyrics.ui.theme.VersoBg
import com.verso.lyrics.ui.theme.VersoSurface
import com.verso.lyrics.ui.theme.VersoSurfaceElevated
import com.verso.lyrics.ui.theme.VersoTextSecondary

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
    var selectedTab by remember { mutableIntStateOf(0) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VersoBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Barra superior
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Verso",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Elegir foto", tint = Color.White)
                }

                IconButton(onClick = {
                    if (hasAudioPermission && tracks.isEmpty()) {
                        viewModel.loadDeviceTracks()
                    }
                    showTrackPicker = true
                }) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = "Elegir canción", tint = Color.White)
                }

                IconButton(onClick = { lrcFolderLauncher.launch(null) }) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = "Carpeta .lrc", tint = VersoTextSecondary)
                }

                Box {
                    IconButton(onClick = { viewModel.checkForUpdates(silent = false) }) {
                        Icon(
                            Icons.Rounded.SystemUpdate,
                            contentDescription = "Buscar actualizaciones",
                            tint = if (hasPendingUpdate) VersoAccent else VersoTextSecondary
                        )
                    }
                    if (hasPendingUpdate) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 8.dp, end = 8.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(VersoAccent)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Button(
                    onClick = { viewModel.startExport() },
                    enabled = config.track != null,
                    colors = ButtonDefaults.buttonColors(containerColor = VersoAccent),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Rounded.MovieCreation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Exportar", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // Lienzo 9:16 interactivo
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(VersoSurface)
                    .pointerInput(Unit) {
                        detectTapGestures { viewModel.togglePlayPause() }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rotation ->
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
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        LyricFrameRenderer.drawFrame(
                            canvas = canvas.nativeCanvas,
                            width = size.width.toInt(),
                            height = size.height.toInt(),
                            config = config,
                            lines = lyricsLines,
                            positionMs = currentPositionMs,
                            photoBitmap = photoBitmap,
                            albumArtBitmap = albumArtBitmap
                        )
                    }
                }

                // Botón sutil Play/Pause en esquina inferior derecha del preview
                if (config.track != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable { viewModel.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // Panel de edición inferior con 3 pestañas
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(VersoSurface)
                .padding(bottom = 10.dp)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = VersoSurface,
                contentColor = VersoAccent
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Tramo") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Estilo") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Apariencia") })
            }

            when (selectedTab) {
                0 -> TramoControls(
                    config = config,
                    lyricsState = lyricsState,
                    onClipStartChange = viewModel::setClipStart,
                    onDurationSelect = viewModel::setClipDuration,
                    onAdjustOffset = viewModel::adjustLyricsOffset,
                    onResetOffset = viewModel::resetLyricsOffset,
                    onChangeLyrics = viewModel::openCandidatesPicker
                )
                1 -> EstiloControls(
                    config = config,
                    onSelectStyle = viewModel::setStyle,
                    onToggleBadge = viewModel::toggleSongBadge
                )
                2 -> AparienciaControls(
                    config = config,
                    paletteColors = paletteColors,
                    onSelectFont = viewModel::setFont,
                    onSelectTextColor = viewModel::setTextColor,
                    onSelectAccentColor = viewModel::setAccentColor,
                    onDimChange = viewModel::setBackgroundDim
                )
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
private fun TramoControls(
    config: com.verso.lyrics.model.StoryConfig,
    lyricsState: LyricsState,
    onClipStartChange: (Long) -> Unit,
    onDurationSelect: (Long) -> Unit,
    onAdjustOffset: (Int) -> Unit,
    onResetOffset: () -> Unit,
    onChangeLyrics: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        val track = config.track
        if (track == null) {
            Text(
                text = "Toca el ícono de música arriba para elegir una canción del dispositivo.",
                style = MaterialTheme.typography.bodySmall,
                color = VersoTextSecondary
            )
            return
        }

        val maxStart = (track.durationMs - config.clipDurationMs).coerceAtLeast(1L)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Inicio: ${formatMs(config.clipStartMs)} — ${formatMs(config.clipStartMs + config.clipDurationMs)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15_000L to "15s", 30_000L to "30s", 45_000L to "45s").forEach { (dur, label) ->
                    val selected = config.clipDurationMs == dur
                    FilterChip(
                        selected = selected,
                        onClick = { onDurationSelect(dur) },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }
        }

        Slider(
            value = (config.clipStartMs.toFloat() / maxStart.toFloat()).coerceIn(0f, 1f),
            onValueChange = { frac -> onClipStartChange((frac * maxStart).toLong()) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                OffsetChip("-0.5s") { onAdjustOffset(-500) }
                OffsetChip("-0.1s") { onAdjustOffset(-100) }
                OffsetChip(
                    text = if (config.lyricsOffsetMs == 0) "0.0s" else "%+.1fs".format(config.lyricsOffsetMs / 1000f),
                    highlight = config.lyricsOffsetMs != 0
                ) { onResetOffset() }
                OffsetChip("+0.1s") { onAdjustOffset(100) }
                OffsetChip("+0.5s") { onAdjustOffset(500) }
            }

            TextButton(onClick = onChangeLyrics) {
                Icon(Icons.Rounded.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (lyricsState is LyricsState.Loading) "Buscando…" else "Cambiar letra",
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun EstiloControls(
    config: com.verso.lyrics.model.StoryConfig,
    onSelectStyle: (LyricStyle) -> Unit,
    onToggleBadge: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
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
                    label = { Text(style.label) }
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Mostrar tarjeta de canción", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = config.showSongBadge, onCheckedChange = { onToggleBadge() })
        }
    }
}

@Composable
private fun AparienciaControls(
    config: com.verso.lyrics.model.StoryConfig,
    paletteColors: List<Int>,
    onSelectFont: (LyricFont) -> Unit,
    onSelectTextColor: (Int) -> Unit,
    onSelectAccentColor: (Int) -> Unit,
    onDimChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LyricFont.entries.forEach { font ->
                FilterChip(
                    selected = config.font == font,
                    onClick = { onSelectFont(font) },
                    label = { Text(font.label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Color:", style = MaterialTheme.typography.labelMedium, color = VersoTextSecondary)
            paletteColors.forEach { argb ->
                val selected = config.textColor == argb
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
                        .clickable {
                            onSelectTextColor(argb)
                            if (argb != 0xFFFFFFFF.toInt()) onSelectAccentColor(argb)
                        }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Fondo oscuro", style = MaterialTheme.typography.labelMedium, color = VersoTextSecondary)
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
