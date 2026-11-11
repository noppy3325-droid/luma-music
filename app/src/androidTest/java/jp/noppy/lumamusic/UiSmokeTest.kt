package jp.noppy.lumamusic

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.noppy.lumamusic.core.dsp.DspConfig
import jp.noppy.lumamusic.core.dsp.SoundMode
import jp.noppy.lumamusic.data.Track
import jp.noppy.lumamusic.data.TrackFolder
import jp.noppy.lumamusic.playback.PlaybackService
import jp.noppy.lumamusic.playback.mediaItem
import jp.noppy.lumamusic.ui.StandbyController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real app routes through its accessibility actions, without shell input injection.
 * Fixtures are private silent PCM/generated artwork. Existing music and settings survive cleanup.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class UiSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as LumaApp
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun eventually(description: String, seconds: Long = 15, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(150)
        }
        fail("Timed out: $description; visible=${nodes().mapNotNull { it.text }.joinToString()}")
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        // refresh() updates one node, but a stale accessibility cache can retain removed
        // semantics IDs after tab/IME transitions. Clear the cache before walking the tree.
        if (android.os.Build.VERSION.SDK_INT >= 36) instrumentation.uiAutomation.clearCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            node.refresh()
            result.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
        }
        return result
    }

    private fun textPresent(text: String) = nodes().any { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun click(label: String, tab: Boolean = false) {
        var loggedFailure = false
        eventually("clickable $label") {
            val matches = nodes().filter { it.text?.toString() == label || it.contentDescription?.toString() == label }
                .let { candidates ->
                    if (!tab) candidates else listOfNotNull(candidates.maxByOrNull {
                        val bounds = android.graphics.Rect()
                        it.getBoundsInScreen(bounds)
                        bounds.centerY()
                    })
                }
            val clicked = matches.any { leaf ->
                var current: AccessibilityNodeInfo? = leaf
                var depth = 0
                while (current != null && depth++ < 8) {
                    if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@any true
                    current = current.parent
                }
                false
            }
            if (!clicked && !loggedFailure) {
                loggedFailure = true
                android.util.Log.w("LumaUiSmoke", "CLICK FAILED $label: " + matches.joinToString { leaf ->
                    val chain = mutableListOf<String>()
                    var node: AccessibilityNodeInfo? = leaf
                    repeat(8) { node?.let { value ->
                        val bounds = android.graphics.Rect(); value.getBoundsInScreen(bounds)
                        chain += "${value.className} text=${value.text} desc=${value.contentDescription} enabled=${value.isEnabled} clickable=${value.isClickable} visible=${value.isVisibleToUser} actions=${value.actionList} bounds=$bounds"
                        node = value.parent
                    } }
                    chain.joinToString(" -> ")
                })
            }
            clicked
        }
        Thread.sleep(850)
    }

    private fun verticalScroller(): AccessibilityNodeInfo? = nodes().filter { node ->
        node.isScrollable && node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id || it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id }
    }.maxByOrNull {
        val bounds = android.graphics.Rect()
        it.getBoundsInScreen(bounds)
        bounds.height()
    }

    private fun screenshot(name: String) {
        Thread.sleep(400)
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable: $name")
        val directory = File(context.getExternalFilesDir(null), "ui-smoke").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun frameStats(reset: Boolean = false) {
        val command = "dumpsys gfxinfo jp.noppy.lumamusic" + if (reset) " reset" else ""
        val stats = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
        if (!reset) File(context.getExternalFilesDir(null), "v51-gesture-frame-stats.txt").writeText(stats)
    }

    private fun swipe(
        activity: Activity,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 300,
        frame: String? = null,
    ) {
        val view = activity.window.decorView
        val down = SystemClock.uptimeMillis()
        fun dispatch(action: Int, fraction: Float) {
            val x = (fromX + (toX - fromX) * fraction) * view.width
            val y = (fromY + (toY - fromY) * fraction) * view.height
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { main { activity.dispatchTouchEvent(event) } } finally { event.recycle() }
        }
        dispatch(MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..10) {
            Thread.sleep(durationMs / 10)
            dispatch(MotionEvent.ACTION_MOVE, step / 10f)
            if (step == 5 && frame != null) screenshot(frame)
        }
        dispatch(MotionEvent.ACTION_UP, 1f)
    }

    private fun tabSelected(label: String): Boolean = nodes().any { node ->
        if (node.text?.toString() != label && node.contentDescription?.toString() != label) return@any false
        generateSequence(node) { it.parent }.take(4).any { it.isSelected }
    }

    private fun fixture(directory: File): Pair<File, List<Track>> {
        val rate = 44100
        val pcmBytes = rate * 20 * 4
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcmBytes).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(pcmBytes)
        val wave = File(directory, "silent-20s.wav").apply { outputStream().use { it.write(header.array()); it.write(ByteArray(pcmBytes)) } }
        val palette = listOf(0xFF405A73.toInt(), 0xFF89635A.toInt(), 0xFF41605B.toInt(), 0xFF898274.toInt(), 0xFF735779.toInt(), 0xFFAC7B5E.toInt())
        val names = listOf("Blue Hour", "Evening Light", "静かな海", "Passing Days", "月のあいだ", "First Light")
        val art = palette.mapIndexed { index, color ->
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, 512f, 512f, color, Color.rgb(18, 24, 32), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 512f, 512f, paint)
            paint.shader = null
            paint.color = Color.argb(44, 255, 255, 255)
            canvas.drawCircle(380f, 150f, 190f, paint)
            paint.color = Color.argb(32, 255, 255, 255)
            canvas.drawCircle(100f, 390f, 180f, paint)
            paint.color = Color.WHITE
            paint.textSize = 36f
            paint.typeface = android.graphics.Typeface.create(context.resources.getFont(jp.noppy.lumamusic.R.font.noto_sans_jp_regular), 400, false)
            canvas.drawText(names[index], 40f, 400f, paint)
            paint.textSize = 16f
            canvas.drawText("Aurio STUDIO  /  GENERATED ARTWORK", 40f, 444f, paint)
            val file = File(directory, "art-$index.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            Uri.fromFile(file).toString()
        }
        val start = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)
        // Private hard links give each row a normal file URI while sharing the silent PCM data.
        val tracks = (0 until 48).map { index ->
            val song = File(directory, "silent-$index.wav")
            runCatching { android.system.Os.link(wave.absolutePath, song.absolutePath) }.getOrElse { wave.copyTo(song) }
            val uri = Uri.fromFile(song).toString()
            val title = if (index == 47) "星をつなぐ 48 — 明け方の青い空が続く場所で / Long Session" else "星をつなぐ ${index + 1}"
            val artist = if (index == 47) "Aurio Studio feat. 月のあいだ・静かな海" else "Aurio Studio"
            Track(uri = uri, title = title, artist = artist, album = names[index % 6], albumArtist = "Aurio Studio",
                duration = 20000, mime = "audio/wav", sampleRate = rate, bitDepth = 16, channels = 2, artwork = art[index % 6], artworkThumbnail = art[index % 6],
                embeddedLyrics = "[00:00.00]静かな夜に\n[00:05.00]星をつないで\n[00:10.00]明日へ歩く", modified = wave.lastModified(), added = start + index,
                size = wave.length(), metadataState = "READY", detailState = "READY")
        }
        return wave to tracks
    }

    @Test fun routesSeekThemesAndStandbyPreserveExistingData() = runBlocking {
        val originalPrefs = app.settings.preferences.first()
        val originalSound = app.settings.soundSettings.first()
        val originalSnapshot = app.settings.snapshot.first()
        val fixtureId = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "ui-fixture-$fixtureId").apply { mkdirs() }
        val fixtureFolder = "luma-ui-fixture:$fixtureId"
        val (_, tracks) = fixture(directory)
        val top = tracks.last()
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        var controller: MediaController? = null
        var activity: Activity? = null
        var queue = emptyList<MediaItem>()
        var index = 0
        var position = 0L
        var playing = false
        var repeat = Player.REPEAT_MODE_OFF
        var shuffle = false
        var orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var queueCaptured = false
        try {
            main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
            controller = future!!.get(15, TimeUnit.SECONDS)
            val player = controller!!
            main {
                queue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
                index = player.currentMediaItemIndex.coerceAtLeast(0)
                position = player.currentPosition.coerceAtLeast(0)
                playing = player.isPlaying; repeat = player.repeatMode; shuffle = player.shuffleModeEnabled
                queueCaptured = true
                player.pause()
            }
            app.settings.text("theme", "light")
            app.settings.text("glassQuality", "BALANCED")
            app.settings.boolean("motion", false)
            app.settings.boolean("dim", false)
            app.settings.boolean("night", false)
            app.settings.boolean("awake", false)
            app.settings.saveSound(DspConfig())
            app.database.withTransaction {
                app.library.dao.upsertTracks(tracks)
                // No folder entity: automatic SAF rescans never try to enumerate this test-only reference.
                app.library.dao.refs(tracks.map { TrackFolder(it.uri, fixtureFolder) })
            }
            val fixtureQueue = tracks.takeLast(3).map(Track::mediaItem)
            main {
                player.setMediaItems(fixtureQueue, fixtureQueue.lastIndex, 0)
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.prepare()
                player.play()
            }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            orientation = activity!!.requestedOrientation
            eventually("fixture library") {
                if (textPresent("確認してください")) runCatching { click("閉じる") }
                textPresent(top.title)
            }
            screenshot("01-library-light")
            assertTrue("library scroll dispatches", verticalScroller()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true)
            Thread.sleep(800)
            screenshot("02-library-scroll-glass")

            frameStats(reset = true)
            swipe(activity, .82f, .51f, .17f, .51f, frame = "15-tab-drag")
            eventually("search tab settles after horizontal swipe") { tabSelected("検索") }
            eventually("search input") { nodes().any { it.isEditable } }
            val search = nodes().first { it.isEditable }
            assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "星をつなぐ") }))
            Thread.sleep(500)
            screenshot("03-search")
            click("プレイリスト", tab = true)
            eventually("playlist route and selected tab") { textPresent("プレイリストを作成") && tabSelected("プレイリスト") }
            click("ライブラリ", tab = true)
            // The mini-player repeats the title even when its list row is off-screen.
            for(page in 0 until 8) { if (verticalScroller()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true) break; Thread.sleep(800) }
            eventually("recent fixtures") { textPresent(top.title) }
            // An incomplete, slow upward drag should return to the mini-player.
            val window = activity.window.decorView
            val miniTitle = nodes().filter { it.text?.toString() == top.title }.maxByOrNull {
                val bounds = android.graphics.Rect()
                it.getBoundsInScreen(bounds)
                bounds.centerY()
            } ?: error("Mini-player title not visible")
            val miniBounds = android.graphics.Rect()
            miniTitle.getBoundsInScreen(miniBounds)
            val miniX = miniBounds.centerX().toFloat() / window.width
            val miniY = miniBounds.centerY().toFloat() / window.height
            swipe(activity, miniX, miniY, miniX, miniY - .09f, 650, "16-player-drag-cancel")
            Thread.sleep(700)
            assertFalse("short drag restores collapsed mini-player", textPresent("プレイヤーを閉じる"))
            swipe(activity, miniX, miniY, miniX, miniY - .48f, 450, "17-player-drag-open")
            eventually("player route") { textPresent("プレイヤーを閉じる") }
            eventually("fixture plays and advances") { var result = false; main { result = player.currentMediaItem?.mediaId == top.uri && player.isPlaying && player.currentPosition > 100 }; result }
            main { assertEquals(fixtureQueue.map { it.mediaId }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }) }
            main { player.repeatMode = Player.REPEAT_MODE_ONE; player.pause() }
            eventually("seek semantic") { textPresent("再生位置") }
            val seek = nodes().first { it.contentDescription?.toString() == "再生位置" }
            assertTrue("accessible seek dispatches", seek.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 5000f) }))
            eventually("seek changes actual player") { var result = false; main { result = player.currentPosition in 4500L..6000L }; result }
            screenshot("04-player-light")
            click("キュー")
            eventually("queue sheet") { textPresent("次はこちら") }
            screenshot("05-queue-glass")
            click("キュー")
            click("歌詞")
            eventually("lyrics panel") { textPresent("歌詞を編集") }
            screenshot("06-lyrics")
            click("歌詞")
            click("サウンド")
            eventually("sound route") { textPresent("Aurio Enhance") }
            click("Aurio Enhance")
            eventually("Enhance saves") { runBlocking { app.settings.soundSettings.first().mode == SoundMode.ENHANCE } }
            app.settings.saveSound(DspConfig(mode = SoundMode.ENHANCE, bands = listOf(-1f, 0f, 0f, .5f, 1f, 1.5f, 1f, .5f, 0f, -1f), preset = "カスタム"))
            main { player.play() }
            Thread.sleep(1000)
            screenshot("07-sound-status")
            val movedToEq=verticalScroller()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id, Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, 4); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0) }) == true
            if(!movedToEq)verticalScroller()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(700)
            screenshot("08-equalizer")
            repeat(6) { if (!textPresent("閉じる")) { verticalScroller()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); Thread.sleep(300) } }
            click("閉じる")
            eventually("player returns") { textPresent("プレイヤーを閉じる") }
            swipe(activity, .5f, .22f, .5f, .70f, 450, "18-player-drag-close")
            eventually("player collapses to the selected tab") { !textPresent("プレイヤーを閉じる") && tabSelected("ライブラリ") }
            main {
                assertTrue("Playback continues as the sheet closes", player.isPlaying)
                assertEquals(top.uri, player.currentMediaItem?.mediaId)
                assertEquals(fixtureQueue.map { it.mediaId }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            }
            frameStats()
            click("設定")
            eventually("settings route") { textPresent("ガラスの描画品質") || textPresent("音楽フォルダー") }
            screenshot("09-settings-light")
            app.settings.text("theme", "dark")
            Thread.sleep(1000)
            screenshot("10-settings-dark")
            click("ライブラリに戻る")
            screenshot("11-library-dark")
            click(top.title)
            screenshot("12-player-dark")
            main { activity!!.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            eventually("landscape") { context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            Thread.sleep(700)
            screenshot("13-player-landscape")
            // Exercise the app's orientation callback, without changing global rotation/security settings.
            main {
                val field = MainActivity::class.java.getDeclaredField("standby").apply { isAccessible = true }
                val standby = field.get(activity) as StandbyController
                if (!standby.armed) standby.arm()
                val sensorField = StandbyController::class.java.getDeclaredField("sensor").apply { isAccessible = true }
                val sensor = sensorField.get(standby) as OrientationEventListener
                sensor.onOrientationChanged(270)
                sensor.disable() // Deterministic callback test; do not let the phone's real desk angle exit it.
            }
            eventually("standby route") { textPresent("スタンバイを終了") }
            screenshot("14-standby-landscape")
            click("スタンバイを終了")
            main { assertNull(player.playerError) }
        } finally {
            if (queueCaptured) controller?.let { player -> main { player.pause() } }
            app.settings.saveSound(originalSound)
            Thread.sleep(200)
            if (queueCaptured) controller?.let { player -> main {
                player.pause()
                if (queue.isEmpty()) player.clearMediaItems() else { player.setMediaItems(queue, index.coerceIn(queue.indices), position); player.prepare() }
                player.repeatMode = repeat; player.shuffleModeEnabled = shuffle
                if (playing && queue.isNotEmpty()) player.play()
            } }
            app.settings.text("theme", originalPrefs.theme)
            app.settings.text("glassQuality", originalPrefs.glassQuality)
            app.settings.boolean("motion", originalPrefs.reducedMotion)
            app.settings.boolean("dim", originalPrefs.dimAfter)
            app.settings.boolean("night", originalPrefs.darkStandby)
            app.settings.boolean("awake", originalPrefs.keepAwake)
            activity?.let { a -> main { a.requestedOrientation = orientation; a.finish() } }
            app.database.withTransaction {
                app.library.dao.clearRefs(fixtureFolder)
                tracks.forEach { track ->
                    app.database.openHelper.writableDatabase.execSQL("DELETE FROM tracks WHERE uri=?", arrayOf(track.uri))
                    app.database.openHelper.writableDatabase.execSQL("DELETE FROM audio_analysis WHERE uri=?", arrayOf(track.uri))
                }
            }
            Thread.sleep(350)
            app.settings.saveSnapshot(originalSnapshot)
            future?.let { saved -> main { MediaController.releaseFuture(saved) } }
            // Exact UUID-owned private directory; never touch registered music folders.
            assertTrue(directory.name == "ui-fixture-$fixtureId" && directory.parentFile == context.filesDir)
            directory.deleteRecursively()
        }
    }
}
