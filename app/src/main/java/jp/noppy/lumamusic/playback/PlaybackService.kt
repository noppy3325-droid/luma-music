@file:Suppress("DEPRECATION")
package jp.noppy.lumamusic.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.audio.*
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import jp.noppy.lumamusic.LumaApp
import jp.noppy.lumamusic.MainActivity
import jp.noppy.lumamusic.R
import jp.noppy.lumamusic.core.PcmSpec
import jp.noppy.lumamusic.core.dsp.SoundMode
import jp.noppy.lumamusic.audio.*
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import jp.noppy.lumamusic.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val app get() = application as LumaApp
    private var restored = false
    private var retryUsed = false
    private val disconnecting = AtomicBoolean(false)
    private val dsp = LumaAudioProcessor()
    private val analysis by lazy { AnalysisRepository(this, app.library.dao) }
    private var analysisJob: Job? = null
    private var libraryIndex: Map<String, Track> = emptyMap()
    private var lastScheduledSnapshot: PlaybackSnapshot? = null
    private val queueEdits = Mutex()

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_aurio_notification)
            },
        )
        app.output.start {
            disconnecting.set(true)
            player.pause()
            player.setPreferredAudioDevice(null)
            save()
        }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
                val provider = DefaultAudioSink.AudioTrackProvider { config, attributes, sessionId, trackContext ->
                    app.output.configure(PcmSpec(config.sampleRate, Integer.bitCount(config.channelConfig), config.encoding), config.channelConfig)
                    try {
                        DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, sessionId, trackContext).also(app.output::observe)
                    } catch (e: RuntimeException) {
                        app.output.fallback("USB優先設定で出力を開始できません。通常再生に戻します")
                        DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, sessionId, trackContext).also(app.output::observe)
                    }
                }
                // Verified against Media3 1.11.1 sources: the no-context builder pins PCM-only
                // capabilities, while the float-output path bypasses custom processors.
                return DefaultAudioSink.Builder().setAudioTrackProvider(provider)
                    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setAudioOffloadSupportProvider { _, _ -> AudioOffloadSupport.DEFAULT_UNSUPPORTED }
                    .setEnableFloatOutput(false).setAudioProcessors(arrayOf(dsp)).build()
            }
        }.setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(
            TrackSelectionParameters.AudioOffloadPreferences.Builder().setAudioOffloadMode(
                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build()).build()
        // Remain conservative before asynchronous preferences arrive.
        app.output.processingIsUnchanged(false)
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, SessionQueuePlayer(player))
            .setSessionActivity(activity)
            .setCallback(SessionCallbacks())
            .build()
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                android.util.Log.w("LumaAudioHealth", "underrun bytes=$bufferSize bufferMs=$bufferSizeMs feedGapMs=$elapsedSinceLastFeedMs")
            }
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                app.output.noteSource(PcmSpec(format.sampleRate, format.channelCount, format.pcmEncoding))
                readGainMetadata(format.metadata)
            }
        })
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) refreshQueueMetadata()
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) save()
                else if (events.contains(Player.EVENT_TIMELINE_CHANGED)) save(queueOnly = true)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                retryUsed = false
                app.output.newSource()
                DspRuntime.source(mediaItem?.mediaId)
                loadAnalysis()
            }
            override fun onMetadata(metadata: Metadata) { readGainMetadata(metadata) }
            override fun onVolumeChanged(volume: Float) = updateProcessing()
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = updateProcessing()
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = updateProcessing()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) disconnecting.set(false)
            }
            override fun onPlayerError(error: PlaybackException) {
                val wasMixer = app.output.state.value.mixerApplied
                if (wasMixer && !retryUsed && !disconnecting.get()) {
                    retryUsed = true
                    app.output.fallback("USB設定で再生できなかったため通常再生で再試行します")
                    player.prepare()
                }
            }
        })
        scope.launch { restorePlayback() }
        scope.launch { app.settings.preferences.collect { app.output.mode(it.mode) } }
        scope.launch { app.settings.soundSettings.collect { DspRuntime.settings(it); updateProcessing(); loadAnalysis() } }
        scope.launch { DspRuntime.state.collect { updateProcessing() } }
        scope.launch {
            app.library.tracks.conflate().collect { tracks ->
                libraryIndex = withContext(Dispatchers.Default) { tracks.associateBy { it.uri } }
                refreshQueueMetadata()
            }
        }
        scope.launch { while (isActive) { delay(5000); save() } }
    }

    private inner class SessionCallbacks : MediaSession.Callback {
        private fun isOwnController(controller: MediaSession.ControllerInfo): Boolean =
            controller.uid == Process.myUid() && controller.packageName == packageName

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!isOwnController(controller)) return super.onConnect(session, controller)
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(addQueueItemCommand).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != addQueueItemCommand.customAction) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }
            if (!isOwnController(controller)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            val uri = args.getString(QUEUE_ITEM_URI)
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            val playNext = args.getBoolean(QUEUE_PLAY_NEXT)
            val result = SettableFuture.create<SessionResult>()
            val job = scope.launch {
                try {
                    val response = queueEdits.withLock {
                        val track = withContext(Dispatchers.IO) { app.library.dao.track(uri)?.takeIf(::isReadable) }
                        if (track == null) SessionResult(SessionError.ERROR_IO) else insertIntoQueue(track, playNext)
                    }
                    result.set(response)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w("LumaPlayback", "Queue insertion failed", error)
                    result.set(SessionResult(SessionError.ERROR_IO))
                }
            }
            job.invokeOnCompletion { if (!result.isDone) result.cancel(false) }
            return result
        }
    }

    private fun isReadable(track: Track): Boolean = runCatching {
        contentResolver.openFileDescriptor(Uri.parse(track.uri), "r")?.use { true } == true
    }.getOrDefault(false)

    private fun insertIntoQueue(track: Track, playNext: Boolean): SessionResult {
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex.takeIf { it in 0 until count }
        val insertAfter = current.takeIf { playNext }
        val insertionIndex = insertAfter?.plus(1) ?: count
        val order = if (player.shuffleModeEnabled) player.currentTimeline.shuffledOrder() else null
        if (order != null && !order.isWindowPermutation(count)) return SessionResult(SessionError.ERROR_INVALID_STATE)
        player.addMediaItem(insertionIndex, track.mediaItem())
        if (order != null) {
            player.setShuffleOrder(DefaultShuffleOrder(order.afterQueueInsertion(insertionIndex, insertAfter), Random.nextLong()))
        }
        save()
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private suspend fun restorePlayback() {
        val saved = app.settings.snapshot.first()
        // A new queue from the UI wins if it arrives while SAF files are being checked.
        if (player.mediaItemCount == 0) {
            player.repeatMode = when (saved.repeat) {
                Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE -> saved.repeat
                else -> Player.REPEAT_MODE_OFF
            }
            player.shuffleModeEnabled = saved.shuffle
            val readable = withContext(Dispatchers.IO) {
                saved.uris.mapIndexedNotNull { index, uri ->
                    app.library.dao.track(uri)?.takeIf(::isReadable)?.let { index to it }
                }
            }
            if (readable.isNotEmpty() && player.mediaItemCount == 0) {
                val selected = readable.indexOfFirst { (index, _) -> index >= saved.index }.let { if (it < 0) readable.lastIndex else it }
                val position = if (readable[selected].first == saved.index) saved.position.coerceAtLeast(0) else 0
                player.setMediaItems(readable.map { (_, track) -> track.mediaItem() }, selected, position)
                if (saved.shuffleOrder.isWindowPermutation(saved.uris.size)) {
                    val remappedIndices = readable.mapIndexed { index, (oldIndex, _) -> oldIndex to index }.toMap()
                    val restoredOrder = saved.shuffleOrder.mapNotNull(remappedIndices::get).toIntArray()
                    player.setShuffleOrder(DefaultShuffleOrder(restoredOrder, Random.nextLong()))
                }
                player.prepare()
            }
        }
        restored = true
    }

    private fun updateProcessing() { app.output.processingIsUnchanged(DspRuntime.snapshot.get().config.mode == SoundMode.DIRECT && !DspRuntime.state.value.applied && player.volume == 1f && player.playbackParameters == PlaybackParameters.DEFAULT && !player.skipSilenceEnabled) }
    /** Metadata-only updates keep the exact existing LocalConfiguration and clipping/queue state. */
    private fun refreshQueueMetadata() {
        for (index in 0 until player.mediaItemCount) {
            val current = player.getMediaItemAt(index)
            val track = libraryIndex[current.mediaId] ?: continue
            if (!current.mediaMetadata.matches(track)) {
                player.replaceMediaItem(index, current.buildUpon().setMediaMetadata(track.sessionMetadata()).build())
            }
        }
    }
    private fun readGainMetadata(metadata:Metadata?) {
        if(metadata==null)return
        val values=mutableMapOf<String,String>()
        for(i in 0 until metadata.length()){when(val entry=metadata[i]){
            is VorbisComment -> values[entry.key]=entry.value
            is TextInformationFrame -> if(entry.id=="TXXX")entry.description?.let{values[it]=entry.values.firstOrNull().orEmpty()}
        }}
        if(values.isNotEmpty())DspRuntime.metadata(values)
    }
    private fun loadAnalysis(){
        analysisJob?.cancel()
        val uri=player.currentMediaItem?.mediaId?:return
        analysisJob=scope.launch { val track=withContext(Dispatchers.IO){app.library.dao.track(uri)}?:return@launch
            val cfg=DspRuntime.snapshot.get().config
            val(profile,message)=analysis.cached(track,cfg)
            if(player.currentMediaItem?.mediaId==uri&&DspRuntime.snapshot.get().config==cfg)DspRuntime.analysis(profile,message)
        }
    }
    private fun snapshot() = PlaybackSnapshot(
        uris = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
        index = player.currentMediaItemIndex.coerceAtLeast(0),
        position = player.currentPosition.coerceAtLeast(0),
        repeat = player.repeatMode,
        shuffle = player.shuffleModeEnabled,
        shuffleOrder = player.currentTimeline.shuffledOrder(),
    )
    private fun save(queueOnly: Boolean = false) {
        if (!restored) return
        val value = snapshot()
        val last = lastScheduledSnapshot
        // Enrichment changes Timeline metadata, not the queue. Keep position writes on the
        // normal timer/transport events rather than writing DataStore for every artwork tag.
        if (queueOnly && last != null && value.copy(position = last.position) == last) return
        lastScheduledSnapshot = value
        scope.launch { app.settings.saveSnapshot(value) }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() {
        scope.cancel()
        if (::player.isInitialized && (restored || player.mediaItemCount > 0)) {
            val value = snapshot()
            runBlocking(Dispatchers.IO) { app.settings.saveSnapshot(value) }
        }
        session?.release(); session = null
        if (::player.isInitialized) player.release()
        app.output.close()
        DspRuntime.stopped()
        super.onDestroy()
    }
}

/** Translate generic notification and headset skip commands to explicit media-item navigation. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class SessionQueuePlayer(private val engine: Player) : ForwardingPlayer(engine) {
    override fun seekToPrevious() = seekToPreviousMediaItem()
    override fun seekToNext() = seekToNextMediaItem()

    override fun seekToPreviousMediaItem() {
        if (engine.neighborIndex(forward = false) != null) engine.seekToPreviousMediaItem()
    }

    override fun seekToNextMediaItem() {
        if (engine.neighborIndex(forward = true) != null) engine.seekToNextMediaItem()
    }
}

private fun MediaMetadata.matches(track: Track): Boolean =
    title?.toString() == track.title && artist?.toString() == track.artist && albumTitle?.toString() == track.album &&
        albumArtist?.toString() == track.albumArtist.ifBlank { track.artist } && artworkUri?.toString() == track.artwork &&
        trackNumber == track.trackNumber && discNumber == track.discNumber

private fun Track.sessionMetadata(): MediaMetadata =
    MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).setAlbumArtist(albumArtist.ifBlank { artist })
        .setArtworkUri(artwork?.let(Uri::parse)).setTrackNumber(trackNumber).setDiscNumber(discNumber).build()

fun Track.mediaItem(): MediaItem = MediaItem.Builder().setMediaId(uri).setUri(uri).setMediaMetadata(sessionMetadata()).build()
