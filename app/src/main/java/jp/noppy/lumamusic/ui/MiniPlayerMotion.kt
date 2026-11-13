package jp.noppy.lumamusic.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import jp.noppy.lumamusic.playback.PlaybackController
import jp.noppy.lumamusic.playback.PlaybackUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal enum class MiniDragAxis { TRACK, SHEET }
internal enum class MiniTrackDirection { NEXT, PREVIOUS }

internal fun miniDragAxis(x: Float, y: Float): MiniDragAxis? = when {
    abs(x) > abs(y) -> MiniDragAxis.TRACK
    y < 0f -> MiniDragAxis.SHEET
    else -> null
}

internal fun miniTrackSwipe(distance: Float, width: Float, velocity: Float, fling: Float): MiniTrackDirection? {
    if (width <= 0f) return null
    val fraction = abs(distance) / width
    val alignedFlick = fraction >= .045f && abs(velocity) >= fling && distance * velocity > 0f
    if (fraction < .23f && !alignedFlick) return null
    return if (distance < 0f) MiniTrackDirection.NEXT else MiniTrackDirection.PREVIOUS
}

@Stable
internal class MiniPlayerMotion(private val scope: CoroutineScope) {
    var base by mutableStateOf<PlaybackUi?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    var previewDirection by mutableStateOf<MiniTrackDirection?>(null)
        private set
    private var width = 1f
    private var travel = 0f
    private var animation: Job? = null
    private var expectedIndex: Int? = null
    private var expectedId: String? = null

    fun begin(playback: PlaybackUi, width: Float): Boolean {
        if (expectedIndex != null || width <= 0f) return false
        animation?.cancel()
        clear()
        this.width = width
        base = playback
        return true
    }

    fun dragBy(delta: Float) {
        val playback = base ?: return
        travel = (travel + delta).coerceIn(-width, width)
        val direction = if (travel < 0f) MiniTrackDirection.NEXT else MiniTrackDirection.PREVIOUS
        val hasNeighbor = if (direction == MiniTrackDirection.NEXT) playback.nextItem != null else playback.previousItem != null
        previewDirection = direction.takeIf { hasNeighbor && travel != 0f }
        offset = if (hasNeighbor) travel else travel * .18f
    }

    fun observe(playback: PlaybackUi) {
        val start = base ?: return
        val stillOnStart = playback.index == start.index && playback.mediaId == start.mediaId
        val matchesTarget = playback.index == expectedIndex && playback.mediaId == expectedId
        if (!stillOnStart && !matchesTarget) {
            animation?.cancel()
            clear()
        }
    }

    fun finish(velocity: Float, fling: Float, cancelled: Boolean, reducedMotion: Boolean, controller: PlaybackController) {
        val start = base ?: return
        val direction = if (cancelled) null else miniTrackSwipe(travel, width, velocity, fling)
        val targetIndex = when (direction) {
            MiniTrackDirection.NEXT -> start.nextIndex
            MiniTrackDirection.PREVIOUS -> start.previousIndex
            null -> null
        }
        val targetId = when (direction) {
            MiniTrackDirection.NEXT -> start.nextItem?.mediaId
            MiniTrackDirection.PREVIOUS -> start.previousItem?.mediaId
            null -> null
        }
        val accepted = targetIndex != null && targetId != null && when (direction) {
            MiniTrackDirection.NEXT -> controller.next(start)
            MiniTrackDirection.PREVIOUS -> controller.previousItem(start)
            null -> false
        }
        if (accepted) {
            expectedIndex = targetIndex
            expectedId = targetId
        }
        animation = scope.launch {
            val confirmed = accepted && withTimeoutOrNull(550) {
                controller.state.first {
                    it.error != start.error || it.index != start.index || it.mediaId != start.mediaId
                }.let { it.index == targetIndex && it.mediaId == targetId && it.error == start.error }
            } == true
            val end = if (!confirmed) 0f else if (direction == MiniTrackDirection.NEXT) -width else width
            if (!reducedMotion && end != offset) {
                AnimationState(offset).animateTo(end, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 570f)) {
                    offset = value
                }
            }
            clear()
        }
    }

    private fun clear() {
        base = null
        previewDirection = null
        expectedIndex = null
        expectedId = null
        offset = 0f
        travel = 0f
    }
}

/** One recognizer owns both axes in the identity area. The transport buttons are siblings. */
internal fun Modifier.miniPlayerDrag(
    sheet: PlayerSheetState,
    motion: MiniPlayerMotion,
    playback: State<PlaybackUi>,
    reducedMotion: State<Boolean>,
    controller: PlaybackController,
): Modifier = pointerInput(sheet, motion, controller) {
    val sheetFling = 730.dp.toPx()
    val trackFling = 620.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!sheet.canDrag(opening = true)) return@awaitEachGesture
        var locked = false
        var axis: MiniDragAxis? = null
        var distance = 0f
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, Offset.Zero)
        val start = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
            if (!locked) {
                locked = true
                axis = miniDragAxis(overSlop.x, overSlop.y)
                if (axis != null) {
                    distance = if (axis == MiniDragAxis.TRACK) overSlop.x else overSlop.y
                    change.consume()
                }
            }
        } ?: return@awaitEachGesture
        val horizontal = axis == MiniDragAxis.TRACK
        if (horizontal) {
            if (!motion.begin(playback.value, size.width.toFloat())) return@awaitEachGesture
            motion.dragBy(distance)
        } else {
            if (!sheet.beginDrag()) return@awaitEachGesture
            sheet.dragBy(distance)
        }
        // Store the active axis in X to give both paths the same velocity handling.
        tracker.addPosition(start.uptimeMillis, Offset(distance, 0f))
        var released = false
        try {
            val completed = drag(start.id) { change ->
                val delta = if (horizontal) change.positionChange().x else change.positionChange().y
                distance += delta
                tracker.addPosition(change.uptimeMillis, Offset(distance, 0f))
                if (horizontal) motion.dragBy(delta) else sheet.dragBy(delta)
                change.consume()
            }
            currentEvent.changes.firstOrNull { it.id == start.id }?.let {
                tracker.addPosition(it.uptimeMillis, Offset(distance, 0f))
            }
            val velocity = tracker.calculateVelocity().x
            if (horizontal) motion.finish(velocity, trackFling, !completed, reducedMotion.value, controller)
            else sheet.finishDrag(velocity, sheetFling, !completed)
            released = true
        } finally {
            if (!released) {
                if (horizontal) motion.finish(0f, trackFling, true, reducedMotion.value, controller)
                else sheet.finishDrag(0f, sheetFling, true)
            }
        }
    }
}

@Composable
internal fun BoxScope.MiniPlayerTracks(playback: PlaybackUi, motion: MiniPlayerMotion, reducedMotion: Boolean) {
    val start = motion.base
    val direction = motion.previewDirection
    val shown = if (reducedMotion) playback else start ?: playback
    val current = MiniTrack(shown.title, shown.artist, shown.artwork)
    val neighbor = when (direction) {
        MiniTrackDirection.NEXT -> start?.nextItem?.let { start.nextIndex to it }
        MiniTrackDirection.PREVIOUS -> start?.previousItem?.let { start.previousIndex to it }
        null -> null
    }
    val cards = buildList {
        add(MiniTrackCard(shown.index, shown.mediaId, current, 0f))
        if (!reducedMotion && neighbor != null) {
            val side = if (direction == MiniTrackDirection.NEXT) 1f else -1f
            add(MiniTrackCard(neighbor.first, neighbor.second.mediaId, neighbor.second.presentation(), side))
        }
    }
    for (card in cards) {
        key(card.index, card.id) {
            val modifier = Modifier.matchParentSize().graphicsLayer {
                translationX = if (reducedMotion) 0f else motion.offset + card.side * size.width
            }
            MiniTrackRow(card.track, if (card.side == 0f) modifier else modifier.clearAndSetSemantics {})
        }
    }
}

private data class MiniTrackCard(val index: Int?, val id: String?, val track: MiniTrack, val side: Float)
private data class MiniTrack(val title: String, val artist: String, val artwork: String?)

private fun MediaItem.presentation() = MiniTrack(
    mediaMetadata.title?.toString().orEmpty(),
    mediaMetadata.artist?.toString().orEmpty(),
    mediaMetadata.artworkUri?.toString(),
)

@Composable
private fun MiniTrackRow(track: MiniTrack, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Artwork(track.artwork, track.title, Modifier.size(42.dp), 10)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            TrackText(track.title, style = MaterialTheme.typography.titleSmall)
            if (track.artist.isNotBlank()) TrackText(track.artist, style = MaterialTheme.typography.bodySmall)
        }
    }
}
