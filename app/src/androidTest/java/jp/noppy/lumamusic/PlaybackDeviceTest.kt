package jp.noppy.lumamusic

import android.content.ComponentName
import android.content.Intent
import android.app.NotificationManager
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.accessibilityservice.AccessibilityService
import androidx.media3.common.*
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.noppy.lumamusic.playback.PlaybackService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import jp.noppy.lumamusic.data.UserTrack

/** Real silent PCM through the service/session: no volume or global device settings are changed. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            var result = false
            main { result = predicate() }
            if (result) return
            Thread.sleep(100)
        }
        fail("Timed out: $description")
    }
    private fun fixture(rate: Int): File {
        val frames = rate * 12
        val bytes = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(frames * 4)
        return File(context.filesDir, "validation-$rate.wav").apply { writeBytes(bytes.array()) }
    }
    @Test fun silent44100And48000PlayThroughSessionAndReportActualTrack() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val controller = future!!.get(15, TimeUnit.SECONDS)
        Thread.sleep(500)
        var queue=emptyList<MediaItem>();var savedIndex=0;var savedPosition=0L;var savedRepeat=0;var savedShuffle=false;var wasPlaying=false
        main { queue=(0 until controller.mediaItemCount).map(controller::getMediaItemAt);savedIndex=controller.currentMediaItemIndex.coerceAtLeast(0);savedPosition=controller.currentPosition; savedRepeat=controller.repeatMode;savedShuffle=controller.shuffleModeEnabled;wasPlaying=controller.playWhenReady }
        try {
            val first = fixture(44100)
            val second = fixture(48000)
            main {
                controller.setMediaItems(listOf(first, second).map { f -> MediaItem.Builder().setMediaId(f.name).setUri(Uri.fromFile(f)).setMediaMetadata(MediaMetadata.Builder().setTitle(f.name).setArtist("Aurio validation • silent PCM").build()).build() })
                controller.prepare(); controller.play()
            }
            await("44.1 kHz playback advances") { controller.isPlaying && controller.currentPosition > 300 }
            val app = context.applicationContext as LumaApp
            await("observed route") { app.output.state.value.actual != null }
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            await("background playback continues") { controller.isPlaying && controller.currentPosition > 1000 }
            val notifications = context.getSystemService(NotificationManager::class.java)
            fun aurioSmallIcon(): Icon? = notifications.activeNotifications
                .asSequence()
                .mapNotNull { it.notification.smallIcon }
                .firstOrNull { it.type == Icon.TYPE_RESOURCE && it.resPackage == context.packageName && it.resId == R.drawable.ic_aurio_notification }
            await("Aurio's own playback notification uses the small icon") { aurioSmallIcon() != null }
            val notificationDrawable = checkNotNull(aurioSmallIcon()?.loadDrawable(context))
            writeBrandArtwork(context, "notification-small.png", notificationDrawable, size = 24)
            main {
                assertNull(controller.playerError)
                assertEquals(44100, app.output.state.value.track?.rate)
                Log.i("LumaDeviceTest", "44100: ${app.output.state.value}")
                controller.pause()
            }
            await("session pause") { !controller.isPlaying }
            main { controller.seekToNextMediaItem(); controller.play() }
            await("48 kHz playback advances") { controller.isPlaying && controller.currentMediaItemIndex == 1 && controller.currentPosition > 300 }
            await("48 kHz track config") { app.output.state.value.track?.rate == 48000 }
            main {
                assertNull(controller.playerError)
                assertFalse(app.output.state.value.mixerApplied)
                assertFalse(app.output.state.value.bitPerfect.contains("保証"))
                Log.i("LumaDeviceTest", "48000: ${app.output.state.value}")
                controller.pause()
            }
        } finally { main {
            controller.pause()
            if(queue.isEmpty()) controller.clearMediaItems() else { controller.setMediaItems(queue,savedIndex.coerceIn(queue.indices),savedPosition);controller.prepare();controller.playWhenReady=wasPlaying }
            controller.repeatMode=savedRepeat;controller.shuffleModeEnabled=savedShuffle
            MediaController.releaseFuture(future!!)
        } }
    }
    @Test fun reportsRealOutputCapabilitiesWithoutGuessedUsbDevice() {
        val audio = context.getSystemService(AudioManager::class.java)
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        assertTrue(devices.isNotEmpty())
        devices.forEach { d -> Log.i("LumaDeviceTest", "device=${d.id} type=${d.type} name=${d.productName} rates=${d.sampleRates.contentToString()} channels=${d.channelCounts.contentToString()} encodings=${d.encodings.contentToString()}") }
    }
    @Test fun concurrentFavoriteAndLyricsEditsPreserveBothFields() = runBlocking {
        val library = (context.applicationContext as LumaApp).library
        val uri = "luma-validation:concurrent-edits"
        library.dao.userTrack(UserTrack(uri))
        coroutineScope {
            val toggles = List(20) { async(Dispatchers.IO) { library.toggleFavorite(uri) } }
            val lyrics = async(Dispatchers.IO) { library.saveLyrics(uri, "[00:01.00]saved lyrics") }
            toggles.awaitAll(); lyrics.await()
        }
        assertFalse(library.dao.userTrack(uri)!!.favorite)
        assertEquals("[00:01.00]saved lyrics", library.dao.userTrack(uri)!!.lyrics)
    }
}
