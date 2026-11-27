package com.verso.lyrics.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verso.lyrics.model.LyricAnimation
import com.verso.lyrics.model.LyricEffect
import com.verso.lyrics.model.LyricFont
import com.verso.lyrics.model.LyricStyle
import com.verso.lyrics.ui.theme.VersoAccent
import com.verso.lyrics.ui.theme.VersoAccentSecondary
import com.verso.lyrics.ui.theme.VersoBorder
import com.verso.lyrics.ui.theme.VersoCyan
import com.verso.lyrics.ui.theme.VersoGold
import com.verso.lyrics.ui.theme.VersoGreen
import com.verso.lyrics.ui.theme.VersoSurfaceElevated
import com.verso.lyrics.ui.theme.VersoTextSecondary

data class StoryPreset(
    val id: String,
    val name: String,
    val tagline: String,
    val previewColors: List<Color>,
    val style: LyricStyle,
    val animation: LyricAnimation,
    val effect: LyricEffect,
    val font: LyricFont,
    val beatPulse: Boolean,
    val behindSubject: Boolean = false
)

val VERSO_PRESETS: List<StoryPreset> = listOf(
    StoryPreset(
        id = "ig_stack",
        name = "IG Apilado",
        tagline = "Versos que se apilan + latido",
        previewColors = listOf(VersoAccent, VersoAccentSecondary),
        style = LyricStyle.STACK,
        animation = LyricAnimation.FADE_UP,
        effect = LyricEffect.OUTLINE,
        font = LyricFont.MODERNA,
        beatPulse = true
    ),
    StoryPreset(
        id = "depth_cinema",
        name = "Cine 3D",
        tagline = "Detrás de tu silueta + doble capa",
        previewColors = listOf(VersoCyan, Color(0xFF6366F1)),
        style = LyricStyle.POSTER,
        animation = LyricAnimation.POP_BOUNCE,
        effect = LyricEffect.HOLLOW_OVERLAP,
        font = LyricFont.EDITORIAL,
        beatPulse = false,
        behindSubject = true
    ),
    StoryPreset(
        id = "neon_beat",
        name = "Neón Club",
        tagline = "Brillo neón sincronizado",
        previewColors = listOf(Color(0xFFA855F7), VersoAccent),
        style = LyricStyle.FOCUS,
        animation = LyricAnimation.GLOW_PULSE,
        effect = LyricEffect.NEON,
        font = LyricFont.NEON,
        beatPulse = true
    ),
    StoryPreset(
        id = "karaoke_pop",
        name = "Karaoke Pop",
        tagline = "Resaltado palabra a palabra",
        previewColors = listOf(VersoGreen, VersoCyan),
        style = LyricStyle.SCROLL,
        animation = LyricAnimation.KARAOKE,
        effect = LyricEffect.SHADOW_3D,
        font = LyricFont.MODERNA,
        beatPulse = true
    ),
    StoryPreset(
        id = "retro_card",
        name = "Tarjeta Retro",
        tagline = "Máquina de escribir limpia",
        previewColors = listOf(VersoGold, VersoAccentSecondary),
        style = LyricStyle.CARD,
        animation = LyricAnimation.WORD_BY_WORD,
        effect = LyricEffect.NONE,
        font = LyricFont.MAQUINA,
        beatPulse = false
    ),
    StoryPreset(
        id = "minimal_gold",
        name = "Minimal Cine",
        tagline = "Elegante dorado cinematográfico",
        previewColors = listOf(Color(0xFFFDE68A), VersoGold),
        style = LyricStyle.CINEMA,
        animation = LyricAnimation.FADE_UP,
        effect = LyricEffect.GRADIENT,
        font = LyricFont.MANUSCRITA,
        beatPulse = false
    )
)

@Composable
fun StudioSegmentedBar(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(VersoSurfaceElevated)
            .border(1.dp, VersoBorder, RoundedCornerShape(14.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { idx, label ->
            val selected = idx == selectedIndex
            val bgColor by animateColorAsState(
                targetValue = if (selected) VersoAccent.copy(alpha = 0.24f) else Color.Transparent,
                label = "segBg"
            )
            val txtColor by animateColorAsState(
                targetValue = if (selected) Color.White else VersoTextSecondary,
                label = "segTxt"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(bgColor)
                    .clickable { onSelect(idx) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = txtColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun StudioSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = VersoTextSecondary,
        modifier = modifier
    )
}

@Composable
fun StudioToggleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    loading: Boolean = false,
    enabled: Boolean = true,
    accentColor: Color = VersoAccent,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) accentColor.copy(alpha = 0.14f) else VersoSurfaceElevated)
            .border(
                width = 1.dp,
                color = if (checked) accentColor.copy(alpha = 0.5f) else VersoBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(enabled = enabled && !loading, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (checked) accentColor.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (checked) accentColor else Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = VersoTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = accentColor
            )
        } else {
            Switch(
                checked = checked,
                onCheckedChange = { if (enabled) onToggle() },
                enabled = enabled
            )
        }
    }
}

@Composable
fun FloatingCanvasPillButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color = VersoAccent,
    pulseScale: Float = 1f,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (active) activeColor.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.56f)
            )
            .border(
                width = 1.dp,
                color = if (active) activeColor.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) activeColor else Color.White,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) Color.White else VersoTextSecondary
        )
    }
}

@Composable
fun PresetCard(
    preset: StoryPreset,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(126.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) VersoAccent.copy(alpha = 0.16f) else VersoSurfaceElevated)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) VersoAccent else VersoBorder,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(preset.previewColors))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = preset.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = preset.tagline,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            color = VersoTextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
