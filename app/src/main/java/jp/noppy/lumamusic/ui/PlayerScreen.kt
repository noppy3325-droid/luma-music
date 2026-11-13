package jp.noppy.lumamusic.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import jp.noppy.lumamusic.core.parseLrc
import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.playback.PlaybackUi

@Composable
internal fun PlayerScreen(
    vm: LumaViewModel,
    playback: PlaybackUi,
    track: Track?,
    favorite: Boolean,
    standby: StandbyController,
    output: () -> Unit,
    close: () -> Unit,
    sound: () -> Unit,
    sheet: PlayerSheetState,
    hero: Painter?,
    miniSurface: Color,
    wide: Boolean,
    panel: String,
    setPanel: (String) -> Unit
) {
    val title = track?.title ?: playback.title
    val artist = track?.artist ?: playback.artist
    val album = track?.album.orEmpty().takeUnless {
        it.isBlank() || it == "不明なアルバム" || it.equals("Unknown Album", true) || it.equals("Unknown", true)
    }.orEmpty()
    val outputState by vm.output.collectAsStateWithLifecycle()
    var menu by rememberSaveable { mutableStateOf(false) }
    val playerColors = remember {
        darkColorScheme(
            background = Color(0xFF161319),
            surface = Color(0xFF201C23),
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFC9C4CE),
            primary = Color(0xFFF3ACC2)
        )
    }
    MaterialTheme(colorScheme = playerColors) {
        LiquidBackdropHost(
            Modifier.fillMaxSize(),
            content = { PlayerArtworkBackdrop(sheet, hero, track?.artwork ?: playback.artwork, title, miniSurface, wide, panel != "player") },
            overlay = {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                        val compact = wide || maxHeight < 710.dp
                        val compactDetailsHeight = maxHeight * if (maxHeight < 570.dp) .78f else .72f
                        val chrome = Modifier.graphicsLayer {
                            val progress = sheet.progress
                            alpha = ((progress - .22f) / .78f).coerceIn(0f, 1f)
                            translationY = (1f - progress) * 86.dp.toPx()
                        }
                        val panelContent: @Composable (Modifier) -> Unit = { modifier ->
                            when (panel) {
                                "lyrics" -> LiveLyricsPanel(vm, track, modifier)
                                "queue" -> Box(modifier.liquidSurface(24).padding(horizontal = 16.dp)) {
                                    QueuePanel(vm, playback, Modifier.fillMaxSize())
                                }
                                else -> Box(modifier.playerSheetDrag(sheet, opening = false))
                            }
                        }
                        val header: @Composable (Modifier) -> Unit = { modifier ->
                            Row(modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(close) { Icon(Icons.Rounded.KeyboardArrowDown, "プレイヤーを閉じる", Modifier.size(30.dp)) }
                                Box(
                                    Modifier.weight(1f).fillMaxHeight().playerSheetDrag(sheet, opening = false).padding(start = 8.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (album.isNotEmpty()) TrackText(album, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(.88f))
                                }
                                Box {
                                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreHoriz, "再生メニュー") }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("横置きスタンバイ") },
                                            leadingIcon = { Icon(Icons.Rounded.ScreenRotation, null) },
                                            onClick = { menu = false; standby.arm() }
                                        )
                                    }
                                }
                            }
                        }
                        val details: @Composable ColumnScope.() -> Unit = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    TrackText(title, style = if (compact) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.headlineMedium)
                                    if (artist.isNotBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        val artistStyle = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium
                                        TrackText(artist, style = artistStyle, color = playerColors.onSurfaceVariant)
                                    }
                                }
                                IconButton({ track?.let { vm.favorite(it.uri) } }, enabled = track != null) {
                                    Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (favorite) "お気に入りを解除" else "お気に入りに登録", tint = if (favorite) playerColors.primary else Color.White)
                                }
                            }
                            SeekControls(vm, playback, compact = true)
                            PlaybackButtons(vm, playback, compact = compact)
                            VolumeControl(bitPerfect = outputState.bitPerfectVolumeRestricted)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                PlayerTool("歌詞", Icons.Rounded.Lyrics, panel == "lyrics", compact) { setPanel(if (panel == "lyrics") "player" else "lyrics") }
                                PlayerTool("出力", Icons.Rounded.SpeakerGroup, false, compact, output)
                                PlayerTool("キュー", Icons.Rounded.QueueMusic, panel == "queue", compact) { setPanel(if (panel == "queue") "player" else "queue") }
                                PlayerTool("サウンド", Icons.Rounded.Equalizer, false, compact, sound)
                            }
                            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
                        }
                        if (wide) {
                            Row(
                                Modifier.fillMaxSize().padding(horizontal = LumaSpacing.Page).then(chrome),
                                horizontalArrangement = Arrangement.spacedBy(28.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                panelContent(Modifier.weight(1f).fillMaxHeight())
                                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                    header(Modifier)
                                    if (standby.armed) Text("横向きでスタンバイを表示", style = MaterialTheme.typography.labelSmall)
                                    details()
                                }
                            }
                        } else {
                            val insetChrome = Modifier.padding(horizontal = LumaSpacing.Page).then(chrome)
                            Column(Modifier.fillMaxSize()) {
                                header(insetChrome)
                                if (standby.armed) Text("スタンバイ待機中 · 端末を横向きに", modifier = insetChrome, style = MaterialTheme.typography.labelSmall)
                                val panelArea = Modifier.weight(1f).fillMaxWidth()
                                // The noninteractive artwork fills the safe area, including both margins.
                                panelContent(if (panel == "player") panelArea else panelArea.then(insetChrome))
                                Column(
                                    insetChrome.heightIn(max = compactDetailsHeight).verticalScroll(rememberScrollState()),
                                    content = details
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable private fun RowScope.PlayerTool(label: String, icon: ImageVector, selected: Boolean, compact: Boolean, action: () -> Unit) {
    Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = action).padding(horizontal = 4.dp, vertical = if (compact) 0.dp else 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(21.dp), tint = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(.78f))
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        TrackText(label, style = if (compact) MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp) else MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(.78f))
    }
}

@Composable fun SeekControls(vm: LumaViewModel, playback: PlaybackUi, compact: Boolean = false) {
    val live by vm.playback.state.collectAsStateWithLifecycle()
    var dragged by remember(live.mediaId, live.index) { mutableStateOf<Float?>(null) }
    val duration = live.duration.toFloat().coerceAtLeast(1f)
    val position = (dragged ?: live.position.toFloat()).coerceIn(0f, duration)
    val slider: @Composable () -> Unit = {
        key(live.mediaId, live.index) {
            LumaSlider(
                position, { dragged = it }, range = 0f..duration, enabled = live.duration > 0,
                onFinished = { dragged?.let { vm.playback.seek(it.toLong()) }; dragged = null }
            )
        }
    }
    val times: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val style = if (compact) MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp) else MaterialTheme.typography.labelSmall
            Text(if (live.duration > 0) timeText(position.toLong()) else "—", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (live.duration > 0) "−${timeText(live.duration - position.toLong())}" else "—", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    // Keep the full 48dp gesture target; compact timestamps share that target's lower area.
    if (compact) Box(Modifier.fillMaxWidth().height(48.dp)) { slider(); times(Modifier.align(Alignment.BottomCenter)) }
    else { slider(); times(Modifier) }
}

@Composable
fun PlaybackButtons(vm: LumaViewModel, playback: PlaybackUi, compact: Boolean = false) {
    val playbackHeight = if (compact) 56.dp else 68.dp
    val shuffle: @Composable () -> Unit = {
        PlaybackModeButton(
            Icons.Rounded.Shuffle, "シャッフル", if (playback.shuffle) "オン" else "オフ",
            playback.shuffle, playback.canShuffle && playback.queue.size > 1, { vm.playback.shuffle() },
        )
    }
    val repeat: @Composable () -> Unit = {
        PlaybackModeButton(
            if (playback.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            "リピート切替",
            when (playback.repeat) {
                Player.REPEAT_MODE_ALL -> "全曲リピート"
                Player.REPEAT_MODE_ONE -> "1曲リピート"
                else -> "オフ"
            },
            playback.repeat != Player.REPEAT_MODE_OFF, playback.canRepeat, { vm.playback.repeat() },
        )
    }
    val transport: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier.height(playbackHeight), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.playback.previousItem() }, enabled = playback.previousIndex != null) {
                Icon(Icons.Rounded.SkipPrevious, "前の曲", Modifier.size(34.dp))
            }
            IconButton(vm.playback::toggle, Modifier.size(if (compact) 56.dp else 64.dp)) {
                Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "一時停止" else "再生", Modifier.size(if (compact) 42.dp else 50.dp))
            }
            IconButton({ vm.playback.next() }, enabled = playback.nextIndex != null) {
                Icon(Icons.Rounded.SkipNext, "次の曲", Modifier.size(34.dp))
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 256.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                shuffle()
                transport(Modifier.weight(1f))
                repeat()
            }
        } else {
            Column {
                transport(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    shuffle()
                    repeat()
                }
            }
        }
    }
}

@Composable
private fun PlaybackModeButton(
    image: ImageVector, label: String, description: String, active: Boolean, enabled: Boolean, onClick: () -> Unit
) {
    IconButton(onClick, modifier = Modifier.size(48.dp).semantics {
        selected = active
        stateDescription = description
    }, enabled = enabled) {
        val tint = when {
            !enabled -> LocalContentColor.current.copy(alpha = .38f)
            active -> MaterialTheme.colorScheme.primary
            else -> LocalContentColor.current.copy(alpha = .68f)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(image, label, Modifier.size(22.dp), tint = tint)
            if (active && enabled) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).size(3.dp).background(tint, CircleShape))
        }
    }
}

@Composable private fun LiveLyricsPanel(vm: LumaViewModel, track: Track?, modifier: Modifier) {
    val playback by vm.playback.state.collectAsStateWithLifecycle()
    LyricsPanel(vm, track, playback.position, modifier)
}

@Composable fun LyricsPanel(vm: LumaViewModel, track: Track?, position: Long, modifier: Modifier = Modifier) {
    var text by remember(track?.uri) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(track?.uri) { if (track != null) { runCatching { vm.library.lyrics(track) }.onSuccess { text = it; error = null }.onFailure { error = it.localizedMessage } } }
    val lines = remember(text) { parseLrc(text) }
    val current = lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= position }
    val list = rememberLazyListState()
    val reducedMotion = LocalReducedMotion.current
    LaunchedEffect(current, reducedMotion) { if (current >= 0) { val index = (current - 1).coerceAtLeast(0); if (reducedMotion) list.scrollToItem(index) else list.animateScrollToItem(index) } }
    Column(modifier.fillMaxWidth()) {
        TextButton({ draft = text; edit = true }, enabled = track != null) { Icon(Icons.Rounded.Edit, null); Text("歌詞を編集") }
        if (lines.isEmpty()) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(error ?: "歌詞はまだありません。\nLRCファイルを曲と同じフォルダーに置くか、手動で追加できます。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            itemsIndexed(lines) { index, line -> Text(line.text.ifBlank { "♪" }, fontSize = 27.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = if (current == index || line.timeMs == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(.35f), modifier = Modifier.fillMaxWidth().clickable(enabled = line.timeMs != null) { line.timeMs?.let(vm.playback::seek) }) }
        }
    }
    if (edit) AlertDialog(onDismissRequest = { edit = false }, title = { Text("歌詞 / LRC") }, text = { OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().height(300.dp), placeholder = { Text("[00:12.50]歌詞\nまたは通常のテキスト") }) }, confirmButton = { TextButton({ track?.let { selected -> vm.action { vm.library.saveLyrics(selected.uri, draft) } }; text = draft; edit = false }) { Text("保存") } }, dismissButton = { TextButton({ edit = false }) { Text("キャンセル") } })
}

@Composable private fun QueuePanel(vm: LumaViewModel, playback: PlaybackUi, modifier: Modifier = Modifier) {
    var editOriginalOrder by rememberSaveable(playback.shuffle) { mutableStateOf(false) }
    val showPlaybackOrder = playback.shuffle && !editOriginalOrder
    val upcoming = playback.upcomingIndices
    val remaining = remember(playback.queue, playback.index, upcoming) {
        val shown = upcoming.toSet()
        playback.queue.indices.filter { it != playback.index && it !in shown }
    }
    LazyColumn(modifier) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackText(
                    if (editOriginalOrder) "元の順序" else "次はこちら",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                if (playback.shuffle) {
                    TextButton({ editOriginalOrder = !editOriginalOrder }) {
                        TrackText(if (editOriginalOrder) "再生順に戻る" else "元の順序を編集", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (editOriginalOrder) {
                Text("シャッフル再生中", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            } else if (playback.shuffle || playback.repeat == Player.REPEAT_MODE_ONE) {
                val mode = when {
                    playback.shuffle && playback.repeat == Player.REPEAT_MODE_ONE -> "シャッフル順 · 1曲リピート中"
                    playback.shuffle -> "シャッフル順"
                    else -> "1曲リピート中"
                }
                Text(mode, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (!showPlaybackOrder) {
                val nextTitle = playback.nextItem?.mediaMetadata?.title?.toString()
                if (!nextTitle.isNullOrBlank()) {
                    val label = if (playback.repeat == Player.REPEAT_MODE_ONE) "手動で次に進む曲" else "次に再生"
                    TrackText("$label · $nextTitle", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
        if (showPlaybackOrder) {
            queueRows(vm, playback, listOf(playback.index) + upcoming, reorder = false)
            if (remaining.isNotEmpty()) {
                item { Text("キュー内のほかの曲", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 12.dp)) }
                queueRows(vm, playback, remaining, reorder = false)
            }
        } else {
            queueRows(vm, playback, playback.queue.indices.toList(), reorder = true)
        }
    }
}

private fun LazyListScope.queueRows(vm: LumaViewModel, playback: PlaybackUi, indices: List<Int>, reorder: Boolean) {
    val rows = indices.mapNotNull { index -> playback.queue.getOrNull(index)?.let { index to it } }
    items(rows, key = { (index, item) -> "${item.mediaId}-$index" }) { (index, item) ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { vm.playback.skip(index, item) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val marker = when (index) {
                playback.index -> "再生中"
                playback.nextIndex -> if (playback.repeat == Player.REPEAT_MODE_ONE) "手動で次に進む曲" else "次に再生"
                else -> null
            }
            Column(Modifier.weight(1f)) {
                TrackText(item.mediaMetadata.title?.toString().orEmpty())
                if (marker != null) Text(marker, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            }
            if (reorder) {
                DragHandle(index, playback.queue.size, vm.playback::move)
                IconButton({ vm.playback.move(index, index - 1) }, enabled = index > 0) {
                    Icon(Icons.Rounded.ArrowUpward, "キューを上に移動")
                }
            }
            IconButton({ vm.playback.remove(index) }) { Icon(Icons.Rounded.Close, "キューから削除") }
        }
    }
}
