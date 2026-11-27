package com.verso.lyrics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verso.lyrics.model.LyricsCandidate
import com.verso.lyrics.ui.theme.VersoAccent
import com.verso.lyrics.ui.theme.VersoSurface
import com.verso.lyrics.ui.theme.VersoSurfaceElevated
import com.verso.lyrics.ui.theme.VersoTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsCandidatesSheet(
    state: CandidatesUiState,
    initialTitle: String,
    initialArtist: String,
    onSearchCustom: (String, String) -> Unit,
    onSelectCandidate: (LyricsCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    if (state is CandidatesUiState.Hidden) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var titleQuery by remember(initialTitle) { mutableStateOf(initialTitle) }
    var artistQuery by remember(initialArtist) { mutableStateOf(initialArtist) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VersoSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Elegir otra letra (LRCLIB)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = titleQuery,
                    onValueChange = { titleQuery = it },
                    label = { Text("Título") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = artistQuery,
                    onValueChange = { artistQuery = it },
                    label = { Text("Artista") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onSearchCustom(titleQuery, artistQuery) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Buscar en LRCLIB")
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                CandidatesUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = VersoAccent)
                    }
                }
                is CandidatesUiState.Ready -> {
                    if (state.candidates.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (state.hasError) {
                                    "Error de red al consultar LRCLIB."
                                } else {
                                    "No se encontraron candidatos."
                                },
                                color = VersoTextSecondary
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.candidates, key = { "${it.id}_${it.synced}_${it.trackName}" }) { cand ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(VersoSurfaceElevated)
                                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                                        .clickable { onSelectCandidate(cand) }
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${cand.trackName} — ${cand.artistName}",
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (cand.synced) VersoAccent.copy(alpha = 0.18f)
                                                    else Color.White.copy(alpha = 0.08f)
                                                )
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = if (cand.synced) "Sincronizada" else "Texto",
                                                fontSize = 10.sp,
                                                color = if (cand.synced) VersoAccent else VersoTextSecondary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    if (cand.previewText.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = cand.previewText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = VersoTextSecondary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                CandidatesUiState.Hidden -> Unit
            }
        }
    }
}
