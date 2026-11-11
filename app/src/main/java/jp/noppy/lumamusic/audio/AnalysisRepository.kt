package jp.noppy.lumamusic.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import jp.noppy.lumamusic.core.dsp.*
import jp.noppy.lumamusic.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.*

/** Explicit user-requested, one-at-a-time, local decoding. Never part of a library scan. */
class AnalysisRepository(private val context:Context,private val dao:LibraryDao) {
    companion object {
        private val worker=Mutex()
        private val dispatcher=Executors.newSingleThreadExecutor { task -> Thread({Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run()},"LumaAudioAnalysis").apply{isDaemon=true} }.asCoroutineDispatcher()
        const val VERSION=1
    }
    private fun identity(track:Track)="${track.uri}|${track.size}|${track.modified}|$VERSION"
    private fun AudioAnalysisCache.profile()=TrackAnalysis(estimatedLufs,peak,toneGains.split(',').map{it.toFloatOrNull()?:0f}.let{v->List(10){v.getOrElse(it){0f}}},frames,sampleRate,complete)
    suspend fun cached(track:Track,config:DspConfig):Pair<TrackAnalysis?,String> = withContext(Dispatchers.IO) {
        val cache=dao.analysis(track.uri)?.takeIf{it.identity==identity(track)&&it.version==VERSION}
        if(cache?.error!=null)return@withContext null to "解析できません: ${cache.error}"
        val profile=cache?.profile()
        if(config.normalizeEnabled && config.normalizeMode==NormalizeMode.ALBUM){
            val album=dao.tracks().first().filter{it.album==track.album && it.albumArtist.ifBlank{it.artist}==track.albumArtist.ifBlank{track.artist}}
            val all=album.map{t->dao.analysis(t.uri)?.takeIf{it.identity==identity(t)&&it.error==null&&it.complete&&it.estimatedLufs!=null}}
            if(all.isEmpty()||all.any{it==null})return@withContext profile?.copy(estimatedLufs=null) to "アルバム解析待ち（全曲の完了が必要）"
            val rows=all.filterNotNull();val seconds=rows.sumOf{it.frames.toDouble()/it.sampleRate.coerceAtLeast(1)}.coerceAtLeast(.001)
            val energy=rows.sumOf{10.0.pow((it.estimatedLufs!!+.691)/10)*it.frames/it.sampleRate.coerceAtLeast(1)}/seconds
            return@withContext (profile?:rows.first().profile()).copy(estimatedLufs= - .691+10*log10(energy),peak=rows.maxOf{it.peak}) to "アルバム推定LUFS（曲別ゲートの加重平均）"
        }
        profile to if(profile==null)"解析待ち（音色・音量を推測しません）"else "${if(profile.complete)"全曲"else "先頭10分まで"}解析 / ${profile.estimatedLufs?.let{"推定 %.1f LUFS".format(it)}?:"無音・測定不足"}"
    }
    suspend fun analyze(track:Track,force:Boolean=false) = worker.withLock {
        withContext(dispatcher){
            if(!force && dao.analysis(track.uri)?.let{it.identity==identity(track)&&it.error==null}==true){refresh(track);return@withContext}
            if(DspRuntime.sourceUri==track.uri)DspRuntime.analysis(null,"現在曲を端末内で解析中…")
            try {
                val p=decode(track)
                if(dao.tracks().first().any{it.uri==track.uri})dao.saveAnalysis(AudioAnalysisCache(track.uri,identity(track),VERSION,p.estimatedLufs,p.peak,p.toneGains.joinToString(","),p.frames,p.sampleRate,p.complete))
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(dao.tracks().first().any{it.uri==track.uri})dao.saveAnalysis(AudioAnalysisCache(track.uri,identity(track),VERSION,null,0.0,"",0,0,false,e.localizedMessage?.take(160)?:"非対応形式"))}
            refresh(track)
        }
    }
    private suspend fun refresh(track:Track){if(DspRuntime.sourceUri==track.uri){val cfg=DspRuntime.snapshot.get().config;val(p,message)=cached(track,cfg);if(DspRuntime.sourceUri==track.uri&&DspRuntime.snapshot.get().config==cfg)DspRuntime.analysis(p,message)}}
    private suspend fun decode(track:Track):TrackAnalysis {
        val extractor=MediaExtractor();var codec:MediaCodec?=null
        try {
            extractor.setDataSource(context,Uri.parse(track.uri),null)
            val index=(0 until extractor.trackCount).firstOrNull{extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true}?:error("音声トラックがありません")
            extractor.selectTrack(index)
            val format=extractor.getTrackFormat(index)
            val mime=format.getString(MediaFormat.KEY_MIME)?:error("音声形式不明")
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(rate in 8000..192000 && channels in 1..2){"解析は8〜192kHzのモノラル/ステレオに対応"}
            var analyzer=LoudnessAccumulator(rate,channels)
            var encoding=if(format.containsKey(MediaFormat.KEY_PCM_ENCODING))format.getInteger(MediaFormat.KEY_PCM_ENCODING)else AudioFormat.ENCODING_PCM_16BIT
            var frame=FloatArray(channels)
            var sourceFrames=0L;var eof=false
            fun consume(buffer:ByteBuffer){buffer.order(ByteOrder.nativeOrder());val bytes=when(encoding){AudioFormat.ENCODING_PCM_FLOAT->4;AudioFormat.ENCODING_PCM_16BIT->2;else->error("解析用PCM形式に未対応")}
                while(buffer.remaining()>=channels*bytes && sourceFrames<rate*600L){for(ch in 0 until channels)frame[ch]=if(bytes==4)buffer.float else buffer.short/32768f;analyzer.frame(frame);sourceFrames++}}
            if(mime=="audio/raw"){
                val data=ByteBuffer.allocateDirect(256*1024)
                while(sourceFrames<rate*600L){currentCoroutineContext().ensureActive();data.clear();val n=extractor.readSampleData(data,0);if(n<0){eof=true;break};data.position(0);data.limit(n);consume(data);extractor.advance();delay(2)}
            }else{
                val decoder=MediaCodec.createDecoderByType(mime);codec=decoder;decoder.configure(format,null,null,0);decoder.start()
                var inputEnded=false;var stalls=0
                val info=MediaCodec.BufferInfo()
                while(!eof && sourceFrames<rate*600L){
                    currentCoroutineContext().ensureActive()
                    if(!inputEnded){val id=decoder.dequeueInputBuffer(10_000);if(id>=0){val input=decoder.getInputBuffer(id)!!;val n=extractor.readSampleData(input,0)
                        if(n<0){decoder.queueInputBuffer(id,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true}
                        else{decoder.queueInputBuffer(id,0,n,extractor.sampleTime,0);extractor.advance()}}}
                    when(val id=decoder.dequeueOutputBuffer(info,10_000)){
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED->{val f=decoder.outputFormat;val newRate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);val newChannels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            require(newRate in 8000..192000&&newChannels in 1..2)
                            if(sourceFrames==0L){rate=newRate;channels=newChannels;analyzer=LoudnessAccumulator(rate,channels);frame=FloatArray(channels)}else require(rate==newRate&&channels==newChannels){"途中で音声形式が変化しました"}
                            encoding=if(f.containsKey(MediaFormat.KEY_PCM_ENCODING))f.getInteger(MediaFormat.KEY_PCM_ENCODING)else AudioFormat.ENCODING_PCM_16BIT}
                        MediaCodec.INFO_TRY_AGAIN_LATER->{if(++stalls>1500)error("解析の応答がありません")}
                        else->if(id>=0){stalls=0;decoder.getOutputBuffer(id)?.let{b->b.position(info.offset);b.limit(info.offset+info.size);consume(b)};eof=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;decoder.releaseOutputBuffer(id,false);delay(2)}
                    }
                }
            }
            return analyzer.result(eof)
        }finally{runCatching{codec?.stop()};codec?.release();extractor.release()}
    }
}
