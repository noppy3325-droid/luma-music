package jp.noppy.lumamusic.audio

import jp.noppy.lumamusic.core.*

/** Output policy only: the Media3 player/session and queue never change owners. */
interface AudioOutputEngine {
    val mode: OutputMode
    fun select(source: PcmSpec?, options: List<MixerOption>, unprocessed: Boolean): MixerOption?
}
class StandardMedia3Engine : AudioOutputEngine {
    override val mode = OutputMode.STANDARD
    override fun select(source: PcmSpec?, options: List<MixerOption>, unprocessed: Boolean): MixerOption? = null
}
class UsbHighQualityEngine : AudioOutputEngine {
    override val mode = OutputMode.USB_HIGH_QUALITY
    override fun select(source: PcmSpec?, options: List<MixerOption>, unprocessed: Boolean) = chooseMixer(mode, source, options, unprocessed)
}
class UsbBitPerfectEngine : AudioOutputEngine {
    override val mode = OutputMode.BIT_PERFECT
    override fun select(source: PcmSpec?, options: List<MixerOption>, unprocessed: Boolean) = chooseMixer(mode, source, options, unprocessed)
}
