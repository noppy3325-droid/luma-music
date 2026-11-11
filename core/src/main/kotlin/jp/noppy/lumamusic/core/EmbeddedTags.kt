package jp.noppy.lumamusic.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class EmbeddedTags(val lyrics: String? = null, val bitDepth: Int? = null)
fun readEmbeddedTags(bytes: ByteArray): EmbeddedTags {
    fun ascii(offset: Int, size: Int) = String(bytes, offset, size, Charsets.ISO_8859_1)
    fun be32(offset: Int): Int = ByteBuffer.wrap(bytes, offset, 4).int
    fun syncSafe(offset: Int) = (0..3).fold(0) { value, i -> (value shl 7) or (bytes[offset + i].toInt() and 127) }
    return runCatching {
        if (bytes.size >= 10 && ascii(0, 3) == "ID3") {
            val version = bytes[3].toInt()
            if (version !in 3..4 || bytes[5].toInt() and 0xC0 != 0) return@runCatching EmbeddedTags()
            val end = minOf(bytes.size.toLong(), 10L + syncSafe(6)).toInt()
            var at = 10
            while (at + 10 <= end) {
                val id = ascii(at, 4)
                val size = if (version == 4) syncSafe(at + 4) else be32(at + 4)
                if (size <= 0 || size.toLong() + at + 10 > end) break
                val flags = bytes[at + 9].toInt() and 255
                if (flags == 0 && id in setOf("USLT", "SYLT")) {
                    val data = bytes.copyOfRange(at + 10, at + 10 + size)
                    decodeLyricsFrame(data, id == "SYLT")?.let { return@runCatching EmbeddedTags(lyrics = it) }
                }
                at += 10 + size
            }
            EmbeddedTags()
        } else if (bytes.size >= 8 && ascii(0, 4) == "fLaC") {
            var at = 4
            var bits: Int? = null
            var lyrics: String? = null
            while (at + 4 <= bytes.size) {
                val type = bytes[at].toInt() and 127
                val last = bytes[at].toInt() and 128 != 0
                val size = (bytes[at + 1].toInt() and 255 shl 16) or (bytes[at + 2].toInt() and 255 shl 8) or (bytes[at + 3].toInt() and 255)
                val start = at + 4
                if (size.toLong() + start > bytes.size) break
                if (type == 0 && size == 34) {
                    val packed = ByteBuffer.wrap(bytes, start + 10, 8).long
                    bits = ((packed ushr 36) and 31).toInt() + 1
                }
                if (type == 4) lyrics = readVorbisComments(bytes.copyOfRange(start, start + size))
                at = start + size
                if (last) break
            }
            EmbeddedTags(lyrics, bits)
        } else {
            val text = String(bytes, Charsets.ISO_8859_1)
            val vorbis = text.indexOf("\u0003vorbis")
            val opus = text.indexOf("OpusTags")
            val start = if (vorbis >= 0) vorbis + 7 else if (opus >= 0) opus + 8 else -1
            if (start >= 0) EmbeddedTags(lyrics = readVorbisComments(bytes.copyOfRange(start, bytes.size))) else EmbeddedTags()
        }
    }.getOrDefault(EmbeddedTags())
}

private fun decodeLyricsFrame(data: ByteArray, synced: Boolean): String? {
    if (data.size < if (synced) 7 else 5) return null
    val charset = when (data[0].toInt()) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> return null }
    val wide = data[0].toInt() in 1..2
    val step = if (wide) 2 else 1
    fun terminator(start: Int): Int {
        var i = start
        while (i < data.size) { if (data[i] == 0.toByte() && (!wide || i + 1 < data.size && data[i + 1] == 0.toByte())) return i; i += step }
        return data.size
    }
    if (synced && data[4].toInt() != 2) return null // MPEG-frame timestamps cannot be assumed to be milliseconds.
    var at = terminator(if (synced) 6 else 4) + step
    if (at >= data.size) return null
    if (!synced) return String(data, at, data.size - at, charset).trimEnd('\u0000')
    val lines = mutableListOf<String>()
    while (at < data.size) {
        val end = terminator(at)
        val timestampAt = end + step
        if (timestampAt + 4 > data.size) break
        val text = String(data, at, end - at, charset)
        val ms = ByteBuffer.wrap(data, timestampAt, 4).int.toLong() and 0xffffffffL
        lines.add("[%02d:%02d.%03d]%s".format(java.util.Locale.ROOT, ms / 60000, ms / 1000 % 60, ms % 1000, text))
        at = timestampAt + 4
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

private fun readVorbisComments(bytes: ByteArray): String? = runCatching {
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val vendorSize = data.int
    if (vendorSize < 0 || vendorSize > data.remaining() - 4) return@runCatching null
    data.position(data.position() + vendorSize)
    val count = data.int
    if (count !in 0..100000) return@runCatching null
    repeat(count) {
        if (data.remaining() < 4) return@runCatching null
        val size = data.int
        if (size < 0 || size > data.remaining()) return@runCatching null
        val text = ByteArray(size).also(data::get).toString(Charsets.UTF_8)
        if (text.substringBefore('=').uppercase(java.util.Locale.ROOT) in setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS")) return@runCatching text.substringAfter('=')
    }
    null
}.getOrNull()

fun unchangedPcm(source: PcmSpec?, output: PcmSpec?, volume: Float, speed: Float, skipSilence: Boolean): Boolean =
    source != null && source == output && source.encoding > 0 && source.rate > 0 && source.channels > 0 && volume == 1f && speed == 1f && !skipSilence
