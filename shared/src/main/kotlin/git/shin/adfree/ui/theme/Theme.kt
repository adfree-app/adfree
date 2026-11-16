package git.shin.adfree.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val DarkColorScheme = darkColorScheme(
    primary = AdGuardDarkColors.greenPrimary,
    onPrimary = Color.Black,
    primaryContainer = AdGuardDarkColors.greenDark,
    onPrimaryContainer = Color.White,
    secondary = AdGuardDarkColors.blueDns,
    onSecondary = Color.Black,
    secondaryContainer = AdGuardDarkColors.surfaceVariant,
    onSecondaryContainer = Color.White,
    tertiary = AdGuardDarkColors.redBlocked,
    onTertiary = Color.White,
    background = AdGuardDarkColors.bg,
    onBackground = AdGuardDarkColors.textPrimary,
    surface = AdGuardDarkColors.surface,
    onSurface = AdGuardDarkColors.textPrimary,
    surfaceVariant = AdGuardDarkColors.surfaceVariant,
    onSurfaceVariant = AdGuardDarkColors.textSecondary,
    outline = AdGuardDarkColors.outline,
    outlineVariant = AdGuardDarkColors.outline.copy(alpha = 0.5f),
)

private val LightColorScheme = lightColorScheme(
    primary = AdGuardLightColors.greenPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4EFD9),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = AdGuardLightColors.blueDns,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E9F7),
    onSecondaryContainer = Color(0xFF001E30),
    tertiary = AdGuardLightColors.redBlocked,
    onTertiary = Color.White,
    background = AdGuardLightColors.bg,
    onBackground = AdGuardLightColors.textPrimary,
    surface = AdGuardLightColors.surface,
    onSurface = AdGuardLightColors.textPrimary,
    surfaceVariant = AdGuardLightColors.surfaceVariant,
    onSurfaceVariant = AdGuardLightColors.textSecondary,
    outline = AdGuardLightColors.outline,
    outlineVariant = AdGuardLightColors.outline.copy(alpha = 0.5f),
)

/** Labels were laid out at 9-13sp; scale text without resizing dp containers. */
private const val FontScale = 1.2f

/**
 * Whether the theme has already scaled the text above this point.
 *
 * The scale is a multiplication, so a theme nested inside another multiplies
 * twice: 1.2 becomes 1.44, and every label in the app grows a fifth while the dp
 * boxes around them do not. That is not a hypothetical — the desktop applies the
 * theme at the window so the title bar shares the palette, and the app screen
 * applies it again for its own settings, which made the whole UI read "too big"
 * on the day the custom title bar landed.
 */
private val LocalFontScaleApplied = staticCompositionLocalOf { false }

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Keep AdGuard's branded colors
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val palette = if (darkTheme) AdGuardDarkColors else AdGuardLightColors
    val density = LocalDensity.current
    val scaled = LocalFontScaleApplied.current

    CompositionLocalProvider(
        LocalAdGuardColors provides palette,
        LocalFontScaleApplied provides true,
    ) {
        if (scaled) {
            // Already scaled further up the tree: only the palette is restored.
            MaterialTheme(
                colorScheme = colorScheme,
                typography = Typography,
                content = content
            )
        } else {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = density.density,
                    fontScale = density.fontScale * FontScale,
                )
            ) {
                MaterialTheme(
                    colorScheme = colorScheme,
                    typography = Typography,
                    content = content
                )
            }
        }
    }
}
