package jp.noppy.lumamusic

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.noppy.lumamusic.ui.LumaTheme
import jp.noppy.lumamusic.ui.TrackText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TrackTextDeviceTest {
    @Test fun longMixedTitleEllipsizesAtLargerScaleAndKeepsFullAccessibilityText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        ) as MainActivity
        val title = "あなたの夜が明けるまで (feat. ＩＡ) / A long mixed-language title"
        val result = AtomicReference<TextLayoutResult>()
        val laidOut = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    val deviceDensity = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(deviceDensity, fontScale = 1.6f)) {
                        LumaTheme("light") {
                            Column(Modifier.width(170.dp)) {
                                TrackText(title, onTextLayout = { layout ->
                                    result.set(layout)
                                    laidOut.countDown()
                                })
                            }
                        }
                    }
                }
            }
            assertTrue("Compose text was laid out", laidOut.await(5, TimeUnit.SECONDS))
            val layout = result.get()
            assertEquals(1, layout.lineCount)
            assertTrue("The line has an ellipsis", layout.isLineEllipsized(0))

            val automation = instrumentation.uiAutomation
            val until = SystemClock.elapsedRealtime() + 5000
            var semantics: String? = null
            while (semantics == null && SystemClock.elapsedRealtime() < until) {
                if (Build.VERSION.SDK_INT >= 36) automation.clearCache()
                val nodes = ArrayDeque<AccessibilityNodeInfo>()
                automation.rootInActiveWindow?.let(nodes::add)
                while (nodes.isNotEmpty()) {
                    val node = nodes.removeFirst()
                    val text = node.text?.toString()
                    if (text == title) {
                        semantics = text
                        break
                    }
                    repeat(node.childCount) { node.getChild(it)?.let(nodes::add) }
                }
                if (semantics == null) Thread.sleep(100)
            }
            assertNotNull("Full title is available to accessibility", semantics)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
