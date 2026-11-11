package jp.noppy.lumamusic.core

import org.junit.Assert.*
import org.junit.Test

class AudioPolicyTest {
    private val cd = PcmSpec(44100, 2, 2)
    private val video = PcmSpec(48000, 2, 2)
    @Test fun selectsSourceRateInsteadOfHighestRate() {
        val options = listOf(MixerOption(video, false), MixerOption(cd, false))
        assertEquals(cd, chooseMixer(OutputMode.USB_HIGH_QUALITY, cd, options)?.spec)
        assertEquals(video, chooseMixer(OutputMode.USB_HIGH_QUALITY, video, options)?.spec)
    }
    @Test fun unknownAndEmptyCapabilitiesNeverInventAFormat() {
        assertNull(chooseMixer(OutputMode.USB_HIGH_QUALITY, null, listOf(MixerOption(cd, false))))
        assertNull(chooseMixer(OutputMode.USB_HIGH_QUALITY, cd, emptyList()))
    }
    @Test fun mismatchAndStandardModeUseSystemPath() {
        assertNull(chooseMixer(OutputMode.USB_HIGH_QUALITY, cd, listOf(MixerOption(video, false))))
        assertNull(chooseMixer(OutputMode.STANDARD, cd, listOf(MixerOption(cd, false))))
        assertNull(chooseMixer(OutputMode.USB_HIGH_QUALITY, cd, listOf(MixerOption(cd.copy(encoding = 4), false))))
    }
    @Test fun bitPerfectRequiresAdvertisedBehaviorAndUnprocessedPcm() {
        assertNull(chooseMixer(OutputMode.BIT_PERFECT, cd, listOf(MixerOption(cd, false))))
        assertNull(chooseMixer(OutputMode.BIT_PERFECT, cd, listOf(MixerOption(cd, true)), false))
        assertEquals(MixerOption(cd, true), chooseMixer(OutputMode.BIT_PERFECT, cd, listOf(MixerOption(cd, true))))
        assertNull(chooseMixer(OutputMode.USB_HIGH_QUALITY, cd, listOf(MixerOption(cd, true))))
    }
    @Test fun pausesOnlyWhenActualOrPendingRouteIsRemoved() {
        assertTrue(shouldPauseOnRemoval(10, null, setOf(10)))
        assertTrue(shouldPauseOnRemoval(null, 11, setOf(11)))
        assertFalse(shouldPauseOnRemoval(10, 11, setOf(12)))
        assertFalse(shouldPauseOnRemoval(null, null, setOf(10)))
    }
    @Test fun pausesIfUsbRouteChangesBeforeDeviceRemovalCallback() {
        assertTrue(shouldPauseOnRouteChange(true, 10, 3))
        assertFalse(shouldPauseOnRouteChange(true, 10, 10))
        assertFalse(shouldPauseOnRouteChange(false, 3, 10))
        assertFalse(shouldPauseOnRouteChange(true, null, 3))
    }
}
