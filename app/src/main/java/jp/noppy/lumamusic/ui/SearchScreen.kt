package jp.noppy.lumamusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.noppy.lumamusic.data.MusicFolder
import jp.noppy.lumamusic.data.MusicPlaylist
import jp.noppy.lumamusic.data.PlaylistEntry
import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.data.UserTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SearchKind(val label: String) {
    All("すべて"),
    Tracks("楽曲"),
    Artists("アーティスト"),
    Albums("アルバム"),
    Playlists("プレイリスト"),
}

@Composable
internal fun SearchScreen(
    vm: LumaViewModel,
    tracks: List<Track>,
    folders: List<MusicFolder>,
    users: List<UserTrack>,
    playlists: List<MusicPlaylist>,
    entries: List<PlaylistEntry>,
    addFolder: () -> Unit,
    openPlayer: () -> Unit,
    bottomContentPadding: Dp,
    active: Boolean = true,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SearchKind.All) }
    var detailCategory by rememberSaveable { mutableStateOf<LibraryCategory?>(null) }
    var detailKey by rememberSaveable { mutableStateOf("") }
    var detailName by rememberSaveable { mutableStateOf("") }
    val index by vm.searchIndex.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val playback by vm.playbackSummary.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val favorites = remember(users) { users.filter(UserTrack::favorite).map(UserTrack::uri).toSet() }
    var focused by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(110)
        searchQuery = query
    }
    val results by produceState<Pair<String, SearchResults?>?>(null, index, searchQuery) {
        value = searchQuery to if (searchQuery.isBlank()) null else withContext(Dispatchers.Default) { index?.search(searchQuery) }
    }

    fun finishEditing() {
        focus.clearFocus()
        keyboard?.hide()
    }

    fun openDetail(category: LibraryCategory, key: String, name: String) {
        finishEditing()
        detailCategory = category
        detailKey = key
        detailName = name
    }

    val category = detailCategory
    if (category != null) {
        key(category, detailKey) {
            LibraryScreen(
                vm, tracks, folders, users, playlists, entries, addFolder, openPlayer,
                bottomContentPadding = bottomContentPadding,
                active = active,
                initialDetail = LibraryDetail(category, detailKey, detailName),
                onCloseDetail = { detailCategory = null },
            )
        }
        return
    }

    LaunchedEffect(active, focused) {
        if (!active && focused) finishEditing()
    }
    var previousQuery by rememberSaveable { mutableStateOf(searchQuery) }
    LaunchedEffect(results, query) {
        if (results?.first == query && results?.second != null && previousQuery != query) {
            listState.scrollToItem(0)
            previousQuery = query
        }
    }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        PageHeading("検索")
        SearchField(query, { query = it }, requester, { focused = it }, ::finishEditing)
        val found = results?.takeIf { it.first == query && query.isNotBlank() }?.second
        if (found != null && !found.isEmpty) {
            ChoiceChips(SearchKind.entries.map(SearchKind::label), filter.label) { label ->
                filter = SearchKind.entries.first { it.label == label }
                scope.launch { listState.scrollToItem(0) }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = libraryListPadding(bottomContentPadding)) {
            if (query.isBlank()) return@LazyColumn
            if (found == null) return@LazyColumn
            if (found.isEmpty) {
                item {
                    val noLibrary = tracks.isEmpty() && folders.isEmpty()
                    val inaccessible = folders.any { it.error != null } && tracks.isEmpty()
                    SearchMessage(
                        when {
                            scan.running && tracks.isEmpty() -> "ライブラリを読み込み中"
                            inaccessible -> "フォルダーにアクセスできません"
                            noLibrary -> "音楽フォルダーが登録されていません"
                            else -> "一致する項目がありません"
                        },
                        when {
                            scan.running && tracks.isEmpty() -> "読み込みが進むと、結果を表示します。"
                            inaccessible -> "フォルダーを選び直してから検索してください。"
                            noLibrary -> "端末内のフォルダーを追加すると、楽曲を検索できます。"
                            else -> "曲名やアーティスト名の一部で検索できます。"
                        },
                        when {
                            scan.running && tracks.isEmpty() -> null
                            inaccessible || noLibrary -> "フォルダーを選ぶ"
                            else -> "検索語を変える"
                        },
                    ) {
                        if (inaccessible || noLibrary) {
                            finishEditing()
                            addFolder()
                        } else {
                            requester.requestFocus()
                            keyboard?.show()
                        }
                    }
                }
                return@LazyColumn
            }

            val all = filter == SearchKind.All
            val filteredEmpty = when (filter) {
                SearchKind.All -> false
                SearchKind.Tracks -> found.tracks.isEmpty()
                SearchKind.Artists -> found.artists.isEmpty()
                SearchKind.Albums -> found.albums.isEmpty()
                SearchKind.Playlists -> found.playlists.isEmpty()
            }
            if (filteredEmpty) item {
                SearchMessage("一致する${filter.label}がありません", null, "すべての結果を表示") { filter = SearchKind.All }
            }
            if ((all || filter == SearchKind.Tracks) && found.tracks.isNotEmpty()) {
                if (all) searchHeading(SearchKind.Tracks)
                itemsIndexed(found.tracks, key = { _, track -> "track:${track.uri}" }, contentType = { _, _ -> "track" }) { position, track ->
                    TrackRow(
                        track, track.uri in favorites,
                        {
                            finishEditing()
                            vm.browseTrack(found.tracks, position, openPlayer)
                        },
                        { vm.favorite(track.uri) }, vm, playlists,
                        current = track.uri == playback.mediaId, playing = playback.playing,
                    )
                }
            }
            if ((all || filter == SearchKind.Artists) && found.artists.isNotEmpty()) {
                if (all) searchHeading(SearchKind.Artists)
                items(found.artists, key = { "artist:$it" }, contentType = { "group" }) { artist ->
                    GroupRow(artist, icon = Icons.Rounded.Person) { openDetail(LibraryCategory.Artists, artist, artist) }
                }
            }
            if ((all || filter == SearchKind.Albums) && found.albums.isNotEmpty()) {
                if (all) searchHeading(SearchKind.Albums)
                items(found.albums, key = { "album:${it.key}" }, contentType = { "group" }) { album ->
                    val track = album.track
                    LaunchedEffect(track.uri, track.modified, track.size, track.detailState) { vm.ensureDetails(track.uri) }
                    GroupRow(album.title, album.artist, Icons.Rounded.Album, track.artworkThumbnail ?: track.artwork) {
                        openDetail(LibraryCategory.Albums, album.key, album.title)
                    }
                }
            }
            if ((all || filter == SearchKind.Playlists) && found.playlists.isNotEmpty()) {
                if (all) searchHeading(SearchKind.Playlists)
                items(found.playlists, key = { "playlist:${it.id}" }, contentType = { "group" }) { playlist ->
                    GroupRow(playlist.name, icon = Icons.Rounded.PlaylistPlay, artwork = playlist.cover) {
                        openDetail(LibraryCategory.Playlists, playlist.id.toString(), playlist.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    change: (String) -> Unit,
    requester: FocusRequester,
    onFocus: (Boolean) -> Unit,
    submit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    BasicTextField(
        value = query,
        onValueChange = change,
        modifier = Modifier.padding(horizontal = LumaSpacing.Page, vertical = 6.dp)
            .fillMaxWidth().focusRequester(requester).onFocusChanged { onFocus(it.isFocused) }
            .semantics { contentDescription = "ライブラリを検索" },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { submit() }),
        cursorBrush = SolidColor(scheme.primary),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clip(LumaShapes.Search).background(scheme.onSurface.copy(.065f)).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.Search, null, Modifier.size(21.dp), tint = scheme.onSurfaceVariant)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) TrackText(
                        "楽曲、アーティスト、アルバムなど",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant,
                    )
                    field()
                }
                if (query.isNotEmpty()) IconButton({ change("") }) {
                    Icon(Icons.Rounded.Cancel, "検索をクリア", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                } else Spacer(Modifier.width(8.dp))
            }
        },
    )
}

private fun LazyListScope.searchHeading(kind: SearchKind) {
    item(key = "heading:${kind.name}", contentType = "heading") {
        Text(
            kind.label,
            Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun SearchMessage(title: String, body: String?, action: String?, onAction: () -> Unit) {
    Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (body != null) Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (action != null) TextButton(onAction) { Text(action) }
    }
}
