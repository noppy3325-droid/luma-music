package jp.noppy.lumamusic.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val paletteCache = LruCache<String, Int>(64)

/** Cached low-frequency artwork palette; never blur or decode the full artwork each frame. */
@Composable fun AlbumBackdrop(artwork: String?, title: String, reducedMotion: Boolean, background: Color) {
    val context = LocalContext.current
    val palette by produceState(Color(0xFF957787), artwork) {
        value = withContext(Dispatchers.IO) {
            if (artwork == null) return@withContext Color(0xFF957787)
            paletteCache.get(artwork)?.let { return@withContext Color(it) }
            runCatching {
                val bitmap = context.contentResolver.openInputStream(Uri.parse(artwork))?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 }) }
                if (bitmap == null) Color(0xFF957787) else try {
                    val color = Palette.from(bitmap).maximumColorCount(8).generate().getDominantColor(0xFF957787.toInt())
                    paletteCache.put(artwork, color)
                    Color(color)
                } finally { bitmap.recycle() }
            }.getOrDefault(Color(0xFF957787))
        }
    }
    val tone by animateColorAsState(palette, if (reducedMotion) snap() else tween(450), label = "artwork-palette")
    val wash = lerp(background, tone, .20f)
    val reflected = lerp(background, Color(tone.blue, tone.red, tone.green), .14f)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(background, wash, background))).drawWithCache {
        val reflection = Brush.radialGradient(listOf(reflected, Color.Transparent), Offset(size.width * .15f, size.height * .28f), size.maxDimension * .85f)
        onDrawBehind { drawRect(reflection) }
    })
}
