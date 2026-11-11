package jp.noppy.lumamusic.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import jp.noppy.lumamusic.core.dsp.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LumaAudioProcessorTest {
    @Test fun emptyPipelineInputBeforeFirstPcmIsSafe() {
        val p=LumaAudioProcessor();DspRuntime.settings(DspConfig())
        p.configure(AudioProcessor.AudioFormat(44100,2,C.ENCODING_PCM_16BIT));p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        p.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertFalse(p.output.hasRemaining())
        val a=bytes(44100);p.queueInput(input(a));assertArrayEquals(a,output(p));p.reset()
    }
    private fun bytes(rate:Int=48000,frames:Int=2400):ByteArray {val b=ByteBuffer.allocate(frames*4).order(ByteOrder.nativeOrder());repeat(frames){val x=(5000*sin(2*PI*1000*it/rate)).roundToInt().toShort();b.putShort(x);b.putShort(x)};return b.array()}
    private fun input(bytes:ByteArray)=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also{it.flip()}
    private fun output(p:LumaAudioProcessor):ByteArray {val b=p.output;return ByteArray(b.remaining()).also{b.get(it)}}
    @Test fun actualMedia3AdapterBypassesBytesAndChangesEnhancedPcm(){
        val p=LumaAudioProcessor();DspRuntime.settings(DspConfig());p.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));p.flush()
        val a=bytes();p.queueInput(input(a));assertArrayEquals(a,output(p))
        DspRuntime.settings(DspConfig(mode=SoundMode.ENHANCE,bands=List(10){if(it==5)-3f else 0f}));p.queueInput(input(a));val changed=output(p);assertFalse(a.contentEquals(changed));assertTrue(DspRuntime.state.value.applied)
        DspRuntime.settings(DspConfig());p.queueInput(input(a));output(p);p.queueInput(input(a));assertArrayEquals(a,output(p));p.reset()
    }
    @Test fun reconfigureRateAndChannelCountWithoutStaleState(){val p=LumaAudioProcessor();DspRuntime.settings(DspConfig(mode=SoundMode.ENHANCE));for(rate in listOf(44100,48000))for(ch in listOf(1,2)){p.configure(AudioProcessor.AudioFormat(rate,ch,C.ENCODING_PCM_16BIT));p.flush();val b=ByteBuffer.allocate(400*ch).order(ByteOrder.nativeOrder());repeat(200*ch){b.putShort(100)};p.queueInput(input(b.array()));assertArrayEquals(b.array(),output(p))};p.reset()}
}
