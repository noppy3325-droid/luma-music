package jp.noppy.lumamusic

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BrandAssetsTest {
    @Test fun installedLauncherEntrySuppliesAdaptiveIcon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = context.getSystemService(LauncherApps::class.java)
        val entry = launcher.getActivityList(context.packageName, Process.myUserHandle())
            .single { it.componentName.className == MainActivity::class.java.name }
        assertEquals("Aurio", entry.label.toString())

        // HyperOS can return a flattened, themed bitmap to launcher clients.
        writeBrandArtwork(context, "launcher-installed.png", entry.getIcon(0), size = 192)
        val icon = context.resources.getXml(R.mipmap.ic_launcher).use { parser ->
            Drawable.createFromXml(context.resources, parser, context.theme) as AdaptiveIconDrawable
        }
        assertNotNull(icon.foreground)
        assertNotNull(icon.background)
        writeBrandArtwork(context, "launcher-adaptive.png", icon, size = 192)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val themed = checkNotNull(icon.monochrome).mutate()
            themed.setTint(Color.rgb(225, 202, 232))
            writeBrandArtwork(context, "launcher-monochrome.png", themed, size = 192)
        }
    }
}

/** Render only the app drawable obtained from the device, without reading private system UI. */
internal fun writeBrandArtwork(context: Context, fileName: String, drawable: Drawable, size: Int) {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    try {
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        assertTrue("Icon has no visible pixels: $fileName", (0 until size).any { y ->
            (0 until size).any { x -> Color.alpha(bitmap.getPixel(x, y)) > 0 }
        })
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "brand-assets")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, fileName).outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    } finally {
        bitmap.recycle()
    }
}
