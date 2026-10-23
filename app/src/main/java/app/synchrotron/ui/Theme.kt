package app.synchrotron.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val Violet = Color(0xFF6D4AFF)

private val LightColors = lightColorScheme(
    primary = Violet, onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF), onPrimaryContainer = Color(0xFF21005D),
    secondaryContainer = Color(0xFFE8E0F5), background = Color(0xFFFBF9FF), surface = Color(0xFFFBF9FF),
    surfaceContainer = Color(0xFFF1EDFA), surfaceContainerHigh = Color(0xFFEBE6F5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCBBEFF), onPrimary = Color(0xFF330092),
    primaryContainer = Color(0xFF4D24D6), onPrimaryContainer = Color(0xFFE6DEFF),
    background = Color(0xFF14121A), surface = Color(0xFF14121A),
    surfaceContainer = Color(0xFF1F1C27), surfaceContainerHigh = Color(0xFF2A2634),
)

@Composable
fun SynchrotronTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
