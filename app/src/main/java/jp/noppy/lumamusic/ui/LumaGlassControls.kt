package jp.noppy.lumamusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import jp.noppy.lumamusic.playback.PlaybackUi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable fun FloatingGlass(modifier: Modifier = Modifier, radius: Int = 28, padding: PaddingValues = PaddingValues(12.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.liquidSurface(radius).padding(padding), content = content)
}

/** UI-only component: transport events are supplied by the playback owner. */
@Composable fun LumaGlassMiniPlayer(playback: PlaybackUi, modifier: Modifier = Modifier, openGesture: Modifier = Modifier, open: () -> Unit, toggle: () -> Unit, next: () -> Unit) {
    FloatingGlass(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = open), 24, PaddingValues(horizontal = 10.dp)) {
        Row(Modifier.height(62.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxHeight().then(openGesture), verticalAlignment = Alignment.CenterVertically) {
                Artwork(playback.artwork, playback.title, Modifier.size(42.dp), 10)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    TrackText(playback.title, style = MaterialTheme.typography.titleSmall)
                    if (playback.artist.isNotBlank()) TrackText(playback.artist, style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(toggle) { Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "一時停止" else "再生", Modifier.size(27.dp)) }
            IconButton(next) { Icon(Icons.Rounded.SkipNext, "次の曲", Modifier.size(27.dp)) }
        }
        if (playback.duration > 0) LinearProgressIndicator(progress = { (playback.position.toFloat() / playback.duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(1.dp), color = LocalContentColor.current.copy(.5f), trackColor = Color.Transparent, drawStopIndicator = {})
    }
}

internal data class LumaTab(val key: String, val label: String, val icon: ImageVector)

internal val LumaTabs = listOf(
    LumaTab("library", "ライブラリ", Icons.Rounded.LibraryMusic),
    LumaTab("search", "検索", Icons.Rounded.Search),
    LumaTab("playlists", "プレイリスト", Icons.Rounded.PlaylistPlay),
)

@Composable internal fun LumaGlassTabBar(pager: PagerState, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    // 68dp outer capsule, 58dp inset capsule: radii 34 / 29 share the same center.
    FloatingGlass(modifier.fillMaxWidth(), 34, PaddingValues(5.dp)) {
        Box(Modifier.fillMaxWidth().height(58.dp).clip(LumaShapes.Pill)) {
            LiquidSelectionIndicator(pager, reduced, Modifier.matchParentSize())
            Row(Modifier.fillMaxSize().selectableGroup()) {
                LumaTabs.forEachIndexed { index, tab ->
                    val chosen = pager.settledPage == index
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(LumaShapes.Pill)
                            .selectable(chosen, role = Role.Tab, onClick = { onSelect(index) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val tint = if (chosen) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = .8f)
                        Icon(tab.icon, null, Modifier.size(23.dp), tint = tint)
                        Spacer(Modifier.height(2.dp))
                        Text(tab.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, color = tint)
                    }
                }
            }
        }
    }
}

@Composable private fun LiquidSelectionIndicator(pager: PagerState, reduced: Boolean, modifier: Modifier) {
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .09f)
    Box(
        modifier.layout { measurable, constraints ->
            val position = if (reduced) pager.settledPage.toFloat() else pager.currentPage + pager.currentPageOffsetFraction
            val page = position.coerceIn(0f, (LumaTabs.size - 1).toFloat())
            val tabWidth = constraints.maxWidth.toFloat() / LumaTabs.size
            val betweenPages = abs(page - page.roundToInt())
            val stretch = if (reduced) 1f else 1f + .08f * sin(betweenPages * PI).toFloat()
            val width = (tabWidth * stretch).roundToInt().coerceIn(0, constraints.maxWidth)
            val left = ((page + .5f) * tabWidth - width / 2f).roundToInt().coerceIn(0, constraints.maxWidth - width)
            val surface = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) { surface.placeRelative(left, 0) }
        }
            .liquidSurface(29)
            .background(tint, LumaShapes.Pill),
    )
}
