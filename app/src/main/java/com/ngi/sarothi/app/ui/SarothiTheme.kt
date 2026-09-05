package com.ngi.sarothi.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Sarothi's palette.
 *
 * Fixed colours rather than `dynamicColorScheme`: Material You takes its palette from the
 * wallpaper, which would make the safety states unreadable. Red has to mean "this step
 * spends money or deletes something" on every phone, not "this matches your wallpaper".
 * The accent is a deep amber after the lantern in the app mark.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF7A4E00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB0),
    onPrimaryContainer = Color(0xFF261500),
    secondary = Color(0xFF00524C),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFBF7),
    onBackground = Color(0xFF1E1B16),
    surface = Color(0xFFFFFBF7),
    onSurface = Color(0xFF1E1B16),
    surfaceVariant = Color(0xFFF0E3D5),
    onSurfaceVariant = Color(0xFF4F463B),
    error = Color(0xFFA3140F),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF817669),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF8BC63),
    onPrimary = Color(0xFF402A00),
    primaryContainer = Color(0xFF5C3A00),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFF7FDBC8),
    onSecondary = Color(0xFF003731),
    background = Color(0xFF151310),
    onBackground = Color(0xFFE9E1D8),
    surface = Color(0xFF151310),
    onSurface = Color(0xFFE9E1D8),
    surfaceVariant = Color(0xFF3A332B),
    onSurfaceVariant = Color(0xFFCDBFB0),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5C0B07),
    outline = Color(0xFF968A7C),
)

/** Sensitivity colours, shared by the checklist and the confirmation dialog. */
object SarothiStates {
    val danger = Color(0xFFA3140F)
    val caution = Color(0xFF8A5300)
    val done = Color(0xFF2E6B32)
}

@Composable
fun SarothiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
