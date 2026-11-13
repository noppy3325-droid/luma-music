package jp.noppy.lumamusic.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniTrackSwipeTest {
    @Test fun initialMovementLocksOnlyOneAxis() {
        assertEquals(MiniDragAxis.TRACK, miniDragAxis(-35f, -10f))
        assertEquals(MiniDragAxis.SHEET, miniDragAxis(10f, -35f))
        assertNull(miniDragAxis(0f, 35f))
    }

    @Test fun distanceOrAnAlignedFlickSelectsExactlyOneNeighbor() {
        assertEquals(MiniTrackDirection.NEXT, miniTrackSwipe(-50f, 200f, 0f, 600f))
        assertEquals(MiniTrackDirection.PREVIOUS, miniTrackSwipe(20f, 200f, 700f, 600f))
        assertNull(miniTrackSwipe(20f, 200f, -700f, 600f))
        assertNull(miniTrackSwipe(5f, 200f, 900f, 600f))
    }
}
