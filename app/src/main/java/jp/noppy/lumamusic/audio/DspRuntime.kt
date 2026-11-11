package jp.noppy.lumamusic.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import jp.noppy.lumamusic.core.dsp.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

data class DspSnapshot(val config:DspConfig=DspConfig(),val analysis:TrackAnalysis?=null,val revision:Long=0)
data class DspStatus(val mode:SoundMode=SoundMode.DIRECT,val sampleRate:Int?=null,val channels:Int?=null,val processedFrames:Long=0,val applied:Boolean=false,val gainDb:Double=0.0,val analysis:String="解析待ち",val error:String?=null)
/** Settings are atomic; the audio callback never touches Room, files, or coroutines. */
object DspRuntime {
    @Volatile var sourceUri:String?=null
        private set
    val snapshot=AtomicReference(DspSnapshot())
    private var tags:Map<String,String> = emptyMap()
    private var rawAnalysis:TrackAnalysis?=null
    private var analysisMessage="解析待ち"
    private val mutable=MutableStateFlow(DspStatus())
    val state=mutable.asStateFlow()
    @Synchronized fun settings(config:DspConfig) { snapshot.updateAndGet{it.copy(config=config.safe(),revision=it.revision+1)}; mutable.update{it.copy(mode=config.mode,applied=true)}; publish() }
    @Synchronized fun analysis(value:TrackAnalysis?,message:String) { rawAnalysis=value;analysisMessage=message;publish() }
    @Synchronized fun metadata(values:Map<String,String>) { tags=tags+values;publish() }
    private fun publish(){val cfg=snapshot.get().config;val tag=parseGainTags(tags,cfg.normalizeMode==NormalizeMode.ALBUM,cfg.targetLufs.toDouble())
        val value=if(tag!=null)(rawAnalysis?:TrackAnalysis(null,tag.peak?:1.0,List(10){0f},0,0,false)).copy(tagGainDb=tag.gainDb,tagKind=tag.kind,peak=tag.peak?:rawAnalysis?.peak?:1.0)else rawAnalysis
        snapshot.updateAndGet{it.copy(analysis=value,revision=it.revision+1)}
        mutable.update{it.copy(analysis=if(tag!=null && cfg.normalizeEnabled)"${tag.kind}タグ優先 / $analysisMessage"else analysisMessage)}
    }
    @Synchronized fun source(uri:String?) { sourceUri=uri;tags=emptyMap();mutable.update{it.copy(sampleRate=null,channels=null,processedFrames=0,applied=true)};analysis(null,"解析待ち") }
    fun audio(rate:Int,channels:Int,frames:Long,applied:Boolean,gain:Double) { mutable.update{it.copy(sampleRate=rate,channels=channels,processedFrames=frames,applied=applied,gainDb=gain,error=null)} }
    fun unavailable(message:String) { mutable.update{it.copy(applied=false,error=message)} }
    fun stopped() { mutable.update{it.copy(applied=false,sampleRate=null,channels=null)} }
}

@androidx.annotation.OptIn(UnstableApi::class)
class LumaAudioProcessor:BaseAudioProcessor() {
    private var engine:PcmDspEngine?=null
    private var frame=FloatArray(0)
    private var revision= -1L
    private var frames=0L
    private var nextReport=0L
    override fun onConfigure(inputAudioFormat:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat {
        if(inputAudioFormat.encoding!=C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..8 || inputAudioFormat.sampleRate<=0){
            DspRuntime.unavailable("このPCM形式ではAurio DSPを利用できません")
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat // Remains active in Direct so switching never recreates the player.
    }
    override fun onFlush() { engine=PcmDspEngine(inputAudioFormat.sampleRate,inputAudioFormat.channelCount);frame=FloatArray(inputAudioFormat.channelCount);revision= -1;frames=0;nextReport=0 }
    override fun queueInput(inputBuffer:ByteBuffer) {
        // Media3 also calls processors with the shared EMPTY_BUFFER while draining.
        // Do not make that same zero-capacity object both source and destination.
        if (!inputBuffer.hasRemaining()) return
        val dsp=engine?:return
        val snapshot=DspRuntime.snapshot.get()
        if(snapshot.revision!=revision && !dsp.transitioning){dsp.configure(snapshot.config,snapshot.analysis);revision=snapshot.revision}
        val output=replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())
        val count=inputBuffer.remaining()/(2*frame.size)
        if(dsp.isDirect)output.put(inputBuffer) // Byte exact bypass, no float roundtrip or limiter.
        else {
            repeat(count){for(ch in frame.indices)frame[ch]=inputBuffer.short/32768f;dsp.processFrame(frame)
                for(ch in frame.indices)output.putShort((frame[ch]*32768).roundToInt().coerceIn(-32768,32767).toShort())}
            while(inputBuffer.hasRemaining())output.put(inputBuffer.get())
        }
        frames+=count
        if(frames>=nextReport){DspRuntime.audio(dsp.sampleRate,dsp.channels,frames,!dsp.isDirect,dsp.gainDb);nextReport=frames+dsp.sampleRate/2}
        output.flip()
    }
    override fun onReset(){engine=null;frame=FloatArray(0);DspRuntime.stopped()}
}
