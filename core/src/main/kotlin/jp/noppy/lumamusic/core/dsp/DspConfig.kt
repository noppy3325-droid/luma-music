package jp.noppy.lumamusic.core.dsp

enum class SoundMode { DIRECT, ENHANCE }
enum class NormalizeMode { TRACK, ALBUM }
data class DspConfig(
    val mode: SoundMode = SoundMode.DIRECT,
    val eqEnabled: Boolean = true,
    val bands: List<Float> = List(10) { 0f },
    val preampDb: Float = 0f,
    val autoEnabled: Boolean = false,
    val normalizeEnabled: Boolean = false,
    val normalizeMode: NormalizeMode = NormalizeMode.TRACK,
    val targetLufs: Float = -16f,
    val widthEnabled: Boolean = false,
    val width: Float = 1f,
    val preset: String = "フラット",
) {
    fun safe() = copy(bands = List(10) { bands.getOrElse(it) { 0f }.takeIf(Float::isFinite)?.coerceIn(-12f, 12f) ?: 0f }, preampDb = preampDb.takeIf(Float::isFinite)?.coerceIn(-12f, 6f) ?: 0f, targetLufs = targetLufs.takeIf(Float::isFinite)?.coerceIn(-23f, -12f) ?: -16f, width = width.takeIf(Float::isFinite)?.coerceIn(0f, 1.2f) ?: 1f)
}
val EQ_FREQUENCIES = doubleArrayOf(31.0,62.0,125.0,250.0,500.0,1000.0,2000.0,4000.0,8000.0,16000.0)
val FACTORY_PRESETS: Map<String,List<Float>> = linkedMapOf(
    "フラット" to List(10){0f}, "バランス" to listOf(1f,1f,0f,-.5f,0f,0f,.5f,1f,.5f,0f),
    "低音強調" to listOf(3f,3f,2f,1f,0f,0f,0f,0f,0f,0f),
    "ボーカル" to listOf(-1f,-1f,-.5f,0f,1f,2f,2f,1f,0f,-.5f),
    "明瞭感" to listOf(0f,0f,-.5f,-1f,0f,1f,1.5f,2f,1f,.5f),
    "ソフト" to listOf(0f,.5f,.5f,0f,0f,0f,-.5f,-1f,-2f,-2f),
)
data class TrackAnalysis(val estimatedLufs: Double?, val peak: Double, val toneGains: List<Float>, val frames: Long, val sampleRate: Int, val complete: Boolean, val tagGainDb: Double? = null, val tagKind: String? = null)
