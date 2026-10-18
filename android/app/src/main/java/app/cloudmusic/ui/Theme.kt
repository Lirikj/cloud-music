package app.cloudmusic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.sp
import app.cloudmusic.R

data class CloudPalette(val background: Color, val surface: Color, val elevated: Color, val text: Color, val muted: Color, val accent: Color, val onAccent: Color, val soft: Color, val border: Color)
val DarkPalette = CloudPalette(Color(0xFF121212), Color(0xFF1B1B1B), Color(0xFF242424), Color(0xFFF4F4F4), Color(0xFFA7A7A7), Color(0xFF1ED760), Color(0xFF092411), Color(0xFF183724), Color(0xFF333333))
val LightPalette = CloudPalette(Color(0xFFF7F7F7), Color.White, Color(0xFFEAEAEA), Color(0xFF151515), Color(0xFF616161), Color(0xFF137A38), Color.White, Color(0xFFE4F3E8), Color(0xFFDADADA))
val LocalCloudPalette = staticCompositionLocalOf { DarkPalette }
@OptIn(ExperimentalTextApi::class)
private val Manrope = FontFamily(listOf(400, 500, 600, 700, 800).map { weight ->
    Font(R.font.manrope, weight = FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
})

@Composable fun CloudMusicTheme(light: Boolean, content: @Composable () -> Unit) {
    val p = if (light) LightPalette else DarkPalette
    val scheme = if (light) lightColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text, surfaceVariant = p.elevated, onSurfaceVariant = p.muted, outline = p.border)
    else darkColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text, surfaceVariant = p.elevated, onSurfaceVariant = p.muted, outline = p.border)
    val typography = Typography(
        headlineLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, letterSpacing = (-1).sp),
        headlineMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 27.sp, letterSpacing = (-0.7).sp),
        titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 16.sp),
        titleSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 14.sp),
        bodyLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 16.sp),
        bodyMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 14.sp),
        bodySmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 12.sp),
        labelLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 14.sp),
        labelMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 12.sp),
        labelSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 11.sp),
    )
    CompositionLocalProvider(LocalCloudPalette provides p) { MaterialTheme(colorScheme = scheme, typography = typography, content = content) }
}
