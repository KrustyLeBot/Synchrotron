// Generated: the same recipe in every app of the suite (Synchrotron, Griffonote, Ecoutify, Météorite).
package app.synchrotron.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Fixed brand colors (never the wallpaper's), from the app accent #6D4AFF: Material's tonal-spot
 * scheme for surfaces and containers, the accent at full strength for `primary`.
 */
internal val SuiteLightColors = lightColorScheme(
    primary = Color(0xFF5427E6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCC2FB),
    onPrimaryContainer = Color(0xFF433C6B),
    inversePrimary = Color(0xFFC9BFFF),
    secondary = Color(0xFF605C72),
    onSecondary = Color(0xFFFCF7FF),
    secondaryContainer = Color(0xFFE5DFF9),
    onSecondaryContainer = Color(0xFF534F64),
    tertiary = Color(0xFF785374),
    onTertiary = Color(0xFFFFF7F9),
    tertiaryContainer = Color(0xFFFECEF6),
    onTertiaryContainer = Color(0xFF664263),
    background = Color(0xFFFDF8FE),
    onBackground = Color(0xFF33313A),
    surface = Color(0xFFFDF8FE),
    onSurface = Color(0xFF33313A),
    surfaceVariant = Color(0xFFE5E0ED),
    onSurfaceVariant = Color(0xFF605E68),
    surfaceTint = Color(0xFF5D36EF),
    inverseSurface = Color(0xFF0E0E12),
    inverseOnSurface = Color(0xFF9F9CA2),
    error = Color(0xFFA8364B),
    onError = Color(0xFFFFF7F7),
    errorContainer = Color(0xFFF97386),
    onErrorContainer = Color(0xFF6E0523),
    outline = Color(0xFF7C7984),
    outlineVariant = Color(0xFFB4B0BC),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFDF8FE),
    surfaceContainer = Color(0xFFF1ECF6),
    surfaceContainerHigh = Color(0xFFEBE6F1),
    surfaceContainerHighest = Color(0xFFE5E0ED),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDD8E4),
)

internal val SuiteDarkColors = darkColorScheme(
    primary = Color(0xFFC9BFFF),
    onPrimary = Color(0xFF2F009C),
    primaryContainer = Color(0xFF534E73),
    onPrimaryContainer = Color(0xFFE6DFFF),
    inversePrimary = Color(0xFF5D36EF),
    secondary = Color(0xFFC9C3DC),
    onSecondary = Color(0xFF413E52),
    secondaryContainer = Color(0xFF3C394D),
    onSecondaryContainer = Color(0xFFC2BCD5),
    tertiary = Color(0xFFFFE3F8),
    onTertiary = Color(0xFF6F4A6C),
    tertiaryContainer = Color(0xFFFECEF6),
    onTertiaryContainer = Color(0xFF664263),
    background = Color(0xFF0E0E12),
    onBackground = Color(0xFFE8E3F0),
    surface = Color(0xFF0E0E12),
    onSurface = Color(0xFFE8E3F0),
    surfaceVariant = Color(0xFF26252D),
    onSurfaceVariant = Color(0xFFADA9B5),
    surfaceTint = Color(0xFFC9BFFF),
    inverseSurface = Color(0xFFFDF8FE),
    inverseOnSurface = Color(0xFF565459),
    error = Color(0xFFF97386),
    onError = Color(0xFF490013),
    errorContainer = Color(0xFF871C34),
    onErrorContainer = Color(0xFFFF97A3),
    outline = Color(0xFF77747E),
    outlineVariant = Color(0xFF494750),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2D2B34),
    surfaceContainer = Color(0xFF1A191F),
    surfaceContainerHigh = Color(0xFF201F26),
    surfaceContainerHighest = Color(0xFF26252D),
    surfaceContainerLow = Color(0xFF141318),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceDim = Color(0xFF0E0E12),
)

/** Rounded shapes shared by the whole suite. */
internal val SuiteShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
