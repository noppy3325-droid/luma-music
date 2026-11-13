package jp.noppy.lumamusic.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.shadow.Shadow
import jp.noppy.lumamusic.playback.PlaybackUi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin

@Composable fun FloatingGlass(modifier: Modifier = Modifier, radius: Int = 28, padding: PaddingValues = PaddingValues(12.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.liquidSurface(radius).padding(padding), content = content)
}

/** UI-only component: transport events are supplied by the playback owner. */
@Composable internal fun LumaGlassMiniPlayer(
    playback: PlaybackUi,
    modifier: Modifier,
    trackGesture: Modifier,
    open: () -> Unit,
    toggle: () -> Unit,
    next: () -> Boolean,
    previous: () -> Boolean,
    tracks: @Composable BoxScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()
        .liquidSurface(LumaDock.MiniRadius, highlight = LumaDock.GlassHighlight, shadow = NavigationShadow)
        .padding(horizontal = 10.dp)) {
        Row(Modifier.height(62.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).fillMaxHeight().clipToBounds().then(trackGesture)
                    .clickable(role = Role.Button, onClickLabel = "プレイヤーを開く", onClick = open)
                    .semantics {
                        customActions = buildList {
                            if (playback.previousItem != null) add(CustomAccessibilityAction("前の曲", previous))
                            if (playback.nextItem != null) add(CustomAccessibilityAction("次の曲", next))
                        }
                    },
                content = tracks,
            )
            IconButton(toggle) { Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "一時停止" else "再生", Modifier.size(27.dp)) }
            IconButton({ next() }, enabled = playback.nextItem != null) { Icon(Icons.Rounded.SkipNext, "次の曲", Modifier.size(27.dp)) }
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

private val NavigationShadow = Shadow(radius = 14.dp, color = Color.Black.copy(alpha = .08f))

@Composable internal fun LumaGlassTabBar(
    pager: PagerState,
    swipeEnabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val reverse = ScrollableDefaults.reverseDirection(LocalLayoutDirection.current, Orientation.Horizontal, reverseScrolling = false)
    val scrollState = remember(pager) { TabStripScrollState(pager) }
    val minFlingVelocity = with(LocalDensity.current) { 620.dp.toPx() }
    val flingBehavior = remember(scrollState, reduced, minFlingVelocity) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val position = pager.indicatorPage(reduced = false)
                val drag = position - scrollState.startPage
                val direction = when {
                    abs(drag) >= .30f -> drag.sign.toInt()
                    abs(drag) >= .045f && abs(initialVelocity) >= minFlingVelocity -> initialVelocity.sign.toInt()
                    else -> 0
                }
                val target = (scrollState.startPage + direction).coerceIn(0, pager.pageCount - 1)
                val distance = (target - position) * (pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing)
                if (reduced) {
                    scrollBy(distance)
                } else {
                    var consumed = 0f
                    AnimationState(0f).animateTo(distance, spring(dampingRatio = .95f, stiffness = Spring.StiffnessMedium)) {
                        consumed += scrollBy(value - consumed)
                    }
                    scrollBy(distance - consumed)
                }
                return 0f
            }
        }
    }
    Box(
        modifier.widthIn(max = 416.dp).fillMaxWidth()
            .scrollable(scrollState, Orientation.Horizontal, enabled = swipeEnabled, reverseDirection = reverse, flingBehavior = flingBehavior)
            .liquidSurface(LumaDock.NavigationRadius, highlight = LumaDock.GlassHighlight, shadow = NavigationShadow)
            .padding(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(LumaShapes.Pill)
                .liquidSelectionIndicator(pager, reduced)
                .selectableGroup(),
        ) {
            LumaTabs.forEachIndexed { index, tab ->
                NavigationTab(tab, pager, index, reduced, { onSelect(index) }, Modifier.weight(1f))
            }
        }
    }
}

/** Keep the origin through the gap between the drag and fling scroll mutations. */
private class TabStripScrollState(private val pager: PagerState) : ScrollableState by pager {
    var startPage: Int = pager.currentPage
        private set

    override suspend fun scroll(scrollPriority: MutatePriority, block: suspend ScrollScope.() -> Unit) {
        pager.scroll(scrollPriority) {
            if (scrollPriority == MutatePriority.UserInput) startPage = pager.currentPage
            block()
        }
    }
}

@Composable private fun NavigationTab(tab: LumaTab, pager: PagerState, index: Int, reduced: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    val chosen = pager.settledPage == index
    val strength = (1f - abs(pager.indicatorPage(reduced) - index)).coerceIn(0f, 1f)
    val tint = lerp(LocalContentColor.current.copy(alpha = .79f), MaterialTheme.colorScheme.primary, strength)
    Column(
        modifier.heightIn(min = 56.dp).clip(LumaShapes.Pill)
            .selectable(chosen, role = Role.Tab, onClick = onSelect).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tab.icon, null, Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.height(3.dp))
        Text(tab.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, color = tint)
    }
}

private fun PagerState.indicatorPage(reduced: Boolean): Float =
    if (reduced) settledPage.toFloat() else currentPage + currentPageOffsetFraction

@Composable private fun Modifier.liquidSelectionIndicator(pager: PagerState, reduced: Boolean): Modifier {
    val scheme = MaterialTheme.colorScheme
    val wash = Brush.verticalGradient(
        listOf(scheme.primary.copy(alpha = .15f), scheme.primary.copy(alpha = .08f)),
    )
    val edge = Brush.verticalGradient(
        listOf(scheme.onSurface.copy(alpha = .10f), scheme.onSurface.copy(alpha = .025f)),
    )
    return drawWithCache {
        val tabWidth = size.width / LumaTabs.size
        val radius = CornerRadius(size.height / 2f)
        val stroke = Stroke(.65.dp.toPx())
        val rtl = layoutDirection == LayoutDirection.Rtl
        onDrawBehind {
            val page = pager.indicatorPage(reduced).coerceIn(0f, (LumaTabs.size - 1).toFloat())
            val stretch = 1f + .06f * sin(abs(page - round(page)) * PI).toFloat()
            val width = tabWidth * stretch
            val leading = ((page + .5f) * tabWidth - width / 2f).coerceIn(0f, size.width - width)
            val left = if (rtl) size.width - leading - width else leading
            val bounds = Size(width, size.height)
            drawRoundRect(wash, Offset(left, 0f), bounds, radius)
            drawRoundRect(edge, Offset(left, 0f), bounds, radius, style = stroke)
        }
    }
}
