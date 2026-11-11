package jp.noppy.lumamusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/** A 3dp visual track inside a 48dp gesture/assistive-technology target. */
@Composable fun LumaSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, range: ClosedFloatingPointRange<Float> = 0f..1f, enabled: Boolean = true, label: String = "再生位置", onFinished: () -> Unit = {}) {
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onFinished)
    var pressed by remember { mutableStateOf(false) }
    val color = LocalContentColor.current.copy(alpha = if (enabled) .82f else .3f)
    val span = (range.endInclusive - range.start).coerceAtLeast(.001f)
    Canvas(modifier.fillMaxWidth().height(48.dp)
        .semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range)
            if (enabled) setProgress { change(it.coerceIn(range)); finish(); true }
        }
        .onKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) false
            else when (event.key) {
                Key.DirectionLeft, Key.DirectionDown -> { change((currentValue - span / 20).coerceIn(range)); finish(); true }
                Key.DirectionRight, Key.DirectionUp -> { change((currentValue + span / 20).coerceIn(range)); finish(); true }
                else -> false
            }
        }
        .focusable(enabled)
        .pointerInput(enabled, range) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                pressed = true
                fun update(x: Float) { change(range.start + (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * span) }
                update(down.position.x)
                drag(down.id) { event -> update(event.position.x); event.consume() }
                pressed = false
                finish()
            }
        }) {
        val trackHeight = (if (pressed) 5.dp else 3.dp).toPx()
        val top = (size.height - trackHeight) / 2
        val fraction = ((currentValue - range.start) / span).coerceIn(0f, 1f)
        drawRoundRect(color.copy(alpha = .2f), Offset(0f, top), Size(size.width, trackHeight), CornerRadius(trackHeight))
        drawRoundRect(color, Offset(0f, top), Size(size.width * fraction, trackHeight), CornerRadius(trackHeight))
    }
}
