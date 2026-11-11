package jp.noppy.lumamusic.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.session.*
import jp.noppy.lumamusic.data.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackUi(
    val connected: Boolean = false, val playing: Boolean = false, val mediaId: String? = null,
    val title: String = "再生する曲を選択", val artist: String = "あなたの音楽を、手元に。", val artwork: String? = null,
    val position: Long = 0, val duration: Long = 0, val queue: List<MediaItem> = emptyList(), val index: Int = 0,
    val repeat: Int = Player.REPEAT_MODE_OFF, val shuffle: Boolean = false, val error: String? = null,
)
class PlaybackController(context: Context, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(PlaybackUi())
    val state = mutable.asStateFlow()
    private var controller: MediaController? = null
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    private var cachedQueue: List<MediaItem> = emptyList()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            update(events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_MEDIA_METADATA_CHANGED))
        }
    }
    init {
        future.addListener({
            runCatching { future.get() }.onSuccess { controller = it; it.addListener(listener); update(true) }
                .onFailure { mutable.value = PlaybackUi(error = "再生サービスに接続できません: ${it.localizedMessage}") }
        }, ContextCompat.getMainExecutor(context))
        scope.launch { while (isActive) { update(); delay(350) } }
    }
    private fun update(refreshQueue: Boolean = false) {
        val p = controller ?: return
        if (refreshQueue) cachedQueue = (0 until p.mediaItemCount).map(p::getMediaItemAt)
        val m = p.mediaMetadata
        mutable.value = PlaybackUi(true, p.isPlaying, p.currentMediaItem?.mediaId, m.title?.toString() ?: "再生する曲を選択", m.artist?.toString().orEmpty(), m.artworkUri?.toString(), p.currentPosition.coerceAtLeast(0), p.duration.takeIf { it > 0 } ?: 0,
            cachedQueue, p.currentMediaItemIndex.coerceAtLeast(0), p.repeatMode, p.shuffleModeEnabled, p.playerError?.localizedMessage)
    }
    fun play(tracks: List<Track>, index: Int = 0, shuffle: Boolean = false) { controller?.let { p -> if (tracks.isNotEmpty()) { p.setMediaItems(tracks.map(Track::mediaItem), index.coerceIn(tracks.indices), 0); p.shuffleModeEnabled = shuffle; p.prepare(); p.play() } } }
    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }
    fun seek(position: Long) { controller?.seekTo(position) }
    fun skip(index: Int) { controller?.seekToDefaultPosition(index); controller?.play() }
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun repeat() { controller?.let { it.repeatMode = (it.repeatMode + 1) % 3 } }
    fun enqueue(track: Track, next: Boolean) { controller?.let { p -> if (next) p.addMediaItem((p.currentMediaItemIndex + 1).coerceAtLeast(0), track.mediaItem()) else p.addMediaItem(track.mediaItem()) } }
    fun remove(index: Int) { controller?.removeMediaItem(index) }
    fun move(from: Int, to: Int) { controller?.moveMediaItem(from, to) }
    fun pause() { controller?.pause() }
    fun close() { controller?.removeListener(listener); MediaController.releaseFuture(future) }
}
