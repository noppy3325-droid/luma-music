package jp.noppy.lumamusic.audio

import android.content.Context
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import jp.noppy.lumamusic.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DeviceReport(
    val id: Int, val name: String, val type: String, val usb: Boolean,
    val rates: List<Int>, val channels: List<Int>, val encodings: List<Int>,
)
data class OutputState(
    val connected: List<DeviceReport> = emptyList(),
    val actual: DeviceReport? = null,
    val preferredId: Int? = null,
    val track: PcmSpec? = null,
    val requested: OutputMode = OutputMode.USB_HIGH_QUALITY,
    val method: String = "Android標準音声再生",
    val bitPerfect: String = "未確認",
    val message: String? = null,
    val mixerApplied: Boolean = false,
) {
    val bitPerfectVolumeRestricted: Boolean
        get() = mixerApplied && requested == OutputMode.BIT_PERFECT && actual?.usb == true
}

class OutputMonitor(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(OutputState())
    val state = mutable.asStateFlow()
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var engine: AudioOutputEngine = UsbHighQualityEngine()
    private var started = false
    private var track: AudioTrack? = null
    private var activeId: Int? = null
    private var preferredId: Int? = null
    private var appliedDevice: AudioDeviceInfo? = null
    // Guarded API-34 types stay in MixerApi34, which is only constructed on API 34+.
    private var mixer: MixerApi34? = null
    private var unprocessed = true
    private var source: PcmSpec? = null
    private var optimizationFailed = false
    private var onDisconnect: (() -> Unit)? = null
    private val routeListener = AudioRouting.OnRoutingChangedListener { routing ->
        if (routing === track) updateRoute()
    }
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refreshDevices(); updateRoute() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val removed = removedDevices.filter { it.isSink }.map { it.id }.toSet()
            if (shouldPauseOnRemoval(activeId, preferredId ?: appliedDevice?.id, removed)) {
                onDisconnect?.invoke()
                clearPreference()
                activeId = null
                if (preferredId in removed) preferredId = null
                mutable.update { it.copy(actual = null, preferredId = preferredId, message = "音声機器が取り外されたため一時停止しました") }
            }
            refreshDevices()
        }
    }
    private val poll = object : Runnable {
        override fun run() { if (started) { updateRoute(); handler.postDelayed(this, 700) } }
    }

    fun start(onDisconnect: () -> Unit) {
        this.onDisconnect = onDisconnect
        if (started) return
        started = true
        if (Build.VERSION.SDK_INT >= 34) mixer = runCatching { MixerApi34(audio, attributes, handler) { deviceId ->
            if (appliedDevice?.id == deviceId) {
                mutable.update { it.copy(mixerApplied = false, method = "Android標準音声再生", bitPerfect = "設定変更・実出力未確認", message = "ミキサー設定が解除または変更されました") }
            }
        } }.getOrNull()
        audio.registerAudioDeviceCallback(callback, handler)
        refreshDevices()
        handler.post(poll)
    }

    @Synchronized fun mode(value: OutputMode) {
        if (engine.mode == value) return
        optimizationFailed = false
        engine = when (value) { OutputMode.STANDARD -> StandardMedia3Engine(); OutputMode.USB_HIGH_QUALITY -> UsbHighQualityEngine(); OutputMode.BIT_PERFECT -> UsbBitPerfectEngine() }
        clearPreference()
        mutable.update { it.copy(requested = value) }
        configure(mutable.value.track, track?.format?.channelMask ?: 0)
    }

    @Synchronized fun processingIsUnchanged(value: Boolean) {
        if (unprocessed == value) return
        unprocessed = value
        clearPreference()
        configure(mutable.value.track, track?.format?.channelMask ?: 0)
    }

    fun device(id: Int?): AudioDeviceInfo? = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
    fun preferDevice(id: Int?) {
        clearPreference()
        preferredId = id
        mutable.update { it.copy(preferredId = id, actual = null) }
    }

    /** Called before AudioTrack creation, using the actual PCM configuration produced by Media3. */
    @Synchronized fun configure(spec: PcmSpec?, channelMask: Int) {
        mutable.update { it.copy(track = spec) }
        val anticipated = if (preferredId != null) device(preferredId) else if (Build.VERSION.SDK_INT >= 33)
            runCatching { audio.getAudioDevicesForAttributes(attributes).singleOrNull() }.getOrNull() else device(activeId)
        val target = anticipated?.takeIf { isUsb(it) }
        if (target == null || spec == null || engine.mode == OutputMode.STANDARD || optimizationFailed) {
            clearPreference(); return
        }
        val api = mixer
        if (Build.VERSION.SDK_INT < 34 || api == null) {
            clearPreference()
            mutable.update { it.copy(bitPerfect = if (Build.VERSION.SDK_INT < 34) "このOSではAPI非対応" else "ミキサーAPI利用不可・未確認", message = "Android標準音声再生を使用します") }
            return
        }
        if (appliedDevice?.id != target.id) clearPreference()
        val result = api.apply(target, spec, channelMask, engine, unprocessed && unchangedPcm(source, spec, 1f, 1f, false))
        appliedDevice = target.takeIf { result.applied }
        mutable.update { it.copy(
            method = if (result.applied) if (engine.mode == OutputMode.BIT_PERFECT) "USB / BIT_PERFECT API設定（実出力未確認）" else "USB / 形式一致のミキサー希望設定済み" else "Android標準音声再生",
            bitPerfect = result.bitPerfect,
            message = result.message,
            mixerApplied = result.applied,
        ) }
    }

    fun observe(newTrack: AudioTrack) {
        handler.post {
            track?.let { runCatching { it.removeOnRoutingChangedListener(routeListener) } }
            track = newTrack
            activeId = null
            mutable.update { it.copy(actual = null) }
            newTrack.addOnRoutingChangedListener(routeListener, handler)
            updateRoute()
        }
    }

    private fun updateRoute() {
        val current = track ?: return
        val route = runCatching { if (current.state == AudioTrack.STATE_INITIALIZED) current.routedDevice else null }.getOrNull()
        if (route != null && route.id != activeId) {
            val previous = activeId
            // Routing can change before onAudioDevicesRemoved is delivered.
            if (shouldPauseOnRouteChange(mutable.value.actual?.usb == true, previous, route.id)) onDisconnect?.invoke()
            activeId = route.id
            if (previous != null && previous != route.id) clearPreference()
            mutable.update { it.copy(actual = report(route)) }
            configure(mutable.value.track, current.format.channelMask)
        }
    }

    private fun refreshDevices() { mutable.update { it.copy(connected = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { d -> d.isSink }.map(::report)) } }

    @Synchronized fun clearPreference() {
        if (Build.VERSION.SDK_INT >= 34) mixer?.clear()
        appliedDevice = null
        mutable.update { it.copy(mixerApplied = false, method = "Android標準音声再生", bitPerfect = "未確認") }
    }

    @Synchronized fun noteSource(value: PcmSpec?) { source = value }
    @Synchronized fun newSource() { optimizationFailed = false; source = null; clearPreference() }
    @Synchronized fun fallback(reason: String) { optimizationFailed = true; clearPreference(); mutable.update { it.copy(message = reason) } }
    fun close() {
        started = false
        audio.unregisterAudioDeviceCallback(callback)
        handler.removeCallbacks(poll)
        track?.let { runCatching { it.removeOnRoutingChangedListener(routeListener) } }
        track = null
        activeId = null
        clearPreference()
        if (Build.VERSION.SDK_INT >= 34) mixer?.close()
        mixer = null
        onDisconnect = null
        mutable.update { it.copy(actual = null, track = null) }
    }
}

fun isUsb(device: AudioDeviceInfo) = device.type in setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)
private fun report(d: AudioDeviceInfo) = DeviceReport(d.id, d.productName.toString(), when {
    isUsb(d) -> "USB Audio"
    d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || d.type == AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
    d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内蔵スピーカー"
    else -> "Android Audio"
}, isUsb(d), d.sampleRates.toList(), d.channelCounts.toList(), d.encodings.toList())

data class MixerResult(val applied: Boolean, val bitPerfect: String, val message: String?)

@RequiresApi(34)
private class MixerApi34(
    private val audio: AudioManager, private val attributes: AudioAttributes, handler: Handler, onInvalidated: (Int) -> Unit,
) {
    private var device: AudioDeviceInfo? = null
    private var selected: AudioMixerAttributes? = null
    private val listener = AudioManager.OnPreferredMixerAttributesChangedListener { attrs, changedDevice, mixer ->
        if (attrs.usage == attributes.usage && changedDevice.id == device?.id && mixer != selected) onInvalidated(changedDevice.id)
    }
    init { audio.addOnPreferredMixerAttributesChangedListener({ runnable -> handler.post(runnable) }, listener) }
    fun apply(target: AudioDeviceInfo, spec: PcmSpec, mask: Int, engine: AudioOutputEngine, unchanged: Boolean): MixerResult {
        return try {
            val supported = audio.getSupportedMixerAttributes(target)
            val candidates = supported.map { MixerOption(PcmSpec(it.format.sampleRate, it.format.channelCount, it.format.encoding), it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) }
            val support = if (candidates.any { it.bitPerfect }) "対応形式あり（動作未確認）" else "この接続で対応形式なし"
            val choice = engine.select(spec, candidates, unchanged)
            val attribute = choice?.let { chosen -> supported.firstOrNull { candidate ->
                PcmSpec(candidate.format.sampleRate, candidate.format.channelCount, candidate.format.encoding) == chosen.spec &&
                    (candidate.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) == chosen.bitPerfect &&
                    candidate.format.channelMask == mask
            } }
            if (attribute == null) {
                clear()
                MixerResult(false, support, "一致するミキサー形式を適用できないため通常再生します")
            } else if (selected == attribute && device?.id == target.id && audio.getPreferredMixerAttributes(attributes, target) == attribute) {
                MixerResult(true, if (choice.bitPerfect) "API設定済み・実出力未確認" else support, null)
            } else {
                clear()
                val accepted = audio.setPreferredMixerAttributes(attributes, target, attribute)
                if (accepted && audio.getPreferredMixerAttributes(attributes, target) == attribute) {
                    device = target; selected = attribute
                    MixerResult(true, if (choice.bitPerfect) "API設定済み・実出力未確認" else support, null)
                } else {
                    audio.clearPreferredMixerAttributes(attributes, target)
                    MixerResult(false, support, "ミキサー設定が受理されなかったため通常再生します")
                }
            }
        } catch (e: RuntimeException) {
            runCatching { audio.clearPreferredMixerAttributes(attributes, target) }
            clear()
            MixerResult(false, "未確認", "ミキサーAPIを利用できません: ${e.localizedMessage}。通常再生します")
        }
    }
    fun clear() {
        val old = device
        device = null; selected = null
        if (old != null) runCatching { audio.clearPreferredMixerAttributes(attributes, old) }
    }
    fun close() { clear(); audio.removeOnPreferredMixerAttributesChangedListener(listener) }
}

fun encodingName(encoding: Int): String = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> "PCM 8 bit"
    AudioFormat.ENCODING_PCM_16BIT -> "PCM 16 bit"
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24 bit"
    AudioFormat.ENCODING_PCM_32BIT -> "PCM 32 bit"
    AudioFormat.ENCODING_PCM_FLOAT -> "PCM float 32 bit"
    else -> "encoding=$encoding"
}
