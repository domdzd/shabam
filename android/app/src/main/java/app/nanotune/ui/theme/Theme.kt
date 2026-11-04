package app.nanotune.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.nanotune.data.settings.ThemeMode

// Brand colours live here so the app can be re-skinned in one place.
object Brand {
    val Accent = Color(0xFF3DDBB5)
    val AccentDeep = Color(0xFF00876B)
    val Night = Color(0xFF0D1117)
    val NightRaised = Color(0xFF151B23)
    val NightHigh = Color(0xFF1C242E)
    val Live = Color(0xFFFF5A5F)
}

private val DarkColors = darkColorScheme(
    primary = Brand.Accent,
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF0F4A3D),
    onPrimaryContainer = Color(0xFFA8F5DE),
    secondary = Color(0xFFB4CAC1),
    onSecondary = Color(0xFF1F352E),
    secondaryContainer = Color(0xFF24312D),
    onSecondaryContainer = Color(0xFFD0E8DE),
    tertiary = Color(0xFFA9C7FF),
    onTertiary = Color(0xFF0B305F),
    background = Brand.Night,
    onBackground = Color(0xFFE2E6EB),
    surface = Brand.Night,
    onSurface = Color(0xFFE2E6EB),
    surfaceVariant = Brand.NightHigh,
    onSurfaceVariant = Color(0xFFAAB4BF),
    surfaceContainerLowest = Color(0xFF090C10),
    surfaceContainerLow = Color(0xFF11161D),
    surfaceContainer = Brand.NightRaised,
    surfaceContainerHigh = Brand.NightHigh,
    surfaceContainerHighest = Color(0xFF242E39),
    outline = Color(0xFF3B4652),
    outlineVariant = Color(0xFF2A333D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Brand.AccentDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F3DF),
    onPrimaryContainer = Color(0xFF002118),
    secondary = Color(0xFF4B635A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8DC),
    onSecondaryContainer = Color(0xFF072019),
    tertiary = Color(0xFF3A5F94),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF181C1B),
    surface = Color(0xFFF7FAF8),
    onSurface = Color(0xFF181C1B),
    surfaceVariant = Color(0xFFDCE5E0),
    onSurfaceVariant = Color(0xFF404945),
    outline = Color(0xFF707975),
)

private val NanoTuneTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    )
}

@Composable
fun isAppInDarkTheme(theme: ThemeMode): Boolean = when (theme) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

@Composable
fun NanoTuneTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = NanoTuneTypography,
        content = content,
    )
}
