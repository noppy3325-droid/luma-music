package jp.noppy.lumamusic.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable fun DragHandle(index: Int, count: Int, onMove: (Int, Int) -> Unit) {
    val rowHeight = with(LocalDensity.current) { 72.dp.toPx() }
    val move by rememberUpdatedState(onMove)
    Icon(Icons.Rounded.DragHandle, "長押ししてドラッグで並べ替え", Modifier.size(40.dp).padding(8.dp).pointerInput(index, count) {
        var distance = 0f
        detectDragGesturesAfterLongPress(onDragStart = { distance = 0f }, onDrag = { change, amount -> change.consume(); distance += amount.y }, onDragEnd = {
            val destination = (index + (distance / rowHeight).roundToInt()).coerceIn(0, (count - 1).coerceAtLeast(0))
            if (destination != index) move(index, destination)
        })
    })
}
