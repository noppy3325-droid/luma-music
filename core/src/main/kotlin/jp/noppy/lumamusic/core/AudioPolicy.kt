package jp.noppy.lumamusic.core

data class PcmSpec(val rate: Int, val channels: Int, val encoding: Int)
data class MixerOption(val spec: PcmSpec, val bitPerfect: Boolean)
enum class OutputMode { STANDARD, USB_HIGH_QUALITY, BIT_PERFECT }

fun chooseMixer(mode: OutputMode, source: PcmSpec?, options: List<MixerOption>, unprocessed: Boolean = true): MixerOption? {
    if (mode == OutputMode.STANDARD || source == null || source.rate <= 0 || source.channels <= 0 || source.encoding <= 0) return null
    if (mode == OutputMode.BIT_PERFECT && !unprocessed) return null
    return options.firstOrNull { it.spec == source && it.bitPerfect == (mode == OutputMode.BIT_PERFECT) }
}
fun shouldPauseOnRemoval(activeId: Int?, preferredId: Int?, removedIds: Set<Int>): Boolean =
    (activeId != null && activeId in removedIds) || (preferredId != null && preferredId in removedIds)

fun shouldPauseOnRouteChange(previousWasUsb: Boolean, previousId: Int?, currentId: Int?): Boolean =
    previousWasUsb && previousId != null && currentId != null && previousId != currentId
