package jp.noppy.lumamusic.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.playback.PlaybackUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Stable
internal class PlayerSheetState(private val scope: CoroutineScope, initiallyExpanded: Boolean) {
    var progress by mutableFloatStateOf(if (initiallyExpanded) 1f else 0f)
        private set
    var miniBounds by mutableStateOf<Rect?>(null)
    private var hostOrigin by mutableStateOf(Offset.Zero)
    private var hostHeight by mutableFloatStateOf(1f)
    private var targetExpanded by mutableStateOf(initiallyExpanded)
    private var dragging = false
    private var animation: Job? = null
    var reducedMotion = false

    val active: Boolean get() = targetExpanded || progress > 0f
    val expanded: Boolean get() = targetExpanded && progress == 1f
    private val travel: Float get() = ((miniBounds?.top?.minus(hostOrigin.y) ?: (hostHeight * .85f))).coerceAtLeast(1f)

    fun layout(origin: Offset, height: Int) {
        hostOrigin = origin
        hostHeight = height.toFloat()
    }

    fun miniInHost(): Rect? = miniBounds?.translate(-hostOrigin)

    fun open() = settle(expand = true)
    fun close() = settle(expand = false)

    fun canDrag(opening: Boolean) = if (opening) !active else expanded

    fun beginDrag() {
        animation?.cancel()
        dragging = true
    }

    fun dragBy(deltaY: Float) {
        if (dragging) progress = (progress - deltaY / travel).coerceIn(0f, 1f)
    }

    fun finishDrag(opening: Boolean, velocityY: Float, minFlingVelocity: Float, cancelled: Boolean) {
        if (!dragging) return
        val expand = when {
            cancelled -> !opening
            velocityY < -minFlingVelocity -> true
            velocityY > minFlingVelocity -> false
            opening -> progress >= .30f
            else -> progress > .70f
        }
        settle(expand, if (cancelled) 0f else -velocityY / travel)
    }

    private fun settle(expand: Boolean, velocity: Float = 0f) {
        dragging = false
        animation?.cancel()
        targetExpanded = expand
        val end = if (expand) 1f else 0f
        if (reducedMotion || progress == end) {
            progress = end
            return
        }
        animation = scope.launch {
            AnimationState(progress, initialVelocity = velocity.coerceIn(-4f, 4f)).animateTo(
                targetValue = end,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 440f, visibilityThreshold = .002f)
            ) { progress = value.coerceIn(0f, 1f) }
            progress = end
        }
    }

    companion object {
        fun saver(scope: CoroutineScope): Saver<PlayerSheetState, Boolean> = Saver(
            save = { it.targetExpanded },
            restore = { PlayerSheetState(scope, it) }
        )
    }
}

@Composable
internal fun rememberPlayerSheetState(): PlayerSheetState {
    val scope = rememberCoroutineScope()
    return rememberSaveable(saver = remember(scope) { PlayerSheetState.saver(scope) }) {
        PlayerSheetState(scope, initiallyExpanded = false)
    }
}

/**
 * Installed only on the mini player's identity area and the expanded player's
 * header/artwork. A horizontal or wrong-way movement leaves the event unconsumed.
 */
internal fun Modifier.playerSheetDrag(
    state: PlayerSheetState,
    opening: Boolean,
    enabled: Boolean = true
): Modifier = if (!enabled) this else pointerInput(state, opening) {
    val minFlingVelocity = 730.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!state.canDrag(opening)) return@awaitEachGesture
        var distanceY = 0f
        var axisChosen = false
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, Offset.Zero)
        val start = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
            val allowedDirection = if (opening) overSlop.y < 0f else overSlop.y > 0f
            if (!axisChosen && allowedDirection && abs(overSlop.y) > abs(overSlop.x)) {
                change.consume()
                distanceY = overSlop.y
            }
            axisChosen = true
        } ?: return@awaitEachGesture
        tracker.addPosition(start.uptimeMillis, Offset(0f, distanceY))
        state.beginDrag()
        state.dragBy(distanceY)
        try {
            val completed = drag(start.id) { change ->
                val deltaY = change.positionChange().y
                distanceY += deltaY
                tracker.addPosition(change.uptimeMillis, Offset(0f, distanceY))
                state.dragBy(deltaY)
                change.consume()
            }
            // Include release time so a quick pull followed by a pause is not a fling.
            currentEvent.changes.firstOrNull { it.id == start.id }?.let {
                tracker.addPosition(it.uptimeMillis, Offset(0f, distanceY))
            }
            state.finishDrag(opening, tracker.calculateVelocity().y, minFlingVelocity, !completed)
        } finally {
            state.finishDrag(opening, 0f, minFlingVelocity, cancelled = true)
        }
    }
}

/** The route beneath the sheet stays composed and keeps its scroll/search state. */
@Composable
internal fun PlayerSheet(
    sheet: PlayerSheetState,
    vm: LumaViewModel,
    playback: PlaybackUi,
    track: Track?,
    favorite: Boolean,
    standby: StandbyController,
    output: () -> Unit,
    sound: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reducedMotion = LocalReducedMotion.current
    SideEffect { sheet.reducedMotion = reducedMotion }
    val title = track?.title ?: playback.title
    val artist = track?.artist ?: playback.artist
    val artwork = track?.artwork ?: playback.artwork
    val miniSurface = MaterialTheme.colorScheme.surface
    val miniText = MaterialTheme.colorScheme.onSurface
    val hero = rememberPlayerArtwork(artwork, reducedMotion)
    var panel by rememberSaveable { mutableStateOf("player") }
    BoxWithConstraints(modifier.onGloballyPositioned { sheet.layout(it.positionInRoot(), it.size.height) }) {
        if (!sheet.active) return@BoxWithConstraints
        val wide = maxWidth > maxHeight
        val panelModifier = Modifier.fillMaxSize().drawWithCache {
            val outline = Path()
            onDrawWithContent {
                val progress = sheet.progress
                if (progress == 1f) drawContent()
                else {
                    val full = Rect(Offset.Zero, size)
                    val mini = sheet.miniInHost() ?: Rect(0f, size.height * .85f, size.width, size.height)
                    outline.reset()
                    outline.addRoundRect(RoundRect(interpolateBounds(mini, full, progress), CornerRadius(24.dp.toPx() * (1f - progress))))
                    clipPath(outline) { this@onDrawWithContent.drawContent() }
                }
            }
        }.pointerInput(Unit) {
            // Make the topmost sheet modal while keeping the opening gesture's
            // existing hit path alive in the mini player beneath it.
            awaitPointerEventScope { while (true) awaitPointerEvent() }
        }.then(if (sheet.expanded) Modifier else Modifier.clearAndSetSemantics {})
        Box(panelModifier) {
            PlayerScreen(vm, playback, track, favorite, standby, output, sheet::close, sound, sheet, hero, miniSurface, wide, panel) { panel = it }
            // The miniature text follows its starting plate before the expanded
            // information and transport become visible. Artwork stays in one layer.
            Row(
                Modifier.morphBounds { width, height ->
                    val mini = sheet.miniInHost() ?: Rect(0f, height * .85f, width, height)
                    val left = mini.left + 64.dp.toPx()
                    val top = lerp(mini.top, 0f, sheet.progress)
                    Rect(left, top, (mini.right - 10.dp.toPx()).coerceAtLeast(left), top + mini.height)
                }.graphicsLayer { alpha = (1f - sheet.progress / .34f).coerceIn(0f, 1f) }
                    .clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    TrackText(title, style = MaterialTheme.typography.titleSmall, color = miniText)
                    if (artist.isNotBlank()) TrackText(artist, style = MaterialTheme.typography.bodySmall, color = miniText)
                }
                Box(Modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(27.dp), tint = miniText)
                }
                Box(Modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SkipNext, null, Modifier.size(27.dp), tint = miniText)
                }
            }
        }
    }
}

internal fun interpolateBounds(start: Rect, end: Rect, progress: Float): Rect = Rect(
    lerp(start.left, end.left, progress),
    lerp(start.top, end.top, progress),
    lerp(start.right, end.right, progress),
    lerp(start.bottom, end.bottom, progress)
)

/** A small dynamic layout for the artwork crop; other controls animate in their layers. */
internal fun Modifier.morphBounds(bounds: androidx.compose.ui.unit.Density.(Float, Float) -> Rect): Modifier =
    layout { measurable, constraints ->
        val rect = bounds(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val placeable = measurable.measure(Constraints.fixed(rect.width.roundToInt().coerceAtLeast(0), rect.height.roundToInt().coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
        }
    }
