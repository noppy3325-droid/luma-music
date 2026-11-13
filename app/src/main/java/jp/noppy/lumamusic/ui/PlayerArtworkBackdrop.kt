package jp.noppy.lumamusic.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val artworkPalettes = LruCache<String, Pair<Color, Color>>(64)

/** Fixed request size: drawing and dragging never launch a fresh decode. */
@Composable
internal fun rememberPlayerArtwork(artwork: String?, reducedMotion: Boolean): Painter? {
    val context = LocalContext.current
    if (artwork == null) return null
    val request = remember(context, artwork, reducedMotion) {
        ImageRequest.Builder(context)
            .data(artwork)
            .size(1080, 1080)
            .crossfade(!reducedMotion)
            .build()
    }
    // Until the new request resolves, the new track's palette is visible.
    // Replacing the painter by URI avoids presenting the old artwork with a new title.
    return key(artwork) { rememberAsyncImagePainter(request, contentScale = ContentScale.Fit) }
}

@Composable
internal fun PlayerArtworkBackdrop(
    sheet: PlayerSheetState,
    hero: Painter?,
    artwork: String?,
    title: String,
    miniSurface: Color,
    wide: Boolean,
    alternatePanel: Boolean
) {
    val context = LocalContext.current
    val reducedMotion = LocalReducedMotion.current
    val statusBars = WindowInsets.statusBars
    val hue = ((title.hashCode().toLong() and 0xffff) % 360).toFloat()
    val placeholder = remember(hue) { Color.hsl(hue, .38f, .28f) to Color.hsl((hue + 52f) % 360f, .35f, .20f) }
    val palette by produceState(placeholder, artwork, placeholder) {
        value = if (artwork == null) placeholder else withContext(Dispatchers.IO) {
            artworkPalettes.get(artwork) ?: runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                val bitmap = context.contentResolver.openInputStream(Uri.parse(artwork))?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: return@runCatching placeholder
                try {
                    val swatches = Palette.from(bitmap).maximumColorCount(10).generate()
                    val first = Color(swatches.getDominantColor(placeholder.first.toArgb()))
                    val second = swatches.swatches.sortedByDescending { it.population }
                        .firstOrNull { Color(it.rgb) != first }?.let { Color(it.rgb) } ?: placeholder.second
                    (first to second).also { artworkPalettes.put(artwork, it) }
                } finally {
                    bitmap.recycle()
                }
            }.getOrDefault(placeholder)
        }
    }
    val dark = Color(0xFF151219)
    val animation = if (reducedMotion) snap() else tween<Color>(360)
    val top by animateColorAsState(lerp(dark, palette.first, .52f), animation, label = "player-primary-art-tone")
    val bottom by animateColorAsState(lerp(dark, palette.second, .34f), animation, label = "player-secondary-art-tone")
    val floor = lerp(dark, bottom, .44f)
    val heroScale = remember(sheet) {
        object : ContentScale {
            override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
                val crop = ContentScale.Crop.computeScaleFactor(srcSize, dstSize).scaleX
                val fit = ContentScale.Fit.computeScaleFactor(srcSize, dstSize).scaleX
                // Preserve the thumbnail crop at the lower anchor.
                val scale = androidx.compose.ui.util.lerp(crop, minOf(crop, fit * 1.08f), sheet.progress)
                return ScaleFactor(scale, scale)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(miniSurface)) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = sheet.progress }
                .background(Brush.verticalGradient(0f to top, .44f to top, .73f to bottom, 1f to floor))
        )
        Box(
            Modifier.morphBounds { width, height ->
                val mini = sheet.miniInHost() ?: Rect(0f, height * .85f, width, height)
                val edge = 42.dp.toPx()
                val start = Offset(mini.left + 10.dp.toPx(), mini.center.y - edge / 2)
                val end = if (wide) {
                    Rect(0f, 0f, width * .60f, height)
                } else {
                    val imageTop = statusBars.getTop(this).toFloat()
                    Rect(0f, imageTop, width, maxOf(imageTop, minOf(height * .68f, imageTop + width * 1.15f)))
                }
                interpolateBounds(Rect(start, Size(edge, edge)), end, sheet.progress)
            }.graphicsLayer {
                clip = true
                shape = RoundedCornerShape((10f * (1f - sheet.progress)).dp)
                compositingStrategy = CompositingStrategy.Offscreen
            }.drawWithCache {
                if (size.minDimension <= 0f) return@drawWithCache onDrawWithContent { drawContent() }
                val opaque = Color.Black
                val intrinsic = hero?.intrinsicSize ?: Size.Unspecified
                val source = intrinsic.takeIf { it != Size.Unspecified && it.width > 0f && it.height > 0f } ?: size
                val scale = heroScale.computeScaleFactor(source, size).scaleX
                val drawnHeight = minOf(source.height * scale, size.height)
                val drawnTop = ((size.height - drawnHeight) / 2f).coerceAtLeast(0f)
                val topStop = (drawnTop / size.height.coerceAtLeast(1f)).coerceIn(0f, .5f)
                val span = (drawnHeight / size.height.coerceAtLeast(1f)).coerceIn(.01f, 1f - topStop)
                val mask = if (wide) {
                    Brush.horizontalGradient(.38f to Color.Transparent, .76f to opaque.copy(.20f), 1f to opaque)
                } else {
                    Brush.verticalGradient(
                        topStop to opaque,
                        (topStop + .08f * span) to Color.Transparent,
                        (topStop + .54f * span) to Color.Transparent,
                        (topStop + .83f * span) to opaque.copy(.22f),
                        (topStop + span) to opaque,
                    )
                }
                onDrawWithContent {
                    drawContent()
                    val progress = ((sheet.progress - .08f) / .92f).coerceIn(0f, 1f)
                    if (progress > 0f) drawRect(mask, alpha = progress, blendMode = BlendMode.DstOut)
                }
            }.background(Brush.linearGradient(listOf(top, bottom))),
            contentAlignment = Alignment.Center
        ) {
            if (hero != null) {
                Image(hero, "アルバムアート: $title", Modifier.fillMaxSize(), contentScale = heroScale)
            } else {
                Icon(Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(.27f), tint = Color.White.copy(.45f))
            }
        }
        // Scrim confines contrast adjustment to the artwork, behind all foreground content.
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = sheet.progress }.background(
                if (alternatePanel) Brush.verticalGradient(listOf(dark.copy(.72f), dark.copy(.62f), Color.Transparent))
                else Brush.verticalGradient(0f to Color.Black.copy(.67f), .24f to Color.Transparent, .42f to Color.Transparent, .63f to dark.copy(.27f), 1f to dark.copy(.72f))
            )
        )
    }
}
