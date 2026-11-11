package jp.noppy.lumamusic.audio

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import jp.noppy.lumamusic.LumaApp
import jp.noppy.lumamusic.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.*

/** Exercises Android's actual extractor/decoder and Room cache, not the JVM estimator alone. */
class AudioAnalysisDeviceTest {
    @Test fun localPcmFilesDecodeAndCacheAtBothMusicRates()=runBlocking {
        withTimeout(90_000){
            val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LumaApp
            val dao=app.library.dao
            val key="luma-analysis-test-${UUID.randomUUID()}"
            val dir=File(app.cacheDir,key).apply{mkdirs()}
            val folder=MusicFolder(key,"DSP test temporary")
            val owned=mutableListOf<String>()
            try{
                dao.folder(folder)
                val repository=AnalysisRepository(app,dao)
                for(rate in listOf(44100,48000))for(channels in listOf(1,2)){
                    val frames=rate*2;val dataSize=frames*channels*2
                    val b=ByteBuffer.allocate(44+dataSize).order(ByteOrder.LITTLE_ENDIAN)
                    b.put("RIFF".toByteArray()).putInt(36+dataSize).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
                    b.putInt(rate).putInt(rate*channels*2).putShort((channels*2).toShort()).putShort(16).put("data".toByteArray()).putInt(dataSize)
                    repeat(frames){i->val x=(.1*32767*sin(2*PI*997*i/rate)).roundToInt().toShort();repeat(channels){b.putShort(x)}}
                    val file=File(dir,"$rate-$channels.wav").apply{writeBytes(b.array())}
                    val uri=Uri.fromFile(file).toString();owned+=uri
                    val track=Track(uri,"DSP test",artist=key,album=key,duration=2000,mime="audio/wav",modified=file.lastModified(),size=file.length())
                    dao.upsertTracks(listOf(track));dao.refs(listOf(TrackFolder(uri,key)))
                    repository.analyze(track)
                    val cached=dao.analysis(uri)
                    assertNotNull("analysis cache $rate/$channels",cached)
                    assertNull("decoder error $rate/$channels: ${cached?.error}",cached?.error)
                    assertTrue("full file was consumed",cached!!.complete)
                    assertEquals(if(channels==1)-23.01 else -20.0,cached.estimatedLufs!!,.2)
                    assertEquals(.1,cached.peak,.001)
                    repository.analyze(track) // Existing valid cache is reused safely.
                    assertEquals(cached,dao.analysis(uri))
                }
            }finally{
                withContext(NonCancellable){
                    dao.deleteAnalysisForFolder(key);dao.clearRefs(key);dao.deleteFolder(key)
                    owned.forEach{uri->app.database.openHelper.writableDatabase.execSQL("DELETE FROM tracks WHERE uri=?",arrayOf(uri))}
                    dir.listFiles()?.forEach{it.delete()};dir.delete()
                }
            }
        }
    }
}
