package jp.noppy.lumamusic.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.noppy.lumamusic.core.albumKey
import jp.noppy.lumamusic.core.normalizedSearch
import jp.noppy.lumamusic.data.MusicFolder
import jp.noppy.lumamusic.data.MusicPlaylist
import jp.noppy.lumamusic.data.PlaylistEntry
import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.data.UserTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class LibraryCategory(val label: String, val icon: ImageVector) {
    Songs("楽曲", Icons.Rounded.MusicNote),
    Albums("アルバム", Icons.Rounded.Album),
    Artists("アーティスト", Icons.Rounded.Person),
    Folders("フォルダー", Icons.Rounded.Folder),
    Favorites("お気に入り", Icons.Rounded.FavoriteBorder),
    Genres("ジャンル", Icons.Rounded.LibraryMusic),
    Playlists("プレイリスト", Icons.Rounded.PlaylistPlay),
}

internal data class LibraryDetail(val category: LibraryCategory, val key: String, val name: String)

private enum class TrackSort(val label: String) {
    Newest("新しい順"),
    Title("曲名順"),
    Artist("アーティスト順"),
}

private val albumOrder = compareBy(Track::discNumber, Track::trackNumber, Track::title)

/** The chip scroller owns horizontal drags even when it is already at an edge. */
private object ChipScrollBoundary : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
        Offset(available.x, 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity) =
        Velocity(available.x, 0f)
}

@Composable
internal fun ChoiceChips(
    choices: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .nestedScroll(ChipScrollBoundary)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = LumaSpacing.Page, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { label ->
            val chosen = label == selected
            val scheme = MaterialTheme.colorScheme
            Box(
                Modifier.heightIn(min = 48.dp)
                    .selectable(chosen, onClick = { onSelect(label) }, role = Role.Tab),
                contentAlignment = Alignment.Center,
            ) {
                TrackText(
                    label,
                    Modifier.clip(LumaShapes.Pill)
                        .background(scheme.onSurface.copy(alpha = if (chosen) .1f else .035f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    MaterialTheme.typography.labelLarge.copy(fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium),
                    if (chosen) scheme.onSurface else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun LibraryScreen(
    vm: LumaViewModel,
    tracks: List<Track>,
    folders: List<MusicFolder>,
    users: List<UserTrack>,
    playlists: List<MusicPlaylist>,
    entries: List<PlaylistEntry>,
    addFolder: () -> Unit,
    openPlayer: () -> Unit,
    section: String = "library",
    settings: () -> Unit = {},
    active: Boolean = true,
    initialDetail: LibraryDetail? = null,
    onCloseDetail: () -> Unit = {},
) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val isPlaylists = section == "playlists"
    var category by rememberSaveable { mutableStateOf(initialDetail?.category ?: if (isPlaylists) LibraryCategory.Playlists else LibraryCategory.Songs) }
    var detailKey by rememberSaveable { mutableStateOf(initialDetail?.key) }
    var detailName by rememberSaveable { mutableStateOf(initialDetail?.name.orEmpty()) }
    var folderPath by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(TrackSort.Newest) }
    var showSort by remember { mutableStateOf(false) }
    var createPlaylist by remember { mutableStateOf(false) }
    var playlistName by rememberSaveable { mutableStateOf("") }
    val stateHolder = rememberSaveableStateHolder()
    val favorites = remember(users) { users.filter(UserTrack::favorite).map(UserTrack::uri).toSet() }
    val albums = remember(tracks) { tracks.groupBy { albumKey(it.album, it.albumArtist, it.artist) } }
    val byUri = remember(tracks) { tracks.associateBy(Track::uri) }
    val playlistId = detailKey?.toLongOrNull().takeIf { category == LibraryCategory.Playlists }
    val playlistEntries = remember(entries, playlistId) { entries.filter { it.playlistId == playlistId }.sortedBy(PlaylistEntry::position) }
    var folderTracks by remember(detailKey, category) { mutableStateOf<List<Track>>(emptyList()) }

    fun closeDetail() {
        if (folderPath.isNotEmpty()) folderPath = folderPath.substringBeforeLast('/', "")
        else if (initialDetail != null) onCloseDetail()
        else detailKey = null
    }
    fun openDetail(key: String, name: String) {
        detailKey = key
        detailName = name
        folderPath = ""
    }

    BackHandler(active && detailKey != null) { closeDetail() }
    LaunchedEffect(detailKey, category, tracks) {
        val key = detailKey
        if (category == LibraryCategory.Folders && key != null) folderTracks = vm.library.dao.tracksInFolder(key)
    }

    val tracksByParent = remember(folderTracks, detailKey) {
        val root = detailKey?.let { runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it)) }.getOrNull() }
        folderTracks.groupBy { track ->
            runCatching {
                DocumentsContract.getDocumentId(Uri.parse(track.uri))
                    .removePrefix(root.orEmpty()).trimStart('/').substringBeforeLast('/', "")
            }.getOrDefault("")
        }
    }
    val childFolders = remember(tracksByParent, folderPath) {
        val prefix = if (folderPath.isEmpty()) "" else "$folderPath/"
        tracksByParent.keys.asSequence()
            .filter { it != folderPath && it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .filter(String::isNotEmpty).distinct().sorted().toList()
    }

    val scopeTracks = remember(category, detailKey, tracks, favorites, albums, tracksByParent, folderPath, byUri, playlistEntries) {
        when {
            category == LibraryCategory.Favorites -> tracks.filter { it.uri in favorites }
            category == LibraryCategory.Albums && detailKey != null -> albums[detailKey].orEmpty().sortedWith(albumOrder)
            category == LibraryCategory.Artists && detailKey != null -> tracks.filter { it.artist == detailKey }
            category == LibraryCategory.Genres && detailKey != null -> tracks.filter { it.genre.ifBlank { "未設定" } == detailKey }
            category == LibraryCategory.Folders && detailKey != null -> tracksByParent[folderPath].orEmpty()
            playlistId != null -> playlistEntries.map { entry ->
                byUri[entry.trackUri] ?: Track(entry.trackUri, "見つからない曲", "フォルダーとアクセス権限を確認してください")
            }
            else -> tracks
        }
    }
    val visible = if (sort == TrackSort.Newest || category == LibraryCategory.Albums || playlistId != null) {
        // Room's library flow is already ordered by date added. Folder detail is
        // queried separately so apply the same stable sort at that entry point.
        if (category == LibraryCategory.Folders && detailKey != null) {
            remember(scopeTracks) { scopeTracks.sortedWith(compareByDescending(Track::added).thenBy(Track::uri)) }
        } else scopeTracks
    } else key(category, detailKey, folderPath) {
        val sorted by produceState(scopeTracks, scopeTracks, sort) {
            value = withContext(Dispatchers.Default) {
                val sortKeys = scopeTracks.associate { track ->
                    val title = normalizedSearch(track.title)
                    track.uri to if (sort == TrackSort.Title) title else "${normalizedSearch(track.artist)}\u0000$title"
                }
                scopeTracks.sortedWith(compareBy<Track> { sortKeys.getValue(it.uri) }.thenBy(Track::uri))
            }
        }
        sorted
    }

    Column(Modifier.fillMaxSize()) {
        PageHeading(
            detailKey?.let { detailName } ?: if (isPlaylists) "プレイリスト" else "ライブラリ",
            if (detailKey == null) null else ({ closeDetail() }),
        ) {
            if (detailKey == null && !isPlaylists) {
                IconButton(addFolder) { Icon(Icons.Rounded.FolderOpen, "音楽フォルダーを追加", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(settings) { Icon(Icons.Rounded.Tune, "設定", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (detailKey == null && !isPlaylists) {
            val categories = LibraryCategory.entries.filter { it != LibraryCategory.Playlists }
            ChoiceChips(categories.map(LibraryCategory::label), category.label) { label ->
                category = categories.first { it.label == label }
            }
        }
        if (scan.running && initialDetail == null) {
            Row(Modifier.padding(horizontal = LumaSpacing.Page, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(8.dp))
                TrackText("音楽を読み込み中 · ${scan.count}曲見つかりました", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        stateHolder.SaveableStateProvider("${category.name}:${detailKey.orEmpty()}:$folderPath") {
            when {
                tracks.isEmpty() && detailKey == null && category !in setOf(LibraryCategory.Folders, LibraryCategory.Playlists) -> LibraryEmpty(scan.running, folders, addFolder)
                detailKey == null && category == LibraryCategory.Albums -> {
                    LazyVerticalGrid(
                        GridCells.Adaptive(146.dp),
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = LumaSpacing.Page, end = LumaSpacing.Page, top = 16.dp, bottom = LumaSpacing.FloatingBottom),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(albums.entries.toList(), key = { it.key }, contentType = { "album" }) { (key, songs) ->
                            val song = songs.first()
                            LaunchedEffect(song.uri, song.modified, song.size, song.detailState) { vm.ensureDetails(song.uri) }
                            Column(Modifier.clip(LumaShapes.Artwork).clickable { openDetail(key, song.album) }) {
                                Artwork(song.artworkThumbnail ?: song.artwork, song.album, Modifier.fillMaxWidth().aspectRatio(1f))
                                Spacer(Modifier.height(8.dp))
                                TrackText(song.album)
                                TrackText(song.albumArtist.ifBlank { song.artist }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                detailKey == null && category in setOf(LibraryCategory.Artists, LibraryCategory.Genres, LibraryCategory.Folders, LibraryCategory.Playlists) -> {
                    val groups = remember(category, tracks, folders, playlists) {
                        when (category) {
                            LibraryCategory.Artists -> tracks.map(Track::artist).distinct().sorted().map { it to it }
                            LibraryCategory.Genres -> tracks.map { it.genre.ifBlank { "未設定" } }.distinct().sorted().map { it to it }
                            LibraryCategory.Folders -> folders.map { it.uri to it.name }
                            else -> playlists.map { it.id.toString() to it.name }
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = libraryListPadding()) {
                        if (category == LibraryCategory.Playlists) {
                            item {
                                TextButton({ createPlaylist = true }) {
                                    Icon(Icons.Rounded.Add, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("プレイリストを作成")
                                }
                            }
                        }
                        if (groups.isEmpty()) item {
                            Text("まだ項目がありません", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(groups, key = { it.first }, contentType = { "category" }) { (key, name) ->
                            GroupRow(
                                name,
                                subtitle = folders.firstOrNull { it.uri == key }?.error.takeIf { category == LibraryCategory.Folders },
                                icon = category.icon,
                            ) { openDetail(key, name) }
                        }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = libraryListPadding()) {
                    if (category == LibraryCategory.Folders && detailKey != null) {
                        items(childFolders) { name ->
                            GroupRow(name, icon = Icons.Rounded.Folder) {
                                folderPath = if (folderPath.isEmpty()) name else "$folderPath/$name"
                            }
                        }
                    }
                    if (detailKey != null && visible.isNotEmpty()) item("group_controls") {
                        if (category == LibraryCategory.Albums) AlbumHeader(visible, favorites, vm)
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton({ vm.playback.play(visible); openPlayer() }, Modifier.weight(1f)) {
                                Icon(Icons.Rounded.PlayArrow, null)
                                Text("再生")
                            }
                            FilledTonalButton({ vm.playback.play(visible, shuffle = true); openPlayer() }, Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Shuffle, null)
                                Text("シャッフル", maxLines = 1)
                            }
                        }
                    }
                    if (playlistId != null) {
                        val playlist = playlists.firstOrNull { it.id == playlistId }
                        if (playlist != null) item("playlist_header") {
                            PlaylistHeader(vm, playlist) {
                                if (initialDetail != null) onCloseDetail() else detailKey = null
                            }
                        }
                    }
                    item("track_count") {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${visible.size}曲",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (playlistId == null && category != LibraryCategory.Albums) {
                                Box {
                                    IconButton({ showSort = true }) {
                                        Icon(Icons.Rounded.Sort, "曲の並べ替え", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    DropdownMenu(showSort, { showSort = false }) {
                                        TrackSort.entries.forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option.label) },
                                                onClick = {
                                                    sort = option
                                                    showSort = false
                                                },
                                                leadingIcon = if (sort == option) ({ Icon(Icons.Rounded.Check, "選択中") }) else null,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(
                        visible,
                        key = { index, track -> playlistEntries.getOrNull(index)?.entryId ?: track.uri },
                        contentType = { _, _ -> "track" },
                    ) { index, track ->
                        TrackRow(track, track.uri in favorites, { vm.playback.play(visible, index); openPlayer() }, { vm.favorite(track.uri) }, vm, playlists)
                        if (playlistId != null) {
                            fun move(from: Int, to: Int) {
                                val revised = playlistEntries.toMutableList()
                                revised.add(to, revised.removeAt(from))
                                vm.action { vm.library.savePlaylistEntries(playlistId, revised) }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                DragHandle(index, playlistEntries.size, ::move)
                                IconButton({ move(index, index - 1) }, enabled = index > 0) { Icon(Icons.Rounded.ArrowUpward, "曲を上へ") }
                                IconButton({
                                    vm.action { vm.library.savePlaylistEntries(playlistId, playlistEntries.filterIndexed { i, _ -> i != index }) }
                                }) { Icon(Icons.Rounded.RemoveCircleOutline, "プレイリストから削除") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (createPlaylist) AlertDialog(
        onDismissRequest = { createPlaylist = false },
        title = { Text("新しいプレイリスト") },
        text = { OutlinedTextField(playlistName, { playlistName = it }, label = { Text("名前") }, singleLine = true) },
        confirmButton = {
            TextButton({ vm.action { vm.library.createPlaylist(playlistName) }; createPlaylist = false; playlistName = "" }, enabled = playlistName.isNotBlank()) { Text("作成") }
        },
        dismissButton = { TextButton({ createPlaylist = false }) { Text("キャンセル") } },
    )
}

@Composable
internal fun PageHeading(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack == null) LumaSpacing.Page else 8.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "一覧に戻る") }
        TrackText(title, Modifier.weight(1f), if (onBack == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall)
        actions()
    }
}

internal fun libraryListPadding() =
    PaddingValues(start = LumaSpacing.Page, end = LumaSpacing.Page, top = 8.dp, bottom = LumaSpacing.FloatingBottom)

@Composable
internal fun GroupRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    artwork: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(LumaShapes.Artwork)
            .clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (artwork != null) Artwork(artwork, title, Modifier.size(44.dp), 10)
        else Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(.065f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            TrackText(title)
            subtitle?.let { TrackText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LibraryEmpty(scanning: Boolean, folders: List<MusicFolder>, addFolder: () -> Unit) {
    val errors = folders.mapNotNull(MusicFolder::error)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = LumaSpacing.Page, end = LumaSpacing.Page, top = 32.dp, bottom = LumaSpacing.FloatingBottom),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                scanning -> "音楽を探しています"
                errors.isNotEmpty() -> "フォルダーを確認してください"
                folders.isEmpty() -> "音楽フォルダーを選ぶ"
                else -> "音楽が見つかりませんでした"
            },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                scanning -> "見つかった曲から順に表示します。"
                errors.isNotEmpty() -> errors.joinToString("\n")
                folders.isEmpty() -> "端末に保存した楽曲を追加できます。"
                else -> "対応する音楽ファイルとアクセス権限を確認してください。"
            },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!scanning) TextButton(addFolder) { Text(if (folders.isEmpty()) "フォルダーを追加" else "フォルダーを選び直す") }
    }
}

@Composable
private fun AlbumHeader(visible: List<Track>, favorites: Set<String>, vm: LumaViewModel) {
    val first = visible.first()
    LaunchedEffect(first.uri, first.modified, first.size, first.detailState) { vm.ensureDetails(first.uri) }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(first.artwork, first.album, Modifier.sizeIn(maxWidth = 264.dp).fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(12.dp))
        TrackText(first.albumArtist.ifBlank { first.artist }, style = MaterialTheme.typography.titleMedium)
        TrackText(
            listOf(first.year, first.genre, "${visible.size}曲", timeText(visible.sumOf(Track::duration))).filter(String::isNotBlank).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val allFavorite = visible.all { it.uri in favorites }
        TextButton({ visible.filter { (it.uri in favorites) == allFavorite }.forEach { vm.favorite(it.uri) } }) {
            Text(if (allFavorite) "アルバム全曲のお気に入りを解除" else "アルバム全曲をお気に入りに登録")
        }
    }
}
@Composable
internal fun TrackRow(
    track: Track,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    vm: LumaViewModel,
    playlists: List<MusicPlaylist>,
) {
    LaunchedEffect(track.uri, track.modified, track.size, track.detailState) { vm.ensureDetails(track.uri) }
    var menu by remember { mutableStateOf(false) }
    var addTo by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(LumaShapes.Artwork).clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.artworkThumbnail ?: track.artwork, track.album, Modifier.size(54.dp), 12)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            TrackText(track.title)
            TrackText(track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (favorite) Icon(Icons.Rounded.Favorite, "お気に入り", tint = Rose, modifier = Modifier.size(14.dp))
        Box {
            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreHoriz, "曲の操作") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(if (favorite) "お気に入りを解除" else "お気に入りに登録") }, {
                    onFavorite()
                    menu = false
                })
                DropdownMenuItem({ Text("次に再生") }, {
                    vm.playback.enqueue(track, true)
                    menu = false
                })
                DropdownMenuItem({ Text("キューの最後に追加") }, {
                    vm.playback.enqueue(track, false)
                    menu = false
                })
                DropdownMenuItem({ Text("プレイリストに追加") }, {
                    addTo = true
                    menu = false
                })
            }
        }
    }
    if (addTo) AlertDialog(
        onDismissRequest = { addTo = false },
        title = { Text("プレイリストに追加") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (playlists.isEmpty()) Text("プレイリストタブでプレイリストを作成してください")
                playlists.forEach { list ->
                    TextButton({
                        vm.action { vm.library.addToPlaylist(list.id, track.uri) }
                        addTo = false
                    }) { TrackText(list.name) }
                }
            }
        },
        confirmButton = { TextButton({ addTo = false }) { Text("閉じる") } },
    )
}

@Composable private fun PlaylistHeader(vm: LumaViewModel, playlist: MusicPlaylist, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.action { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); vm.library.dao.playlist(playlist.copy(cover = uri.toString())) }
    }
    var rename by remember { mutableStateOf(false) }
    var name by remember(playlist.name) { mutableStateOf(playlist.name) }
    playlist.cover?.let { Artwork(it, playlist.name, Modifier.padding(top = 12.dp).size(100.dp)) }
    Row { TextButton({ rename = true }) { Text("名前を変更") }; TextButton({ coverPicker.launch(arrayOf("image/*")) }) { Text("カバー") }; TextButton({ vm.action { vm.library.dao.clearPlaylist(playlist.id); vm.library.dao.deletePlaylist(playlist.id) }; onDeleted() }) { Text("削除") } }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("プレイリスト名") }, text = { OutlinedTextField(name, { name = it }) }, confirmButton = { TextButton({ vm.action { vm.library.dao.playlist(playlist.copy(name = name.trim())) }; rename = false }, enabled = name.isNotBlank()) { Text("保存") } })
}

