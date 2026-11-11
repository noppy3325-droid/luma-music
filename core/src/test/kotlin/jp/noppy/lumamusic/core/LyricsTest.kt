package jp.noppy.lumamusic.core

import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    @Test fun lrcSupportsFractionsRepeatedTimestampsAndOffset() {
        val parsed = parseLrc("[offset:-100]\n[00:01.20][00:03.456]歌詞\n[00:02]次の行\n[ar:歌手]")
        assertEquals(listOf(1100L, 1900L, 3356L), parsed.map { it.timeMs })
        assertEquals(listOf("歌詞", "次の行", "歌詞"), parsed.map { it.text })
    }
    @Test fun plainAndMalformedLyricsRemainReadable() {
        assertEquals(listOf(LyricLine(null, "first"), LyricLine(null, "[xx:yy]second")), parseLrc("first\n[xx:yy]second"))
        assertTrue(parseLrc("").isEmpty())
    }
    @Test fun japaneseWidthKanaAndCaseNormalize() {
        assertEquals(normalizedSearch("ＡＢＣ カタカナ"), normalizedSearch("abc かたかな"))
    }
    @Test fun albumArtistKeepsCompilationsTogetherAndNamesakesApart() {
        assertEquals(albumKey("Hits", "Various", "A"), albumKey("Hits", "Various", "B"))
        assertNotEquals(albumKey("Hits", "", "A"), albumKey("Hits", "", "B"))
    }
}
