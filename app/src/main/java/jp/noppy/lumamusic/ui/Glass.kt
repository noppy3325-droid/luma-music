package jp.noppy.lumamusic.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

val Rose = LumaColors.Accent
val Ink = LumaColors.Dark

/** Plain settings/content grouping. Live glass is reserved for FloatingGlass controls. */
@Composable fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(.07f))
}

@Composable fun Artwork(uri: String?, title: String, modifier: Modifier = Modifier, radius: Int = 14) {
    val seed = title.hashCode()
    val hue = ((seed.toLong() and 0xffff) % 360).toFloat()
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(Brush.linearGradient(listOf(Color.hsl(hue, .35f, .32f), Color.hsl((hue + 65) % 360, .45f, .13f)))), contentAlignment = Alignment.Center) {
        if (uri != null) AsyncImage(uri, "アルバムアート: $title", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(.34f), tint = Color.White.copy(.5f))
    }
}

@Composable fun PressButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !LocalReducedMotion.current) .96f else 1f, label = "press")
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(100)).background(LocalContentColor.current.copy(.06f))
        .clickable(interactionSource = interactions, indication = null, onClick = onClick).padding(16.dp), contentAlignment = Alignment.Center) { content() }
}

fun timeText(time: Long): String { val seconds = (time.coerceAtLeast(0) / 1000); return "%d:%02d".format(seconds / 60, seconds % 60) }
fun rateText(rate: Int?) = rate?.let { if (it % 1000 == 0) "${it / 1000} kHz" else "${it / 1000.0} kHz" } ?: "未確認"
