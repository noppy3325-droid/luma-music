package jp.noppy.lumamusic.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EmbeddedTagsTest {
    @Test fun extractsId3UnsynchronizedLyrics() {
        val payload = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + "日本語の歌詞".toByteArray()
        val frame = "USLT".toByteArray() + ByteBuffer.allocate(4).putInt(payload.size).array() + byteArrayOf(0, 0) + payload
        val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, frame.size.toByte()) + frame
        assertEquals("日本語の歌詞", readEmbeddedTags(tag).lyrics)
    }
    @Test fun extractsFlacBitDepthAndVorbisLyrics() {
        val stream = ByteArray(34)
        // STREAMINFO: 44.1kHz, stereo, 24-bit.
        val packed = (44100L shl 44) or (1L shl 41) or (23L shl 36)
        ByteBuffer.wrap(stream, 10, 8).putLong(packed)
        val comment = "LYRICS=[00:01.00]FLAC lyrics".toByteArray()
        val comments = ByteBuffer.allocate(12 + comment.size).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(1).putInt(comment.size).put(comment).array()
        val tag = "fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + stream + byteArrayOf(0x84.toByte(), 0, 0, comments.size.toByte()) + comments
        assertEquals(24, readEmbeddedTags(tag).bitDepth)
        assertEquals("[00:01.00]FLAC lyrics", readEmbeddedTags(tag).lyrics)
    }
    @Test fun truncatedTagsAreSafe() { assertEquals(EmbeddedTags(), readEmbeddedTags("ID3".toByteArray())); assertEquals(EmbeddedTags(), readEmbeddedTags(byteArrayOf())) }
    @Test fun bitPerfectRequiresKnownUnchangedSourceAndUnityProcessing() {
        val pcm = PcmSpec(44100, 2, 2)
        assertTrue(unchangedPcm(pcm, pcm, 1f, 1f, false))
        assertFalse(unchangedPcm(null, pcm, 1f, 1f, false))
        assertFalse(unchangedPcm(pcm.copy(encoding = 21), pcm, 1f, 1f, false))
        assertFalse(unchangedPcm(pcm, pcm.copy(rate = 48000), 1f, 1f, false))
        assertFalse(unchangedPcm(pcm, pcm, .9f, 1f, false))
        assertFalse(unchangedPcm(pcm, pcm, 1f, 1.2f, false))
        assertFalse(unchangedPcm(pcm, pcm, 1f, 1f, true))
    }
}
