package jp.noppy.lumamusic.ui.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import jp.noppy.lumamusic.LumaApp
import jp.noppy.lumamusic.audio.*
import jp.noppy.lumamusic.core.dsp.*
import jp.noppy.lumamusic.data.DspPreset
import jp.noppy.lumamusic.ui.LumaViewModel
import kotlin.math.*

@Composable
fun SoundSettingsScreen(vm:LumaViewModel,onClose:()->Unit) {
    val app=LocalContext.current.applicationContext as LumaApp
    val saved by vm.settings.soundSettings.collectAsState(DspConfig())
    var config by remember { mutableStateOf(saved) }
    LaunchedEffect(saved){config=saved}
    val status by DspRuntime.state.collectAsState()
    val output by vm.output.collectAsState()
    val playback by vm.playback.state.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val presets by vm.library.dao.presets().collectAsState(emptyList())
    val repository=remember(app){AnalysisRepository(app,vm.library.dao)}
    var presetName by remember{mutableStateOf("")}
    var analyzing by remember{mutableStateOf(false)}
    val active=config.mode==SoundMode.ENHANCE
    val track=tracks.firstOrNull{it.uri==playback.mediaId}
    fun save(next:DspConfig){config=next.safe();vm.action{vm.settings.saveSound(next)}}
    val line=MaterialTheme.colorScheme.primary
    val grid=MaterialTheme.colorScheme.outlineVariant
    val profile=DspRuntime.snapshot.get().analysis
    val curveRate=status.sampleRate?:48000
    val curveMaxFrequency=min(20000.0,curveRate*.47)
    val curve=remember(config,status.sampleRate,profile){val engine=PcmDspEngine(curveRate,2);engine.configure(config.copy(mode=SoundMode.ENHANCE),profile)
        List(160){i->val f=20.0*(curveMaxFrequency/20).pow(i/159.0);engine.responseDb(f).coerceIn(-24.0,24.0).toFloat()}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=16.dp,bottom=140.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item{Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onClose){Icon(Icons.AutoMirrored.Filled.ArrowBack,"閉じる")};Text("サウンド",style=MaterialTheme.typography.headlineMedium)}}
        item{Text("出力先: ${output.actual?.let{"${it.name} (${it.type})"}?:"実出力は未確認"}",style=MaterialTheme.typography.bodyMedium)}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=!active,onClick={save(config.copy(mode=SoundMode.DIRECT))},label={Text("Aurio Direct")});FilterChip(selected=active,onClick={save(config.copy(mode=SoundMode.ENHANCE))},label={Text("Aurio Enhance")})}
            Text(if(!active)"アプリ内音声補正 OFF。Android・機器内部の無加工やビットパーフェクトを保証する名称ではありません。"else "デコード済みPCMを処理します。安全なヘッドルームにより音量が下がる場合があります。",style=MaterialTheme.typography.bodySmall)}
        item{SoundCard("音質の状態"){
            Text(when{status.error!=null->status.error!!;status.sampleRate==null->"PCM入力待ち（再生すると状態を確認できます）";!active&&status.applied->"Directへ切替中 / バイパス確認待ち";!active->"Aurio Direct / アプリ内補正 OFF";!status.applied->"Enhance 適用待ち";else->"Aurio Enhance / PCM処理中"})
            Text("DSP入力: ${status.sampleRate?.let{"${it/1000.0} kHz"}?:"不明"} / ${status.channels?.let{"${it}ch"}?:"不明"} / PCM 16bit",style=MaterialTheme.typography.bodySmall)
            if(active){Text("選択: ${listOfNotNull(if(config.eqEnabled)"EQ ${config.preset}"else null,if(config.autoEnabled)"Auto"else null,if(config.normalizeEnabled)"音量均一化"else null,if(config.widthEnabled)"ステレオ幅"else null).joinToString(" + ").ifEmpty{"フラット"}}")
                Text("安全ゲイン: %.1f dB / サンプルピーク飽和保護（True Peak保証なし）".format(status.gainDb),style=MaterialTheme.typography.bodySmall)}
            Text("ビットパーフェクト: ${if(active||status.applied)"対象外（DSP適用・切替中）"else output.bitPerfect}",style=MaterialTheme.typography.bodySmall)
            Text("USB出力周波数はDSP入力とは別です。OS/HALの実クロックは未確認。24bit/float音源はMedia3で16bitへ変換されます。",style=MaterialTheme.typography.bodySmall)
        }}
        item{SoundCard("10バンド EQ"){
            SoundSwitch("EQ",config.eqEnabled,active){save(config.copy(eqEnabled=it))}
            val upperFrequency=if(curveMaxFrequency==20000.0)"20 kHz"else "%.2f kHz".format(curveMaxFrequency/1000)
            Text("実フィルターの合成特性（20 Hz〜$upperFrequency / ±24 dB）",style=MaterialTheme.typography.labelSmall)
            if(status.sampleRate==null)Text("入力周波数は未確認。48 kHz入力を基準にした参考表示です。",style=MaterialTheme.typography.labelSmall)
            Canvas(Modifier.fillMaxWidth().height(120.dp).semantics{contentDescription="実際に使用するEQフィルターの周波数特性"}){for(y in listOf(0f,.5f,1f))drawLine(grid,androidx.compose.ui.geometry.Offset(0f,size.height*y),androidx.compose.ui.geometry.Offset(size.width,size.height*y));val path=Path();curve.forEachIndexed{i,v->val x=size.width*i/(curve.size-1);val y=size.height*(.5f-v/48);if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};drawPath(path,line,style=androidx.compose.ui.graphics.drawscope.Stroke(3f))}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TextButton(enabled=active,onClick={save(config.copy(bands=List(10){0f},preampDb=0f,preset="フラット"))}){Text("EQリセット")};Text("Auto・音量均一化は別設定",style=MaterialTheme.typography.labelSmall,modifier=Modifier.align(Alignment.CenterVertically))}
        }}
        items(EQ_FREQUENCIES.indices.toList(),key={"eq$it"}){i->SoundCard(null){val f=EQ_FREQUENCIES[i];val supported=f<(status.sampleRate?:48000)*.48
            SoundSlider(if(f<1000)"${f.toInt()} Hz"else "${(f/1000).toInt()} kHz",config.bands[i],-12f..12f,active&&config.eqEnabled&&supported,"%.1f dB".format(config.bands[i])){v->save(config.copy(bands=config.bands.toMutableList().also{it[i]=v},preset="カスタム"))}
            if(!supported)Text("この入力周波数では帯域をバイパスします",style=MaterialTheme.typography.labelSmall)
        }}
        item{SoundCard("プリセット"){
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){FACTORY_PRESETS.forEach{(name,bands)->FilterChip(selected=config.preset==name,onClick={save(config.copy(bands=bands,preampDb=0f,preset=name))},enabled=active,label={Text(name)})}}
            presets.forEach{p->Row(verticalAlignment=Alignment.CenterVertically){TextButton(enabled=active,onClick={save(config.copy(bands=p.bands.split(',').map{it.toFloatOrNull()?:0f},preampDb=p.preamp,preset=p.name))},modifier=Modifier.weight(1f)){Text(p.name)};TextButton(enabled=active,onClick={vm.action{vm.library.dao.deletePreset(p.name)}}){Text("削除")}}}
            OutlinedTextField(value=presetName,onValueChange={presetName=it.take(40)},label={Text("カスタム名（同名で更新）")},singleLine=true,enabled=active,modifier=Modifier.fillMaxWidth())
            Button(enabled=active&&presetName.isNotBlank()&&presetName.trim() !in FACTORY_PRESETS,onClick={val name=presetName.trim();vm.action{vm.library.dao.upsertPreset(DspPreset(name,config.bands.joinToString(","),config.preampDb))};save(config.copy(preset=name))}){Text("保存 / 更新")}
            SoundSlider("プリアンプ",config.preampDb,-12f..6f,active,"%.1f dB".format(config.preampDb)){save(config.copy(preampDb=it))}
            Text("正のゲインはピーク余裕が確認できる範囲に制限。EQブーストと幅の増幅分は先に減衰します。",style=MaterialTheme.typography.bodySmall)
        }}
        item{SoundCard("Aurio Auto"){
            SoundSwitch("自動音質調整",config.autoEnabled,active){save(config.copy(autoEnabled=it))}
            Text("端末内の実解析から低域・高域の過多を最大2 dB抑制。手動EQの非ゼロ帯域を優先します。AI復元ではありません。",style=MaterialTheme.typography.bodySmall)
            Text(status.analysis,style=MaterialTheme.typography.bodyMedium)
            Button(enabled=active&&track!=null&&!analyzing,onClick={val t=track?:return@Button;analyzing=true;vm.action{try{repository.analyze(t)}finally{analyzing=false}}}){Text(if(analyzing)"解析中…"else "現在曲を解析 / キャッシュ利用")}
            if(track!=null)TextButton(enabled=active&&!analyzing,onClick={val t=track;analyzing=true;vm.action{try{repository.analyze(t,true)}finally{analyzing=false}}}){Text("現在曲を再解析")}
            Text("自動スキャンには含めません。明示した曲だけ低優先度で解析（最大先頭10分）。未解析なら補正せず再生します。",style=MaterialTheme.typography.bodySmall)
        }}
        item{SoundCard("音量均一化"){
            SoundSwitch("音量均一化",config.normalizeEnabled,active){save(config.copy(normalizeEnabled=it))}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){NormalizeMode.entries.forEach{m->FilterChip(selected=config.normalizeMode==m,enabled=active&&config.normalizeEnabled,onClick={save(config.copy(normalizeMode=m))},label={Text(if(m==NormalizeMode.TRACK)"曲単位"else "アルバム単位")})}}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(-18f,-16f,-14f).forEach{target->FilterChip(selected=config.targetLufs==target,enabled=active&&config.normalizeEnabled,onClick={save(config.copy(targetLufs=target))},label={Text("${target.toInt()} LUFS")})}}
            Text("R128 / ReplayGainタグを優先し、なければ解析値を使用。ReplayGainは89 dB基準のタグゲインを使用しLUFSへ読み替えません。増幅は最大+6 dB、ピーク余裕を優先。",style=MaterialTheme.typography.bodySmall)
            if(config.normalizeMode==NormalizeMode.ALBUM){Text("全曲の完全な解析キャッシュが必要。曲別ゲートの長さ加重平均によるアルバム推定値で音量差を保ちます。未完了ならゲイン補正を保留。",style=MaterialTheme.typography.bodySmall)
                Button(enabled=active&&track!=null&&!analyzing,onClick={val t=track?:return@Button;val album=tracks.filter{it.album==t.album&&it.albumArtist.ifBlank{it.artist}==t.albumArtist.ifBlank{t.artist}};analyzing=true;vm.action{try{album.forEach{repository.analyze(it)};repository.analyze(t)}finally{analyzing=false}}}){Text("現在のアルバムを解析")}}
        }}
        item{SoundCard("ステレオ幅"){
            SoundSwitch("ステレオ幅",config.widthEnabled,active){save(config.copy(widthEnabled=it))}
            SoundSlider("幅",config.width,0f..1.2f,active&&config.widthEnabled&&status.channels!=1,"${(config.width*100).roundToInt()}%"){save(config.copy(width=it))}
            Text(if(status.channels==1)"モノラル入力では適用しません"else "Mid/Side方式。100%超は音色や定位が変化します。0%はモノラル。",style=MaterialTheme.typography.bodySmall)
        }}
    }
}

@Composable private fun SoundCard(title:String?,content:@Composable ColumnScope.()->Unit){Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainer){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){if(title!=null)Text(title,style=MaterialTheme.typography.titleMedium);content()}}}
@Composable private fun SoundSwitch(label:String,value:Boolean,enabled:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){Text(label,modifier=Modifier.weight(1f));Switch(value,onChange,enabled=enabled,modifier=Modifier.semantics{contentDescription=label})}}
@Composable private fun SoundSlider(label:String,value:Float,range:ClosedFloatingPointRange<Float>,enabled:Boolean,display:String,onChange:(Float)->Unit){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label);Text(display)};Slider(value.coerceIn(range.start,range.endInclusive),onChange,enabled=enabled,valueRange=range,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics{contentDescription=label})}

