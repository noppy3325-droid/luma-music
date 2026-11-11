package jp.noppy.lumamusic.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.OrientationEventListener
import androidx.compose.runtime.*

class StandbyController(private val activity: Activity) {
    var armed by mutableStateOf(false); private set
    var active by mutableStateOf(false); private set
    private var resumed = false
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private val sensor = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(angle: Int) {
            if (!armed || !resumed || angle == ORIENTATION_UNKNOWN) return
            when {
                angle in 65..115 || angle in 245..295 -> {
                    active = true
                    activity.requestedOrientation = if (angle in 65..115) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                active && (angle in 0..25 || angle in 335..359 || angle in 155..205) -> exit()
            }
        }
    }
    fun arm() {
        if (armed) { exit(); return }
        originalOrientation = activity.requestedOrientation
        armed = true
        if (resumed && sensor.canDetectOrientation()) sensor.enable()
    }
    fun foreground(value: Boolean) { resumed = value; if (value && armed) sensor.enable() else sensor.disable() }
    fun exit() { sensor.disable(); active = false; armed = false; activity.requestedOrientation = originalOrientation }
    fun close() { if (armed) exit() else sensor.disable() }
}
