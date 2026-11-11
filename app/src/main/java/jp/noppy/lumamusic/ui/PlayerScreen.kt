package jp.noppy.lumamusic.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                        val compactDetailsHeight = maxHeight.takeIf { it < 570.dp }?.times(.66f)
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
                        val header: @Composable () -> Unit = {
                            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(close) { Icon(Icons.Rounded.KeyboardArrowDown, "プレイヤーを閉じる", Modifier.size(30.dp)) }
                                Box(
                                    Modifier.weight(1f).fillMaxHeight().playerSheetDrag(sheet, opening = false),
                                    contentAlignment = Alignment.Center
                                ) {
                                    TrackText(album, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(.88f))
                                }
                                Box {
                                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreHoriz, "再生メニュー") }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(if (playback.shuffle) "シャッフルをオフ" else "シャッフル") },
                                            leadingIcon = { Icon(Icons.Rounded.Shuffle, null) },
                                            onClick = { menu = false; vm.playback.shuffle() }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(when (playback.repeat) { 1 -> "全曲リピート"; 2 -> "リピートをオフ"; else -> "1曲リピート" }) },
                                            leadingIcon = { Icon(Icons.Rounded.Repeat, null) },
                                            onClick = { menu = false; vm.playback.repeat() }
                                        )
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
                                    TrackText(title, style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium)
                                    if (artist.isNotBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        val artistStyle = if (compact) MaterialTheme.typography.titleMedium else
                                            MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 26.sp)
                                        TrackText(artist, style = artistStyle, color = playerColors.onSurfaceVariant)
                                    }
                                }
                                IconButton({ track?.let { vm.favorite(it.uri) } }, enabled = track != null) {
                                    Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (favorite) "お気に入りを解除" else "お気に入りに登録", tint = if (favorite) playerColors.primary else Color.White)
                                }
                            }
                            SeekControls(vm, playback, compact = compact)
                            PlaybackButtons(vm, playback, compact = compact, secondary = false)
                            VolumeControl()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                PlayerTool("歌詞", Icons.Rounded.Lyrics, panel == "lyrics", compact) { setPanel(if (panel == "lyrics") "player" else "lyrics") }
                                PlayerTool("出力", Icons.Rounded.SpeakerGroup, false, compact, output)
                                PlayerTool("キュー", Icons.Rounded.QueueMusic, panel == "queue", compact) { setPanel(if (panel == "queue") "player" else "queue") }
                                PlayerTool("サウンド", Icons.Rounded.Equalizer, false, compact, sound)
                            }
                            Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                        }
                        if (wide) {
                            Row(
                                Modifier.fillMaxSize().padding(horizontal = LumaSpacing.Page).then(chrome),
                                horizontalArrangement = Arrangement.spacedBy(28.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                panelContent(Modifier.weight(1f).fillMaxHeight())
                                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                    header()
                                    if (standby.armed) Text("横向きでスタンバイを表示", style = MaterialTheme.typography.labelSmall)
                                    details()
                                }
                            }
                        } else {
                            Column(Modifier.fillMaxSize().padding(horizontal = LumaSpacing.Page).then(chrome)) {
                                header()
                                if (standby.armed) Text("スタンバイ待機中 · 端末を横向きに", style = MaterialTheme.typography.labelSmall)
                                panelContent(Modifier.weight(1f).fillMaxWidth())
                                if (compactDetailsHeight != null) {
                                    Column(Modifier.heightIn(max = compactDetailsHeight).verticalScroll(rememberScrollState()), content = details)
                                } else {
                                    details()
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable private fun PlayerTool(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, compact: Boolean, action: () -> Unit) {
    Column(Modifier.widthIn(min = 64.dp).heightIn(min = 48.dp).clickable(onClick = action).padding(horizontal = 10.dp, vertical = if (compact) 0.dp else 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(21.dp), tint = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(.78f))
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        TrackText(label, style = if (compact) MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp) else MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(.78f))
    }
}

@Composable fun SeekControls(vm: LumaViewModel, playback: PlaybackUi, compact: Boolean = false) {
    val live by vm.playback.state.collectAsStateWithLifecycle()
    var dragged by remember(live.mediaId) { mutableStateOf<Float?>(null) }
    val duration = live.duration.toFloat().coerceAtLeast(1f)
    val position = (dragged ?: live.position.toFloat()).coerceIn(0f, duration)
    val slider: @Composable () -> Unit = { LumaSlider(position, { dragged = it }, range = 0f..duration, enabled = live.duration > 0, onFinished = { dragged?.let { vm.playback.seek(it.toLong()) }; dragged = null }) }
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

@Composable fun PlaybackButtons(vm: LumaViewModel, playback: PlaybackUi, compact: Boolean = false, secondary: Boolean = true) {
    Row(Modifier.fillMaxWidth().height(if (compact) 56.dp else 78.dp), horizontalArrangement = if (secondary) Arrangement.SpaceBetween else Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        if (secondary) IconButton(vm.playback::shuffle) { Icon(Icons.Rounded.Shuffle, "シャッフル", Modifier.size(22.dp), tint = if (playback.shuffle) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(.5f)) }
        IconButton(vm.playback::previous) { Icon(Icons.Rounded.SkipPrevious, "前の曲", Modifier.size(34.dp)) }
        IconButton(vm.playback::toggle, Modifier.size(if (compact) 56.dp else 72.dp)) { Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "一時停止" else "再生", Modifier.size(if (compact) 42.dp else 50.dp)) }
        IconButton(vm.playback::next) { Icon(Icons.Rounded.SkipNext, "次の曲", Modifier.size(34.dp)) }
        if (secondary) IconButton(vm.playback::repeat) { Icon(if (playback.repeat == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "リピート切替", Modifier.size(22.dp), tint = if (playback.repeat != 0) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(.5f)) }
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
    LazyColumn(modifier) {
        item { Text("次はこちら", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 16.dp)) }
        itemsIndexed(playback.queue, key = { i, item -> "${item.mediaId}-$i" }) { i, item ->
            Row(Modifier.fillMaxWidth().clickable { vm.playback.skip(i) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (i == playback.index) "♪" else "${i + 1}", color = Rose, modifier = Modifier.width(28.dp))
                TrackText(item.mediaMetadata.title?.toString().orEmpty(), modifier = Modifier.weight(1f))
                DragHandle(i, playback.queue.size, vm.playback::move)
                IconButton({ if (i > 0) vm.playback.move(i, i - 1) }, enabled = i > 0) { Icon(Icons.Rounded.ArrowUpward, "キューを上に移動") }
                IconButton({ vm.playback.remove(i) }) { Icon(Icons.Rounded.Close, "キューから削除") }
            }
        }
    }
}
