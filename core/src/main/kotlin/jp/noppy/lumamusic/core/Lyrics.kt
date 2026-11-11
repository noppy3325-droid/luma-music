package jp.noppy.lumamusic.core

import java.text.Normalizer
import java.util.Locale

data class LyricLine(val timeMs: Long?, val text: String)
fun parseLrc(text: String): List<LyricLine> {
    val timestamp = Regex("\\[(\\d{1,3}):([0-5]?\\d)(?:[.:](\\d{1,3}))?]")
    val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
    val synced = mutableListOf<LyricLine>()
    val plain = mutableListOf<LyricLine>()
    text.lineSequence().forEach { line ->
        val tags = timestamp.findAll(line).toList()
        if (tags.isNotEmpty()) {
            val words = line.substring(tags.last().range.last + 1).trim()
            tags.forEach { match ->
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
                val time = match.groupValues[1].toLong() * 60000 + match.groupValues[2].toLong() * 1000 + fraction + offset
                synced.add(LyricLine(time.coerceAtLeast(0), words))
            }
        } else if (line.isNotBlank() && !Regex("^\\[(ar|ti|al|by|offset|length):.*]$", RegexOption.IGNORE_CASE).matches(line.trim())) plain.add(LyricLine(null, line.trim()))
    }
    return if (synced.isEmpty()) plain else synced.sortedBy { it.timeMs }
}
fun normalizedSearch(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    .map { if (it in '\u30a1'..'\u30f6') (it.code - 0x60).toChar() else it }.joinToString("").trim()
fun albumKey(album: String, albumArtist: String, artist: String): String = "$album\u0000${albumArtist.ifBlank { artist }}"
