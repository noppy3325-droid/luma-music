package jp.noppy.lumamusic

import android.content.ComponentName
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.playback.PlaybackController
import jp.noppy.lumamusic.playback.PlaybackService
import jp.noppy.lumamusic.playback.mediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueuePlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as LumaApp

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            var ready = false
            main { ready = predicate() }
            if (ready) return
            Thread.sleep(60)
        }
        fail(message)
    }

    /** Exercises real decoded media, two MediaControllers, and DataStore without changing device volume. */
    @Test fun sessionOrderModesAndManualQueueEditsStayInSync() {
        val fixtures = fixtures()
        val tracks = fixtures.map { file -> Track(Uri.fromFile(file).toString(), file.name, duration = 6000) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var ui: PlaybackController
        lateinit var future: ListenableFuture<MediaController>
        main {
            ui = PlaybackController(context, scope)
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            future = MediaController.Builder(context, token).buildAsync()
        }
        val observer = future.get(15, TimeUnit.SECONDS)
        val original = runBlocking { app.settings.snapshot.first() }
        val originalTracks = runBlocking { original.uris.mapNotNull { app.library.dao.track(it) } }
        var resumeOriginal = false
        main { resumeOriginal = observer.playWhenReady }
        try {
            runBlocking { app.library.dao.upsertTracks(tracks) }
            await("Both MediaControllers connected") { ui.state.value.connected && observer.isConnected }
            main {
                observer.pause()
                observer.repeatMode = Player.REPEAT_MODE_OFF
                observer.shuffleModeEnabled = false
                observer.setMediaItems(tracks.take(3).map(Track::mediaItem), 1, 4200)
                observer.prepare()
            }
            await("Three context items prepared at the requested position") {
                ui.state.value.queue.size == 3 && observer.playbackState == Player.STATE_READY && observer.currentPosition == 4200L
            }
            main { ui.shuffle() }
            await("Shuffle reaches the session") { ui.state.value.shuffle && observer.shuffleModeEnabled }
            main {
                assertEquals(tracks[1].uri, observer.currentMediaItem?.mediaId)
                assertEquals(4200L, observer.currentPosition)
                assertFalse(observer.playWhenReady)
                ui.repeat()
            }
            await("Repeat OFF cycles to ALL") { ui.state.value.repeat == Player.REPEAT_MODE_ALL && observer.repeatMode == Player.REPEAT_MODE_ALL }
            main {
                assertEquals(2, ui.state.value.upcomingIndices.distinct().size)
                assertEquals(observer.nextMediaItemIndex, ui.state.value.nextIndex)
                ui.enqueue(tracks[3], next = true)
            }
            await("Play Next goes immediately after current in shuffle order") {
                ui.state.value.nextItem?.mediaId == tracks[3].uri && observer.mediaItemCount == 4
            }
            main { ui.enqueue(tracks[4], next = false) }
            await("Append goes at the end of shuffle order") {
                val timeline = observer.currentTimeline
                timeline.windowCount == 5 && observer.getMediaItemAt(timeline.getLastWindowIndex(true)).mediaId == tracks[4].uri
            }
            val expectedBase = listOf(0, 1, 3, 2, 4).map { tracks[it].uri }
            main {
                assertEquals(expectedBase, (0 until observer.mediaItemCount).map { observer.getMediaItemAt(it).mediaId })
                assertEquals(4200L, observer.currentPosition)
                ui.shuffle()
            }
            await("Shuffle OFF keeps the edited base queue") {
                !ui.state.value.shuffle && ui.state.value.queue.map { it.mediaId } == expectedBase &&
                    ui.state.value.upcomingIndices == listOf(2, 3, 4, 0)
            }
            main { ui.shuffle() }
            await("Turning shuffle back on keeps manual Play Next") {
                ui.state.value.shuffle && ui.state.value.nextItem?.mediaId == tracks[3].uri
            }
            main { ui.repeat() }
            await("Repeat ALL cycles to ONE") { ui.state.value.repeat == Player.REPEAT_MODE_ONE && observer.repeatMode == Player.REPEAT_MODE_ONE }
            val gesture = ui.state.value
            main { assertTrue(ui.next(gesture)) }
            await("Explicit Next skips under Repeat ONE and stays paused") {
                observer.currentMediaItem?.mediaId == tracks[3].uri && ui.state.value.mediaId == tracks[3].uri && !observer.playWhenReady
            }
            main {
                assertFalse("An outdated swipe cannot skip again", ui.next(gesture))
                observer.seekTo(4500)
                observer.seekToPrevious() // Generic session command used by some external transports.
            }
            await("Previous returns to the actual shuffled predecessor even after several seconds") {
                observer.currentMediaItem?.mediaId == tracks[1].uri && observer.currentPosition < 1000
            }

            val repeatTransitions = AtomicInteger()
            val repeatListener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) repeatTransitions.incrementAndGet()
                }
            }
            main {
                observer.addListener(repeatListener)
                observer.seekTo(5750)
                observer.play()
            }
            await("Repeat ONE repeats the same item on completion") {
                repeatTransitions.get() > 0 && observer.currentMediaItem?.mediaId == tracks[1].uri
            }
            main {
                observer.pause()
                observer.removeListener(repeatListener)
                ui.repeat()
            }
            await("Repeat ONE cycles back to OFF") { ui.state.value.repeat == Player.REPEAT_MODE_OFF }
            main { observer.seekToDefaultPosition(observer.currentTimeline.getLastWindowIndex(true)) }
            await("Last shuffled item has no Next under Repeat OFF") {
                ui.state.value.mediaId == tracks[4].uri && ui.state.value.nextIndex == null
            }
            main {
                assertFalse(ui.next())
                observer.seekTo(5750)
                observer.play()
            }
            await("Repeat OFF ends playback at the last item") { observer.playbackState == Player.STATE_ENDED }
            main {
                observer.pause()
                ui.repeat()
                observer.seekTo(5750)
            }
            await("Repeat ALL is available at the end") { observer.repeatMode == Player.REPEAT_MODE_ALL && ui.state.value.nextIndex != null }
            val wrappedItem = ui.state.value.nextItem?.mediaId
            main { observer.play() }
            await("Repeat ALL advances to the first shuffled item") { observer.currentMediaItem?.mediaId == wrappedItem && observer.isPlaying }
            main { observer.pause() }

            val saved = runBlocking {
                withTimeout(10_000) {
                    app.settings.snapshot.first { snapshot ->
                        snapshot.uris == expectedBase && snapshot.shuffle && snapshot.repeat == Player.REPEAT_MODE_ALL &&
                            snapshot.uris.getOrNull(snapshot.index) == wrappedItem && snapshot.shuffleOrder.size == 5
                    }
                }
            }
            assertEquals((0..4).toSet(), saved.shuffleOrder.toSet())
            assertEquals(tracks[4].uri, saved.uris[saved.shuffleOrder.last()])
            main { observer.setMediaItems(listOf(tracks.first().mediaItem())); observer.prepare() }
            await("One item supports Repeat but offers no adjacent item or Shuffle") {
                val state = ui.state.value
                state.queue.size == 1 && state.canRepeat && !state.canShuffle && state.previousIndex == null && state.nextIndex == null
            }
        } finally {
            main {
                observer.pause()
                if (originalTracks.isEmpty()) observer.clearMediaItems()
                else {
                    observer.setMediaItems(originalTracks.map(Track::mediaItem), original.index.coerceIn(originalTracks.indices), original.position)
                    observer.prepare()
                    observer.playWhenReady = resumeOriginal
                }
                observer.repeatMode = original.repeat
                observer.shuffleModeEnabled = original.shuffle
                ui.close()
                MediaController.releaseFuture(future)
            }
            scope.cancel()
            tracks.forEach { track ->
                app.database.openHelper.writableDatabase.execSQL("DELETE FROM tracks WHERE uri=?", arrayOf(track.uri))
            }
            fixtures.forEach(File::delete)
        }
    }

    private fun fixtures(): List<File> {
        val frames = 48_000 * 6
        val dataSize = frames * 2
        val wave = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(48_000).putInt(96_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(dataSize)
        val unique = UUID.randomUUID().toString()
        return List(5) { index -> File(context.cacheDir, "queue-$unique-$index.wav").apply { writeBytes(wave.array()) } }
    }
}
