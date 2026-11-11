package jp.noppy.lumamusic

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import jp.noppy.lumamusic.ui.*

class MainActivity : ComponentActivity() {
    private val model by viewModels<LumaViewModel>()
    private lateinit var standby: StandbyController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_MUSIC
        standby = StandbyController(this)
        setContent { if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.getBooleanExtra("luma.glassDemo", false)) GlassDemoScreen() else LumaScreen(model, standby) }
    }
    override fun onResume() { super.onResume(); if (::standby.isInitialized) standby.foreground(true); model.refreshIfStale() }
    override fun onPause() { if (::standby.isInitialized) standby.foreground(false); super.onPause() }
    override fun onDestroy() { if (::standby.isInitialized) standby.close(); super.onDestroy() }
}
