package jp.noppy.lumamusic

import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.activity.compose.setContent
import jp.noppy.lumamusic.ui.GlassDemoScreen
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GlassSpikeTest {
    private fun launchDemo() {
        val i=InstrumentationRegistry.getInstrumentation()
        val a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
        // Exercise the production component in release builds as well as debug builds.
        i.runOnMainSync { a.setContent { GlassDemoScreen() } }
        Thread.sleep(1500)
    }
    @Test fun compareGlassFrames() {
        val i=InstrumentationRegistry.getInstrumentation();val ctx=i.targetContext
        launchDemo()
        fun nodes():List<AccessibilityNodeInfo>{if(android.os.Build.VERSION.SDK_INT>=36)i.uiAutomation.clearCache();val list=mutableListOf<AccessibilityNodeInfo>();val q=ArrayDeque<AccessibilityNodeInfo>();i.uiAutomation.rootInActiveWindow?.let(q::add);while(q.isNotEmpty()){val n=q.removeFirst();n.refresh();list.add(n);for(k in 0 until n.childCount)n.getChild(k)?.let(q::add)};return list}
        fun find(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo {repeat(30){nodes().firstOrNull(predicate)?.let{return it};Thread.sleep(100)};error("Accessibility target missing: ${nodes().mapNotNull{it.text}}")}
        fun shell(cmd:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(cmd)).bufferedReader().use{it.readText()}
        fun scroll(action:Int) {
            for(attempt in 0 until 20){if(find{it.isScrollable}.performAction(action)){Thread.sleep(1000);return};Thread.sleep(100)}
            fail("Scroll action $action did not dispatch")
        }
        for(label in listOf("バランス","オフ")){
            for(page in 0 until 8){if(!find{it.isScrollable}.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))break;Thread.sleep(1000)}
            var button=find{it.text?.toString()==label};while(!button.isClickable)button=button.parent
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(700)
            repeat(2){scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)}
            shell("dumpsys gfxinfo jp.noppy.lumamusic reset")
            val cpu=android.os.Process.getElapsedCpuTime();val wall=android.os.SystemClock.elapsedRealtime()
            repeat(12){step->scroll(if(step%2==0)AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)}
            val timing="processCpuMs=${android.os.Process.getElapsedCpuTime()-cpu} wallMs=${android.os.SystemClock.elapsedRealtime()-wall}"
            val result=shell("dumpsys gfxinfo jp.noppy.lumamusic")
            val stem=if(label=="オフ")"glass-perf-off"else "glass-perf-balanced"
            File(ctx.getExternalFilesDir(null),"$stem.txt").writeText(result+"\n"+timing+"\n")
            File(ctx.getExternalFilesDir(null),"$stem-memory.txt").writeText(shell("dumpsys meminfo jp.noppy.lumamusic"))
            android.util.Log.i("LumaGlassPerf","$label $timing ${result.lineSequence().filter{it.contains("Total frames")||it.contains("Janky")||it.contains("percentile")}.joinToString("; ")}")
        }
    }
    @Test fun liveScrollQualityAndRotation() {
        val i=InstrumentationRegistry.getInstrumentation()
        val ctx=i.targetContext
        launchDemo()
        fun nodes():List<AccessibilityNodeInfo> {
            val root=i.uiAutomation.rootInActiveWindow?:error("No active window")
            val out=mutableListOf<AccessibilityNodeInfo>();val pending=ArrayDeque<AccessibilityNodeInfo>();pending.add(root)
            while(pending.isNotEmpty()){val n=pending.removeFirst();n.refresh();out.add(n);for(x in 0 until n.childCount)n.getChild(x)?.let(pending::add)}
            return out
        }
        fun shot(name:String) { val b=i.uiAutomation.takeScreenshot()?:error("No screenshot");File(ctx.getExternalFilesDir(null),"$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle() }
        shot("glass-spike-before")
        val scroll=nodes().firstOrNull{it.isScrollable}?:error("No scroll semantics")
        assertTrue("Accessibility scroll must dispatch",scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        Thread.sleep(700);shot("glass-spike-after")
        for(label in listOf("高品質","軽量","オフ","バランス")) {
            var button=nodes().firstOrNull{it.text?.toString()==label}?:error("No quality $label")
            while(!button.isClickable) button=button.parent?:error("No clickable ancestor for $label")
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(1200)
            shot("glass-spike-$label")
            assertTrue("Updated label $label: ${nodes().mapNotNull{it.text}.joinToString()}",nodes().any{it.text?.toString()?.contains("· $label")==true})
        }
        var activity:android.app.Activity?=null
        i.runOnMainSync{activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()}
        val a=activity?:error("No resumed activity");val prior=a.requestedOrientation
        try { i.runOnMainSync{a.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE};Thread.sleep(1000);shot("glass-spike-landscape") }
        finally { i.runOnMainSync{a.requestedOrientation=prior} }
    }
}
