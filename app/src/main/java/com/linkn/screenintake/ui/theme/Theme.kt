package com.linkn.screenintake.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 品牌色沿用原来的深绿，往外扩成一套完整的 Material 3 配色（浅色/深色各一套），
// 让卡片、状态角标、分割线这些细节都有统一、协调的颜色，而不是零散地各写各的。
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFEEDD),
    onPrimaryContainer = Color(0xFF002013),
    secondary = Color(0xFF4A6355),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE9D8),
    onSecondaryContainer = Color(0xFF092017),
    tertiary = Color(0xFF3D6373),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC1E8FB),
    onTertiaryContainer = Color(0xFF001F29),
    background = Color(0xFFFAFAF6),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFFAFAF6),
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFDFE4DA),
    onSurfaceVariant = Color(0xFF43483F),
    outline = Color(0xFF73796E),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD7BB),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513C),
    onPrimaryContainer = Color(0xFFB1F1D9),
    secondary = Color(0xFFB1CCBB),
    onSecondary = Color(0xFF1D352A),
    secondaryContainer = Color(0xFF334B3F),
    onSecondaryContainer = Color(0xFFCDE9D8),
    tertiary = Color(0xFFA5CDDF),
    onTertiary = Color(0xFF063542),
    tertiaryContainer = Color(0xFF244C5A),
    onTertiaryContainer = Color(0xFFC1E8FB),
    background = Color(0xFF12140F),
    onBackground = Color(0xFFE2E3DC),
    surface = Color(0xFF12140F),
    onSurface = Color(0xFFE2E3DC),
    surfaceVariant = Color(0xFF43483F),
    onSurfaceVariant = Color(0xFFC3C8BB),
    outline = Color(0xFF8D9388),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun ScreenIntakeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
