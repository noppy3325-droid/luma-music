package jp.noppy.lumamusic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import jp.noppy.lumamusic.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.highlight.Highlight

object LumaColors {
    val Accent = Color(0xFFC43B5A)
    val Light = Color(0xFFF7F7F9)
    val Dark = Color(0xFF121215)
}
object LumaSpacing { val Small = 8.dp; val Medium = 16.dp; val Page = 24.dp; val Section = 32.dp }
object LumaShapes { val Artwork = RoundedCornerShape(14.dp); val Search = RoundedCornerShape(14.dp); val Pill = RoundedCornerShape(50) }
internal object LumaDock {
    val HorizontalMargin = 16.dp
    val BottomMargin = 12.dp
    val PanelGap = 10.dp
    val ListClearance = 8.dp
    const val MiniRadius = 24
    const val NavigationRadius = 32
    val GlassHighlight = Highlight.Ambient.copy(alpha = .52f)
}
// Fixed official faces avoid OEMs ignoring the weight axis of a variable resource font.
val AurioFontFamily = FontFamily(
    Font(R.font.noto_sans_jp_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.noto_sans_jp_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.noto_sans_jp_bold, FontWeight.Bold, FontStyle.Normal),
)
private val defaultTypography = Typography()
// Override every Material style: buttons/chips/dialogs must not fall back to an OEM theme font.
val LumaTypography = Typography(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = AurioFontFamily),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = AurioFontFamily),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = AurioFontFamily),
    headlineLarge = TextStyle(fontFamily = AurioFontFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-.7).sp),
    headlineMedium = TextStyle(fontFamily = AurioFontFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = AurioFontFamily, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 33.sp, letterSpacing = (-.35).sp),
    titleLarge = TextStyle(fontFamily = AurioFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = AurioFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = AurioFontFamily),
    bodyLarge = TextStyle(fontFamily = AurioFontFamily, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = AurioFontFamily, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = AurioFontFamily, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = AurioFontFamily),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = AurioFontFamily),
    labelSmall = TextStyle(fontFamily = AurioFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)

@Composable fun LumaTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || theme == "system" && isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = Color(0xFFEE7791), background = LumaColors.Dark, surface = Color(0xFF202024), onBackground = Color(0xFFF7F7F9), onSurface = Color(0xFFF7F7F9), onSurfaceVariant = Color(0xFFB5B5BE))
        else lightColorScheme(primary = LumaColors.Accent, background = LumaColors.Light, surface = Color.White, onBackground = Color(0xFF19191D), onSurface = Color(0xFF19191D), onSurfaceVariant = Color(0xFF72727B))
    MaterialTheme(colorScheme = colors, typography = LumaTypography, content = content)
}
