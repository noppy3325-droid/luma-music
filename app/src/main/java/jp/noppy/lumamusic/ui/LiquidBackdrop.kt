package jp.noppy.lumamusic.ui

import android.os.Build
import android.os.PowerManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

enum class GlassQuality(val label: String) { HIGH("高品質"), BALANCED("バランス"), REDUCED("軽量"), OFF("オフ") }
val LocalLiquidBackdrop = staticCompositionLocalOf<Backdrop?> { null }
val LocalGlassQuality = staticCompositionLocalOf { GlassQuality.BALANCED }
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable fun effectiveGlassQuality(saved: String, reducedMotion: Boolean): GlassQuality {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var batterySaver by remember { mutableStateOf(power.isPowerSaveMode) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { batterySaver = power.isPowerSaveMode } }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val requested = runCatching { GlassQuality.valueOf(saved) }.getOrDefault(GlassQuality.BALANCED)
    return if ((batterySaver || reducedMotion) && requested in listOf(GlassQuality.HIGH, GlassQuality.BALANCED)) GlassQuality.REDUCED else requested
}

/** Record only source content. Floating surfaces are siblings, never part of their own source. */
@Composable fun LiquidBackdropHost(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit, overlay: @Composable BoxScope.() -> Unit) {
    val source = rememberLayerBackdrop()
    CompositionLocalProvider(LocalLiquidBackdrop provides source) {
        Box(modifier) {
            Box(Modifier.matchParentSize().layerBackdrop(source), content = content)
            overlay()
        }
    }
}

@Composable fun Modifier.liquidSurface(radius: Int = 28): Modifier {
    val source = LocalLiquidBackdrop.current
    val quality = LocalGlassQuality.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(radius.dp)
    val tint = if (dark) Color(0xFF202027) else Color.White
    // RenderEffect starts on API 31 and the refractive AGSL lens on API 33.
    // Older devices and Off use an honest opaque surface with no claimed blur.
    if (source == null || quality == GlassQuality.OFF || Build.VERSION.SDK_INT < 31) {
        return clip(shape).background(tint.copy(alpha = if (quality == GlassQuality.OFF) 1f else .96f))
    }
    return drawBackdrop(
        backdrop = source,
        shape = { shape },
        effects = {
            vibrancy()
            // Limit sampled luminance as well as tinting: labels remain readable over
            // black/white jackets, while the live image and edge lens stay visible.
            colorControls(brightness = if (dark) -.18f else .18f, contrast = .6f)
            blur((when (quality) { GlassQuality.HIGH -> 12f; GlassQuality.BALANCED -> 8f; else -> 4f }).dp.toPx())
            if (quality != GlassQuality.REDUCED && Build.VERSION.SDK_INT >= 33) {
                lens(8.dp.toPx(), if (quality == GlassQuality.HIGH) 20.dp.toPx() else 12.dp.toPx())
            }
        },
        onDrawSurface = { drawRect(tint.copy(alpha = if (dark) .38f else .26f)) }
    )
}

/** Debug spike: scroll colored text behind a fixed surface to verify live sampling and alignment. */
@Composable fun GlassDemoScreen() {
    var quality by remember { mutableStateOf(GlassQuality.BALANCED) }
    LumaTheme("light") {
        CompositionLocalProvider(LocalGlassQuality provides quality) {
            LiquidBackdropHost(Modifier.fillMaxSize(), content = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(Color(0xFFF7F6FA)).padding(24.dp)) {
                    Text("Backdrop 2.0.1 実描画テスト", style = MaterialTheme.typography.headlineMedium)
                    Text("下のガラス越しに色・文字・境界の移動を確認")
                    repeat(30) { index ->
                        val start = when (index % 5) { 0 -> Color.White; 1 -> Color(0xFF777777); 2 -> Color(0xFF151515); else -> Color.hsl(index * 37f % 360, .7f, .65f) }
                        Row(Modifier.fillMaxWidth().height(112.dp).background(Brush.horizontalGradient(listOf(start, Color.hsl((index * 37f + 90) % 360, .7f, .4f)))).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(null, "検証アルバム $index", Modifier.size(80.dp), 12)
                            Spacer(Modifier.width(12.dp))
                            Text("背景の文字 ${index + 1}\n━━━━━━", color = if (index % 5 == 0) Color.Black else Color.White, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }, overlay = {
                Text("背景に追従するピル", Modifier.align(Alignment.Center).liquidSurface(50).padding(horizontal = 24.dp, vertical = 16.dp), color = Color.Black)
                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp).fillMaxWidth().liquidSurface(32).padding(20.dp)) {
                    Text("ライブガラス", style = MaterialTheme.typography.titleLarge)
                    Text("API ${Build.VERSION.SDK_INT} · ${quality.label}")
                    Row { GlassQuality.entries.forEach { option -> TextButton({ quality = option }) { Text(option.label) } } }
                }
            })
        }
    }
}
