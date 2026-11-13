package jp.noppy.lumamusic.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import jp.noppy.lumamusic.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackUi(
    val connected: Boolean = false,
    val playing: Boolean = false,
    val mediaId: String? = null,
    val title: String = "再生する曲を選択",
    val artist: String = "あなたの音楽を、手元に。",
    val artwork: String? = null,
    val position: Long = 0,
    val duration: Long = 0,
    val queue: List<MediaItem> = emptyList(),
    val index: Int = 0,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val error: String? = null,
    val previousIndex: Int? = null,
    val nextIndex: Int? = null,
    val previousItem: MediaItem? = null,
    val nextItem: MediaItem? = null,
    val upcomingIndices: List<Int> = emptyList(),
    val canShuffle: Boolean = false,
    val canRepeat: Boolean = false,
)

class PlaybackController(context: Context, scope: CoroutineScope) {
    private val mutable = MutableStateFlow(PlaybackUi())
    val state = mutable.asStateFlow()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var controller: MediaController? = null
    private var closed = false
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            update(refreshQueue = events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_AVAILABLE_COMMANDS_CHANGED))
        }
    }
    private val poll = scope.launch {
        while (isActive) {
            update()
            delay(350)
        }
    }

    init {
        future.addListener({
            if (!closed) {
                runCatching { future.get() }.onSuccess {
                    controller = it
                    it.addListener(listener)
                    update(refreshQueue = true)
                }.onFailure {
                    mutable.value = PlaybackUi(error = "再生サービスに接続できません: ${it.localizedMessage}")
                }
            }
        }, mainExecutor)
    }

    private fun update(refreshQueue: Boolean = false) {
        val player = controller ?: return
        if (!player.isConnected) {
            mutable.value = PlaybackUi(error = "再生サービスとの接続が切れました")
            return
        }
        val previous = mutable.value
        val hasTimeline = player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)
        val queue = if (refreshQueue && hasTimeline) {
            (0 until player.mediaItemCount).map(player::getMediaItemAt)
        } else if (hasTimeline) previous.queue else emptyList()
        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val shuffle = player.shuffleModeEnabled
        val repeat = player.repeatMode
        val changedOrder = refreshQueue || previous.index != index || previous.shuffle != shuffle || previous.repeat != repeat
        val upcoming = when {
            queue.isEmpty() -> emptyList()
            changedOrder -> player.currentTimeline.upcomingOrder(index, repeat, shuffle)
            else -> previous.upcomingIndices
        }
        val previousIndex = player.neighborIndex(forward = false)?.takeIf { it in queue.indices }
        val nextIndex = player.neighborIndex(forward = true)?.takeIf { it in queue.indices }
        val media = player.currentMediaItem
        val metadata = player.mediaMetadata
        mutable.value = PlaybackUi(
            connected = true,
            playing = player.isPlaying,
            mediaId = media?.mediaId,
            title = metadata.title?.toString() ?: "再生する曲を選択",
            artist = metadata.artist?.toString().orEmpty(),
            artwork = metadata.artworkUri?.toString(),
            position = player.currentPosition.coerceAtLeast(0),
            duration = player.duration.coerceAtLeast(0),
            queue = queue,
            index = index,
            repeat = repeat,
            shuffle = shuffle,
            error = player.playerError?.localizedMessage,
            previousIndex = previousIndex,
            nextIndex = nextIndex,
            previousItem = previousIndex?.let(queue::get),
            nextItem = nextIndex?.let(queue::get),
            upcomingIndices = upcoming,
            canShuffle = queue.size > 1 && player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE),
            canRepeat = queue.isNotEmpty() && player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE),
        )
    }

    private fun connectedPlayer(): MediaController? = controller?.takeIf { it.isConnected }

    /** Reopening the current item from browsing is independent of the visible list's order. */
    fun playFromBrowse(tracks: List<Track>, index: Int): Boolean {
        val target = tracks.getOrNull(index) ?: return false
        val player = connectedPlayer() ?: return false
        if (player.currentMediaItem?.mediaId == target.uri && player.playbackState != Player.STATE_ENDED && player.playerError == null) return true
        return play(tracks, index)
    }

    fun play(tracks: List<Track>, index: Int = 0, shuffle: Boolean? = null): Boolean {
        val player = connectedPlayer() ?: return false
        if (tracks.isEmpty() || !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
            !player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) || !player.isCommandAvailable(Player.COMMAND_PREPARE)) return false
        player.setMediaItems(tracks.map(Track::mediaItem), index.coerceIn(tracks.indices), 0)
        if (shuffle != null && player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) player.shuffleModeEnabled = shuffle
        player.prepare()
        player.play()
        return true
    }

    fun toggle() {
        val player = connectedPlayer() ?: return
        if (!player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) return
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED && player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) {
                player.seekToDefaultPosition()
            }
            prepareIfIdle(player)
            player.play()
        }
    }

    fun next(expected: PlaybackUi? = null): Boolean = navigate(forward = true, expected)
    fun previousItem(expected: PlaybackUi? = null): Boolean = navigate(forward = false, expected)
    fun previous(): Boolean = previousItem()

    private fun navigate(forward: Boolean, expected: PlaybackUi?): Boolean {
        val player = connectedPlayer() ?: return false
        val target = player.neighborIndex(forward) ?: return false
        if (expected != null) {
            val expectedIndex = if (forward) expected.nextIndex else expected.previousIndex
            val expectedItem = if (forward) expected.nextItem else expected.previousItem
            if (player.currentMediaItemIndex != expected.index || player.currentMediaItem?.mediaId != expected.mediaId ||
                target != expectedIndex || expectedItem != player.getMediaItemAt(target)) return false
            // The previewed destination is fixed. If playback advances while the command is in
            // transit, a relative command could jump one additional song.
            return skip(target, expectedItem)
        }
        if (forward) player.seekToNextMediaItem() else player.seekToPreviousMediaItem()
        prepareIfIdle(player)
        return true
    }

    fun seek(position: Long) {
        val player = connectedPlayer() ?: return
        if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) player.seekTo(position.coerceAtLeast(0))
    }

    fun skip(index: Int, expected: MediaItem? = null): Boolean {
        val player = connectedPlayer() ?: return false
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM) || index !in 0 until player.mediaItemCount) return false
        if (expected != null && expected != player.getMediaItemAt(index)) return false
        player.seekToDefaultPosition(index)
        prepareIfIdle(player)
        return true
    }

    private fun prepareIfIdle(player: Player) {
        if (player.playbackState == Player.STATE_IDLE && player.isCommandAvailable(Player.COMMAND_PREPARE)) player.prepare()
    }

    fun shuffle() {
        val player = connectedPlayer() ?: return
        if (player.mediaItemCount > 1 && player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
        }
    }

    fun repeat() {
        val player = connectedPlayer() ?: return
        if (player.mediaItemCount > 0 && player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) {
            player.repeatMode = nextRepeatMode(player.repeatMode)
        }
    }

    fun enqueue(track: Track, next: Boolean, onComplete: (Boolean) -> Unit = {}) {
        val player = connectedPlayer()
        if (player == null || !player.availableSessionCommands.contains(addQueueItemCommand)) {
            onComplete(false)
            return
        }
        val arguments = Bundle().apply {
            putString(QUEUE_ITEM_URI, track.uri)
            putBoolean(QUEUE_PLAY_NEXT, next)
        }
        val request = player.sendCustomCommand(addQueueItemCommand, arguments)
        request.addListener({
            if (!closed) {
                onComplete(runCatching { request.get().resultCode }.getOrNull() == SessionResult.RESULT_SUCCESS)
            }
        }, mainExecutor)
    }

    fun remove(index: Int) {
        val player = connectedPlayer() ?: return
        if (player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) && index in 0 until player.mediaItemCount) {
            player.removeMediaItem(index)
        }
    }

    fun move(from: Int, to: Int) {
        val player = connectedPlayer() ?: return
        val indices = 0 until player.mediaItemCount
        if (player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) && from in indices && to in indices && from != to) {
            player.moveMediaItem(from, to)
        }
    }

    fun pause() {
        val player = connectedPlayer() ?: return
        if (player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) player.pause()
    }

    fun close() {
        closed = true
        poll.cancel()
        controller?.removeListener(listener)
        controller = null
        MediaController.releaseFuture(future)
    }
}

internal fun Player.neighborIndex(forward: Boolean): Int? {
    val command = if (forward) Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM else Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
    if (!isCommandAvailable(command)) return null
    val target = if (forward) nextMediaItemIndex else previousMediaItemIndex
    return target.takeIf { it in 0 until mediaItemCount && it != currentMediaItemIndex }
}
