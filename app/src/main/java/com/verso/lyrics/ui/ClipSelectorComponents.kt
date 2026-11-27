package com.verso.lyrics.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verso.lyrics.model.LyricLine
import com.verso.lyrics.model.activeLyricIndex
import com.verso.lyrics.ui.theme.VersoAccent
import com.verso.lyrics.ui.theme.VersoGold
import com.verso.lyrics.ui.theme.VersoSurface
import com.verso.lyrics.ui.theme.VersoSurfaceElevated
import com.verso.lyrics.ui.theme.VersoTextSecondary
import kotlin.math.abs
import kotlin.math.sin

/**
 * Scrubber visual estilo Instagram Stories con 48 barras de onda,
 * ventana deslizable para [clipStartMs..clipStartMs+clipDurationMs]
 * e indicador en vivo de [currentPositionMs].
 */
@Composable
fun InstagramClipScrubber(
    trackId: Long,
    totalDurationMs: Long,
    clipStartMs: Long,
    clipDurationMs: Long,
    currentPositionMs: Long,
    onClipStartChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeTotal = totalDurationMs.coerceAtLeast(1L)
    val maxStart = (safeTotal - clipDurationMs).coerceAtLeast(0L)
    val latestStart by rememberUpdatedState(clipStartMs)
    val latestOnChange by rememberUpdatedState(onClipStartChange)
    var dragStartMs by remember { mutableLongStateOf(clipStartMs) }
    var dragAccumPx by remember { mutableLongStateOf(0L) }

    val bars = remember(trackId) {
        List(48) { idx ->
            val seed = sin((idx + 1) * 0.72 + (trackId % 97) * 0.31)
            val wave = abs(seed).toFloat()
            (0.24f + 0.72f * wave).coerceIn(0.22f, 0.96f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(VersoSurfaceElevated)
            .pointerInput(safeTotal, clipDurationMs) {
                detectTapGestures { offset ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val tapMs = ((offset.x / w) * safeTotal).toLong()
                    val centeredStart = (tapMs - clipDurationMs / 2L).coerceIn(0L, maxStart)
                    latestOnChange(centeredStart)
                }
            }
            .pointerInput(safeTotal, clipDurationMs) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragStartMs = latestStart
                        dragAccumPx = 0L
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val deltaMs = ((dragAmount / w) * safeTotal).toLong()
                        dragAccumPx += deltaMs
                        val next = (dragStartMs + dragAccumPx).coerceIn(0L, maxStart)
                        latestOnChange(next)
                    }
                )
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSizeCompat()) {
            val w = size.width
            val h = size.height
            val startFrac = (clipStartMs.toFloat() / safeTotal).coerceIn(0f, 1f)
            val endFrac = ((clipStartMs + clipDurationMs).toFloat() / safeTotal).coerceIn(startFrac, 1f)
            val winLeft = startFrac * w
            val winRight = endFrac * w
            val winW = (winRight - winLeft).coerceAtLeast(10f)

            // Fondo de ventana activa
            drawRoundRect(
                color = VersoAccent.copy(alpha = 0.16f),
                topLeft = Offset(winLeft, 0f),
                size = Size(winW, h),
                cornerRadius = CornerRadius(10f, 10f)
            )

            // Barras de onda
            val count = bars.size
            val slotW = w / count
            val barW = (slotW * 0.56f).coerceAtLeast(2.5f)
            val corner = CornerRadius(barW / 2f, barW / 2f)

            bars.forEachIndexed { idx, amp ->
                val cx = idx * slotW + slotW * 0.5f
                val inWindow = cx in winLeft..winRight
                val barH = h * amp * 0.84f
                val top = (h - barH) / 2f
                drawRoundRect(
                    color = if (inWindow) Color.White else Color.White.copy(alpha = 0.22f),
                    topLeft = Offset(cx - barW / 2f, top),
                    size = Size(barW, barH),
                    cornerRadius = corner
                )
            }

            // Borde resaltado de la ventana activa
            drawRoundRect(
                color = VersoAccent,
                topLeft = Offset(winLeft, 1f),
                size = Size(winW, h - 2f),
                cornerRadius = CornerRadius(10f, 10f),
                style = Stroke(width = 3f)
            )

            // Playhead en vivo dentro del tramo
            val playFrac = (currentPositionMs.toFloat() / safeTotal).coerceIn(0f, 1f)
            val playX = playFrac * w
            drawLine(
                color = VersoGold,
                start = Offset(playX, 0f),
                end = Offset(playX, h),
                strokeWidth = 3.5f
            )
        }
    }
}

@Composable
private fun Modifier.fillMaxSizeCompat(): Modifier = this
    .fillMaxWidth()
    .fillMaxHeight()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricLinePickerSheet(
    lines: List<LyricLine>,
    isSynced: Boolean,
    clipStartMs: Long,
    clipDurationMs: Long,
    lyricsOffsetMs: Int,
    onSelectStartMs: (Long) -> Unit,
    onOpenSearchLyrics: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val clipEndMs = clipStartMs + clipDurationMs

    val firstSelectedIdx = remember(lines, clipStartMs, lyricsOffsetMs) {
        activeLyricIndex(lines, (clipStartMs + lyricsOffsetMs).coerceAtLeast(0L)).coerceAtLeast(0)
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && firstSelectedIdx in lines.indices) {
            listState.scrollToItem((firstSelectedIdx - 2).coerceAtLeast(0))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VersoSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Toca el verso donde quieres empezar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "El tramo de ${clipDurationMs / 1000L}s arrancará justo en esa línea.",
                style = MaterialTheme.typography.bodySmall,
                color = VersoTextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (lines.isEmpty() || !isSynced) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (lines.isEmpty()) {
                                "Esta canción aún no tiene letra cargada."
                            } else {
                                "Esta letra no tiene marcas de tiempo sincronizadas."
                            },
                            color = VersoTextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = {
                            onDismiss()
                            onOpenSearchLyrics()
                        }) {
                            Text("Buscar letra sincronizada")
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(lines, key = { idx, line -> "${line.timeMs}_$idx" }) { _, line ->
                        val effectiveStart = (line.timeMs - lyricsOffsetMs).coerceAtLeast(0L)
                        val inClip = effectiveStart in clipStartMs..clipEndMs

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (inClip) VersoAccent.copy(alpha = 0.16f) else VersoSurfaceElevated
                                )
                                .clickable {
                                    onSelectStartMs(effectiveStart)
                                    onDismiss()
                                }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (inClip) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(22.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(VersoAccent)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = formatMs(effectiveStart),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (inClip) VersoAccent else VersoTextSecondary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = line.text,
                                color = Color.White,
                                fontWeight = if (inClip) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
