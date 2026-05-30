package org.tan.cdntest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Light theme colors (matches values/colors.xml)
private val Primary = Color(0xFF1976D2)
private val PrimaryDark = Color(0xFF1565C0)
private val Accent = Color(0xFFFF6F00)
private val BackgroundLight = Color(0xFFF5F5F5)
private val TextPrimaryLight = Color(0xFF212121)
private val TextSecondaryLight = Color(0xFF757575)

// Dark theme colors (matches values-night/colors.xml)
private val AccentDark = Color(0xFFFFAB40)
private val BackgroundDark = Color(0xFF121212)
private val TextPrimaryDark = Color(0xFFE0E0E0)
private val TextSecondaryDark = Color(0xFF9E9E9E)

private val LightColors = lightColors(
    primary = Primary,
    primaryVariant = PrimaryDark,
    secondary = Accent,
    background = BackgroundLight,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight,
)

private val DarkColors = darkColors(
    primary = Primary,
    primaryVariant = PrimaryDark,
    secondary = AccentDark,
    background = BackgroundDark,
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
)

// Expose colors for direct use in composables — auto-follows system theme
object AppColors {
    val primary = Primary
    val white = Color.White

    @Composable
    fun background() = if (isSystemInDarkTheme()) BackgroundDark else BackgroundLight

    @Composable
    fun textPrimary() = if (isSystemInDarkTheme()) TextPrimaryDark else TextPrimaryLight

    @Composable
    fun textSecondary() = if (isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight
}

@Composable
fun CDNTestTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colors = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
