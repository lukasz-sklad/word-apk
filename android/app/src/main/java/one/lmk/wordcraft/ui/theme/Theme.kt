package one.lmk.wordcraft.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

enum class AppThemeMode {
    SYSTEM, LIGHT, DARK, SEPIA
}

private val DarkColorScheme = darkColorScheme(
    primary = WordBlueSecondary,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = DarkText,
    onSurface = DarkText
)

private val LightColorScheme = lightColorScheme(
    primary = WordBluePrimary,
    background = LightBackground,
    surface = LightSurface,
    onBackground = LightText,
    onSurface = LightText
)

private val SepiaColorScheme = lightColorScheme(
    primary = WordAccent,
    background = SepiaBackground,
    surface = SepiaSurface,
    onBackground = SepiaText,
    onSurface = SepiaText
)

@Composable
fun WordCraftTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemInDark = isSystemInDarkTheme()
    val colorScheme = when (themeMode) {
        AppThemeMode.SYSTEM -> if (systemInDark) DarkColorScheme else LightColorScheme
        AppThemeMode.DARK -> DarkColorScheme
        AppThemeMode.LIGHT -> LightColorScheme
        AppThemeMode.SEPIA -> SepiaColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
