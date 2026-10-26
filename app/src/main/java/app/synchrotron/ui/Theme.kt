package app.synchrotron.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun SynchrotronTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) SuiteDarkColors else SuiteLightColors,
        shapes = SuiteShapes,
        content = content,
    )
}
