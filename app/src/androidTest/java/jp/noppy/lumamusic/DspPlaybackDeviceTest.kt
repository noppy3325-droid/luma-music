package jp.noppy.lumamusic

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.noppy.lumamusic.audio.DspRuntime
import jp.noppy.lumamusic.core.dsp.*
import jp.noppy.lumamusic.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Silent audio through the real service; restore the queue and settings after testing. */
@RunWith(AndroidJUnit4::class)
class DspPlaybackDeviceTest {
    @Test fun modeChangesKeepSessionQueueAndAdvancingPosition() {
        val i=InstrumentationRegistry.getInstrumentation();val ctx=i.targetContext;val app=ctx.applicationContext as LumaApp
        val savedSound=runBlocking { app.settings.soundSettings.first() }
        val savedSnapshot=runBlocking { app.settings.snapshot.first() }
        ctx.startActivity(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var future:com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
        i.runOnMainSync { future=MediaController.Builder(ctx,SessionToken(ctx,ComponentName(ctx,PlaybackService::class.java))).buildAsync() }
        val c=future!!.get(20,TimeUnit.SECONDS)
        Thread.sleep(500)
        var savedQueue=emptyList<MediaItem>();var savedIndex=0;var savedPosition=0L;var savedRepeat=0;var savedShuffle=false;var savedPlaying=false
        i.runOnMainSync { savedQueue=(0 until c.mediaItemCount).map(c::getMediaItemAt);savedIndex=c.currentMediaItemIndex.coerceAtLeast(0);savedPosition=c.currentPosition;savedRepeat=c.repeatMode;savedShuffle=c.shuffleModeEnabled;savedPlaying=c.playWhenReady }
        val rate=44100;val data=rate*4*30;val b=ByteBuffer.allocate(44+data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36+data).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate*4).putShort(4).putShort(16).put("data".toByteArray()).putInt(data)
        val file=File(ctx.cacheDir,"dsp-session-${UUID.randomUUID()}.wav").apply{writeBytes(b.array())}
        fun await(label:String,condition:()->Boolean){val until=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<until){var ok=false;i.runOnMainSync{ok=condition()};if(ok)return;Thread.sleep(100)};fail("Timed out: $label")}
        val cpu=Process.getElapsedCpuTime();val start=SystemClock.elapsedRealtime()
        try {
            runBlocking{app.settings.saveSound(DspConfig())}
            i.runOnMainSync { c.setMediaItem(MediaItem.Builder().setMediaId(file.name).setUri(Uri.fromFile(file)).build());c.prepare();c.play() }
            await("Direct playback"){c.isPlaying&&c.currentPosition>300&&!DspRuntime.state.value.applied&&DspRuntime.state.value.sampleRate==rate}
            var before=0L;i.runOnMainSync{before=c.currentPosition}
            runBlocking{app.settings.saveSound(DspConfig(mode=SoundMode.ENHANCE,bands=List(10){if(it==5)3f else 0f},widthEnabled=true,width=1.1f))}
            await("Enhance consumes PCM without resetting position"){DspRuntime.state.value.mode==SoundMode.ENHANCE&&DspRuntime.state.value.applied&&c.currentPosition>before+300}
            i.runOnMainSync { assertEquals(file.name,c.currentMediaItem?.mediaId);assertEquals(1,c.mediaItemCount);assertNull(c.playerError);assertFalse(app.output.state.value.mixerApplied);before=c.currentPosition }
            runBlocking{app.settings.saveSound(DspConfig())}
            await("Direct bypass resumes"){DspRuntime.state.value.mode==SoundMode.DIRECT&&!DspRuntime.state.value.applied&&c.currentPosition>before+300}
            i.runOnMainSync { assertNull(c.playerError);android.util.Log.i("LumaDspDevice","wallMs=${SystemClock.elapsedRealtime()-start} processCpuMs=${Process.getElapsedCpuTime()-cpu} status=${DspRuntime.state.value} output=${app.output.state.value}") }
        } finally {
            i.runOnMainSync { c.pause();if(savedQueue.isEmpty())c.clearMediaItems()else{c.setMediaItems(savedQueue,savedIndex.coerceIn(savedQueue.indices),savedPosition);c.repeatMode=savedRepeat;c.shuffleModeEnabled=savedShuffle;c.prepare();c.playWhenReady=savedPlaying};MediaController.releaseFuture(future!!) }
            runBlocking{app.settings.saveSound(savedSound);app.settings.saveSnapshot(savedSnapshot)}
            file.delete()
        }
    }
}
