package jp.noppy.lumamusic.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class PlayerSheetStateTest {
    @Test fun cancelledOpeningReturnsToMiniPlayerEvenAfterCrossingTheThreshold() {
        val sheet = sheet(initiallyExpanded = false)
        assertTrue(sheet.beginDrag())
        sheet.dragBy(-450f)
        sheet.finishDrag(-1000f, minFlingVelocity = 730f, cancelled = true)
        assertFalse(sheet.active)
    }

    @Test fun dismissedSheetStaysMountedUntilTheDragEnds() {
        val sheet = sheet(initiallyExpanded = true)
        assertTrue(sheet.beginDrag())
        sheet.dragBy(900f)
        assertTrue(sheet.active)
        assertFalse(sheet.expanded)
        assertFalse(sheet.beginDrag())
        sheet.finishDrag(0f, minFlingVelocity = 730f, cancelled = false)
        assertFalse(sheet.active)
    }

    private fun sheet(initiallyExpanded: Boolean) =
        PlayerSheetState(CoroutineScope(EmptyCoroutineContext), initiallyExpanded).apply {
            reducedMotion = true
            layout(Offset.Zero, 1000)
            miniBounds = Rect(16f, 800f, 384f, 862f)
        }
}
