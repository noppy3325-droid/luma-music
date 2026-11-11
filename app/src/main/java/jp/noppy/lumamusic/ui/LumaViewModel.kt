package jp.noppy.lumamusic.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.noppy.lumamusic.LumaApp
import jp.noppy.lumamusic.core.OutputMode
import jp.noppy.lumamusic.data.*
import jp.noppy.lumamusic.playback.PlaybackController
import jp.noppy.lumamusic.playback.PlaybackUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LumaViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LumaApp
    val library = app.library
    val settings = app.settings
    val playback = PlaybackController(app, viewModelScope)
    val playbackSummary = playback.state.map { it.copy(position = 0L) }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackUi())
    val tracks = library.tracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val folders = library.folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val users = library.userTracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playlists = library.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val entries = library.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    internal val searchIndex = combine(tracks, playlists, ::SearchIndex)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val preferences = settings.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, Preferences())
    val output = app.output.state
    val scan = library.scan
    private val mutableMessage = MutableStateFlow<String?>(null)
    private var lastScan = 0L
    val message = mutableMessage.asStateFlow()
    init {
        rescan(false)
        viewModelScope.launch {
            playback.state.map { it.mediaId }.distinctUntilChanged().filterNotNull().collect { uri ->
                action { library.ensureDetails(uri) }
            }
        }
    }
    fun action(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; mutableMessage.value = e.localizedMessage ?: "操作に失敗しました" } } }
    fun dismissMessage() { mutableMessage.value = null }
    fun addFolder(uri: Uri) { action { library.addFolder(uri) } }
    fun removeFolder(folder: MusicFolder) { playback.pause(); action { library.removeFolder(folder) } }
    fun rescan(force: Boolean = true) { lastScan = android.os.SystemClock.elapsedRealtime(); action { library.rescan(force) } }
    fun refreshIfStale() { if (!scan.value.running && android.os.SystemClock.elapsedRealtime() - lastScan > 60000) rescan(false) }
    fun favorite(uri: String) { action { library.toggleFavorite(uri) } }
    suspend fun ensureDetails(uri: String) { library.ensureDetails(uri) }
    fun mode(mode: OutputMode) { action { settings.mode(mode) } }
    override fun onCleared() { playback.close(); super.onCleared() }
}
