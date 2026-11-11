package jp.noppy.lumamusic.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.noppy.lumamusic.audio.*
import jp.noppy.lumamusic.core.OutputMode
import jp.noppy.lumamusic.data.*

@Composable fun SettingsScreen(vm: LumaViewModel, prefs: Preferences, folders: List<MusicFolder>, addFolder: () -> Unit, output: () -> Unit, sound: () -> Unit = {}, close: () -> Unit = {}) {
    val context = LocalContext.current
    var warning by remember { mutableStateOf(false) }
    val version = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrDefault("0.20") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(close) { Icon(Icons.Rounded.ArrowBack, "ライブラリに戻る") }; Text("設定", style = MaterialTheme.typography.headlineLarge) }
        SettingsGroup { Text("サウンド", style = MaterialTheme.typography.titleLarge); TextButton(sound) { Icon(Icons.Rounded.Equalizer, null); Spacer(Modifier.width(8.dp)); Text("Aurio Enhance / Direct") }; Text("音質補正、イコライザー、音量の均一化を調整します。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        SettingsGroup { Text("音声出力", style = MaterialTheme.typography.titleLarge); TextButton(output) { Icon(Icons.Rounded.SpeakerGroup, null); Spacer(Modifier.width(8.dp)); Text("出力情報を確認") }
            listOf(OutputMode.STANDARD to "通常再生", OutputMode.USB_HIGH_QUALITY to "USBの形式一致を優先", OutputMode.BIT_PERFECT to "ビットパーフェクトを試行").forEach { (mode, title) ->
                Row(Modifier.fillMaxWidth().clickable { if (mode == OutputMode.BIT_PERFECT) warning = true else vm.mode(mode) }, verticalAlignment = Alignment.CenterVertically) { RadioButton(prefs.mode == mode, { if (mode == OutputMode.BIT_PERFECT) warning = true else vm.mode(mode) }); Text(title) }
            }
            Text("対応する形式がない場合や設定を適用できない場合は、Android標準再生を使用します。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsGroup { Text("音楽フォルダー", style = MaterialTheme.typography.titleLarge)
            folders.forEach { folder -> Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(folder.name); folder.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }; IconButton({ vm.removeFolder(folder) }) { Icon(Icons.Rounded.RemoveCircleOutline, "フォルダーの登録を解除") } } }
            Row { TextButton(addFolder) { Text("追加") }; TextButton({ vm.rescan() }) { Text("再スキャン") } }
        }
        SettingsGroup { Text("デザイン", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("system" to "自動", "dark" to "ダーク", "light" to "ライト").forEach { (key, label) -> FilterChip(prefs.theme == key, { vm.action { vm.settings.text("theme", key) } }, label = { Text(label) }, border = null, shape = LumaShapes.Pill, modifier = Modifier.padding(end = 6.dp)) } }
            Text("ガラスの描画品質", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState())) { GlassQuality.entries.forEach { quality -> FilterChip(prefs.glassQuality == quality.name, { vm.action { vm.settings.text("glassQuality", quality.name) } }, label = { Text(quality.label) }, border = null, shape = LumaShapes.Pill, modifier = Modifier.padding(end = 6.dp)) } }
            Text("高品質は屈折を強め、軽量はぼかしだけを使用します。オフは通常パネルです。省電力中やアニメーション軽減時は負荷を抑えます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (android.os.Build.VERSION.SDK_INT < 31) Text("この端末では通常パネルを使用します。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (android.os.Build.VERSION.SDK_INT < 33) Text("この端末では屈折を省き、ぼかしで表示します。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (LocalGlassQuality.current.name != prefs.glassQuality) Text("現在の描画: ${LocalGlassQuality.current.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToggleRow("アニメーションを軽減", prefs.reducedMotion) { vm.action { vm.settings.boolean("motion", it) } }
        }
        SettingsGroup { Text("スタンバイ", style = MaterialTheme.typography.titleLarge)
            ToggleRow("画面を点灯したままにする", prefs.keepAwake) { vm.action { vm.settings.boolean("awake", it) } }
            ToggleRow("30秒後に暗くする", prefs.dimAfter) { vm.action { vm.settings.boolean("dim", it) } }
            ToggleRow("暗所向け表示", prefs.darkStandby) { vm.action { vm.settings.boolean("night", it) } }
            ToggleRow("時計を表示", prefs.clock) { vm.action { vm.settings.boolean("clock", it) } }
            ToggleRow("歌詞を表示", prefs.standbyLyrics) { vm.action { vm.settings.boolean("standbyLyrics", it) } }
            Text("スタンバイ画面の明るさ"); LumaSlider(prefs.brightness, { vm.action { vm.settings.number("brightness", it) } }, range = .02f..1f, label = "スタンバイ画面の明るさ")
            Text("前面表示中だけ有効です。低輝度と微小な位置移動で焼き付きを軽減します。", style = MaterialTheme.typography.bodySmall)
        }
        SettingsGroup { Text("システムとの連携", style = MaterialTheme.typography.titleLarge)
            Text("MediaSessionによる通知・ロック画面・イヤホン操作に対応。HyperIslandの表示は端末・地域・OSに依存します。実表示の確認前は未検証として扱います。", style = MaterialTheme.typography.bodySmall)
            TextButton({ context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("バックグラウンド再生の端末設定") }
            Text("再生が止まる場合は、HyperOSのアプリ設定で電池最適化・バックグラウンド制限を確認してください。", style = MaterialTheme.typography.bodySmall)
        }
        Text("Aurio $version\nローカル音楽を、あなたの手元に。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (warning) AlertDialog(onDismissRequest = { warning = false }, title = { Text("音量を確認してください") }, text = { Text("対応端末でのみBIT_PERFECT設定を試します。ソフトウェア音量や音声加工が適用されない場合があります。イヤホン・DAC側の音量を確認してください。実出力を検証できない場合は『未確認』と表示します。") }, confirmButton = { TextButton({ vm.mode(OutputMode.BIT_PERFECT); warning = false }) { Text("確認して試行") } }, dismissButton = { TextButton({ warning = false }) { Text("キャンセル") } })
}

@Composable private fun ToggleRow(label: String, selected: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(selected, onChange) } }

@Composable fun OutputPanel(output: OutputState, track: Track?, playing: Boolean, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("音声出力情報") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutputField(if (playing) "出力経路" else "直近に確認した経路（停止中）", output.actual?.let { "${it.type} / ${it.name}" } ?: "未確認（停止中または経路確定前）")
            OutputField("ソース", track?.let { "${it.mime}\n${rateText(it.sampleRate)} / ${it.bitDepth?.let { b -> "$b bit" } ?: "ビット深度未確認"}" } ?: "未選択")
            OutputField("AudioTrack設定", output.track?.let { "${rateText(it.rate)} / ${it.channels} ch / ${encodingName(it.encoding)}" } ?: "未確認")
            OutputField("実出力サンプルレート", "未確認（AudioTrack設定はDACの測定値ではありません）")
            OutputField("再生方式", output.method)
            OutputField("ビットパーフェクト対応状況", output.bitPerfect)
            output.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Rose) }
            HorizontalDivider(); Text("接続機器が報告する仕様", fontWeight = FontWeight.Bold)
            output.connected.forEach { device ->
                Text("${device.type} / ${device.name}", fontWeight = FontWeight.SemiBold)
                Text("対応サンプルレート: ${device.rates.takeIf { it.isNotEmpty() }?.joinToString { rateText(it) } ?: "未列挙（具体値未確認）"}\nチャンネル数: ${device.channels.takeIf { it.isNotEmpty() }?.joinToString() ?: "未列挙"}\nエンコーディング: ${device.encodings.takeIf { it.isNotEmpty() }?.joinToString { encodingName(it) } ?: "未取得"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClose) { Text("閉じる") } })
}
@Composable private fun OutputField(label: String, value: String) { Column { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyMedium) } }
