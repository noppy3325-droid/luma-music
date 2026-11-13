package jp.noppy.lumamusic.ui

import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.noppy.lumamusic.data.Preferences
import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.playback.PlaybackUi
import jp.noppy.lumamusic.ui.audio.SoundSettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable fun LumaScreen(vm: LumaViewModel, standby: StandbyController) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val users by vm.users.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    // Position is collected only by seek, lyrics, and mini-player composables.
    val playback by vm.playbackSummary.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val selected = remember(tracks, playback.mediaId) { tracks.find { it.uri == playback.mediaId } }
    LaunchedEffect(selected?.uri, selected?.size, selected?.modified, selected?.detailState) { selected?.uri?.let { vm.ensureDetails(it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::addFolder) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val pager = rememberPagerState { LumaTabs.size }
    val sheet = rememberPlayerSheetState()
    val playerVisible = playback.mediaId != null && sheet.active
    val playerCoversBars by remember(sheet) { derivedStateOf { sheet.progress > .92f } }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSound by rememberSaveable { mutableStateOf(false) }
    var showOutput by remember { mutableStateOf(false) }
    var dockHeightPx by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.feedback.collectLatest { snackbar.showSnackbar(it, duration = SnackbarDuration.Short) }
    }
    val tabState = rememberSaveableStateHolder()
    val navigationScope = rememberCoroutineScope()
    var tabChange by remember { mutableStateOf<Job?>(null) }
    val quality = effectiveGlassQuality(prefs.glassQuality, prefs.reducedMotion)
    val reducedMotion = prefs.reducedMotion || quality == GlassQuality.REDUCED
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val canSwipeTabs = !playerVisible && !imeVisible
    val tabFling = PagerDefaults.flingBehavior(pager, snapPositionalThreshold = .32f)
    fun selectTab(index: Int) {
        focus.clearFocus()
        keyboard?.hide()
        tabChange?.cancel()
        tabChange = navigationScope.launch {
            if (reducedMotion) pager.scrollToPage(index)
            else pager.animateScrollToPage(index, animationSpec = spring(dampingRatio = .94f, stiffness = Spring.StiffnessMedium))
        }
    }
    val openSound = { standby.exit(); showSound = true }
    val openPlayer = {
        focus.clearFocus()
        keyboard?.hide()
        sheet.open()
    }
    LumaTheme(prefs.theme) {
        val scheme = MaterialTheme.colorScheme
        val dark = scheme.background == LumaColors.Dark
        val activity = LocalActivity.current
        val view = LocalView.current
        val darkSystemBars = dark || (playerVisible && playerCoversBars && !showSound && !showSettings) || standby.active
        SideEffect {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkSystemBars
                    isAppearanceLightNavigationBars = !darkSystemBars
                }
            }
        }
        CompositionLocalProvider(LocalGlassQuality provides quality, LocalReducedMotion provides reducedMotion, LocalContentColor provides scheme.onBackground) {
            Box(Modifier.fillMaxSize().background(scheme.background)) {
                // Register before child routes so detail/dialog back handlers take precedence.
                BackHandler(showSound || standby.armed || showSettings || playerVisible || pager.settledPage != 0) {
                    when {
                        showSound -> showSound = false
                        standby.armed -> standby.exit()
                        showSettings -> showSettings = false
                        playerVisible -> sheet.close()
                        else -> selectTab(0)
                    }
                }
                when {
                    showSound -> Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) { SoundSettingsScreen(vm) { showSound = false } }
                    standby.active -> LiquidBackdropHost(
                        Modifier.fillMaxSize(),
                        content = { AlbumBackdrop(selected?.artwork ?: playback.artwork, playback.title, reducedMotion, scheme.background) },
                        overlay = { StandbyScreen(vm, playback, selected, prefs, standby, openSound, output.bitPerfectVolumeRestricted) },
                    )
                    showSettings -> Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                        SettingsScreen(vm, prefs, folders, { picker.launch(null) }, { showOutput = true }, openSound, { showSettings = false })
                    }
                    else -> {
                        val bottomContentPadding = with(LocalDensity.current) { dockHeightPx.toDp() } + LumaDock.ListClearance
                        val backgroundSemantics = if (playerVisible) Modifier.semantics { hideFromAccessibility() } else Modifier
                        LiquidBackdropHost(Modifier.fillMaxSize().imePadding().then(backgroundSemantics), content = {
                            Box(Modifier.matchParentSize().background(scheme.background))
                            HorizontalPager(
                                state = pager,
                                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                                userScrollEnabled = canSwipeTabs,
                                flingBehavior = tabFling,
                                key = { LumaTabs[it].key },
                            ) { index ->
                                val tab = LumaTabs[index].key
                                tabState.SaveableStateProvider(tab) {
                                    val active = !playerVisible && pager.settledPage == index
                                    if (tab == "search") {
                                        SearchScreen(
                                            vm, tracks, folders, users, playlists, entries, { picker.launch(null) }, openPlayer,
                                            bottomContentPadding = bottomContentPadding,
                                            active = active,
                                        )
                                    } else {
                                        LibraryScreen(
                                            vm, tracks, folders, users, playlists, entries, { picker.launch(null) }, openPlayer,
                                            bottomContentPadding = bottomContentPadding,
                                            section = tab,
                                            settings = { showSettings = true },
                                            active = active,
                                        )
                                    }
                                }
                            }
                        }, overlay = {
                            val dockInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                                .union(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))
                            Column(
                                Modifier.align(Alignment.BottomCenter)
                                    // Measure the complete dock, including its remaining bottom inset.
                                    // The common parent has already consumed the keyboard inset.
                                    .onSizeChanged { dockHeightPx = it.height }
                                    .windowInsetsPadding(dockInsets)
                                    .padding(
                                        start = LumaDock.HorizontalMargin,
                                        end = LumaDock.HorizontalMargin,
                                        bottom = LumaDock.BottomMargin,
                                    ),
                                verticalArrangement = Arrangement.spacedBy(LumaDock.PanelGap),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (playback.mediaId != null && !imeVisible) MiniPlayerRoute(vm, selected, sheet)
                                LumaGlassTabBar(pager, canSwipeTabs, ::selectTab)
                            }
                        })
                        if (playback.mediaId != null) {
                            tabState.SaveableStateProvider("player-overlay") {
                                PlayerSheet(sheet, vm, playback, selected, users.any { it.uri == playback.mediaId && it.favorite }, standby, { showOutput = true }, openSound, Modifier.matchParentSize())
                            }
                        }
                    }
                }
                val browsing = !showSound && !showSettings && !standby.active && !playerVisible
                val feedbackPosition = Modifier.align(Alignment.BottomCenter).imePadding()
                SnackbarHost(
                    snackbar,
                    if (browsing) feedbackPosition.padding(bottom = with(LocalDensity.current) { dockHeightPx.toDp() })
                    else feedbackPosition.navigationBarsPadding().padding(bottom = 12.dp),
                )
                if (showOutput) OutputPanel(output, selected, playback.playing) { showOutput = false }
                val error = message ?: playback.error ?: scan.message.takeIf { !scan.running }
                if (error != null) {
                    var dismissed by remember(error) { mutableStateOf(false) }
                    if (!dismissed) AlertDialog(onDismissRequest = { dismissed = true; vm.dismissMessage() }, title = { Text("確認してください") }, text = { Text(error, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton({ dismissed = true; vm.dismissMessage() }) { Text("閉じる") } })
                }
            }
        }
    }
}

@Composable private fun MiniPlayerRoute(vm: LumaViewModel, track: Track?, sheet: PlayerSheetState) {
    val playback by vm.playback.state.collectAsStateWithLifecycle()
    val currentTrack = track?.takeIf { it.uri == playback.mediaId }
    val display = playback.copy(
        title = currentTrack?.title ?: playback.title,
        artist = currentTrack?.artist ?: playback.artist,
        artwork = playback.artwork ?: currentTrack?.artwork ?: currentTrack?.artworkThumbnail,
    )
    val scope = rememberCoroutineScope()
    val motion = remember(scope) { MiniPlayerMotion(scope) }
    val reduced = LocalReducedMotion.current
    val currentDisplay = rememberUpdatedState(display)
    val currentReduced = rememberUpdatedState(reduced)
    LaunchedEffect(display.index, display.mediaId) { motion.observe(display) }
    LumaGlassMiniPlayer(
        display,
        modifier = Modifier.onGloballyPositioned { sheet.miniBounds = it.boundsInRoot() }
            .graphicsLayer { alpha = if (sheet.active) 0f else 1f },
        trackGesture = Modifier.miniPlayerDrag(sheet, motion, currentDisplay, currentReduced, vm.playback),
        open = sheet::open,
        toggle = vm.playback::toggle,
        next = { vm.playback.next() },
        previous = { vm.playback.previousItem() },
    ) { MiniPlayerTracks(display, motion, reduced) }
}

@Composable private fun StandbyScreen(vm: LumaViewModel, playback: PlaybackUi, track: Track?, prefs: Preferences, standby: StandbyController, sound: () -> Unit, bitPerfect: Boolean) {
    val activity = LocalActivity.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var dimmed by remember { mutableStateOf(false) }
    var minute by remember { mutableStateOf(0) }
    var time by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(prefs.dimAfter) { dimmed = false; if (prefs.dimAfter) { delay(30000); dimmed = true } }
    LaunchedEffect(Unit) { while (true) { time = LocalTime.now(); delay(60000); minute++ } }
    DisposableEffect(lifecycle) { val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }; lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) } }
    DisposableEffect(resumed, prefs.keepAwake, prefs.brightness, dimmed) {
        val window = activity.window
        val oldBrightness = window.attributes.screenBrightness
        if (resumed) {
            if (prefs.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = if (dimmed) .02f else prefs.brightness }
        }
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); window.attributes = window.attributes.apply { screenBrightness = oldBrightness } }
    }
    Row(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).offset(x = (minute % 3 - 1).dp, y = ((minute / 3) % 3 - 1).dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Artwork(track?.artwork ?: playback.artwork, track?.title ?: playback.title, Modifier.fillMaxHeight(.92f).aspectRatio(1f), 14) }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                if (prefs.clock) Text(time.format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                IconButton(sound) { Icon(Icons.Rounded.Equalizer, "サウンド設定") }; IconButton({ dimmed = !dimmed }) { Icon(Icons.Rounded.Brightness4, "明るさ切替") }
                IconButton(standby::exit) { Icon(Icons.Rounded.Close, "スタンバイを終了") }
            }
            TrackText(track?.title ?: playback.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            TrackText(track?.artist ?: playback.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            FloatingGlass(Modifier.fillMaxWidth(), 24, PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                SeekControls(vm, playback, compact = true)
                PlaybackButtons(vm, playback, compact = true)
                VolumeControl(bitPerfect = bitPerfect)
            }
            if (prefs.standbyLyrics) StandbyLyric(vm, track)
        }
    }
    if (prefs.darkStandby || dimmed) Box(Modifier.fillMaxSize().background(Color.Black.copy(if (dimmed) .5f else .12f)))
}

@Composable private fun StandbyLyric(vm: LumaViewModel, track: Track?) {
    val live by vm.playback.state.collectAsStateWithLifecycle()
    val position = live.position
    var lyrics by remember(track?.uri) { mutableStateOf("") }
    LaunchedEffect(track?.uri) { if (track != null) lyrics = runCatching { vm.library.lyrics(track) }.getOrDefault("") }
    val lines = remember(lyrics) { jp.noppy.lumamusic.core.parseLrc(lyrics) }
    val line = lines.lastOrNull { (it.timeMs ?: Long.MAX_VALUE) <= position } ?: lines.firstOrNull()
    Text(line?.text.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable internal fun VolumeControl(bitPerfect: Boolean = false) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    val fixed = audio.isVolumeFixed
    if (bitPerfect || fixed) {
        val label = if (bitPerfect) "USBビットパーフェクト設定中 · 音量は出力機器で調整" else "この出力の音量は出力機器で調整"
        Text(label, Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.labelSmall)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.VolumeDown, null)
            LumaSlider(volume, { volume = it; audio.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0) },
                range = 0f..max.toFloat().coerceAtLeast(1f), modifier = Modifier.weight(1f), label = "音量")
            Icon(Icons.Rounded.VolumeUp, "音量")
        }
    }
}
