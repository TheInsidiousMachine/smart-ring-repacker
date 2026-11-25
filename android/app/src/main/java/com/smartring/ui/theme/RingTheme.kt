package com.smartring.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE15A4A),
    onPrimary = Color(0xFF2C0705),
    primaryContainer = Color(0xFF5C1A16),
    onPrimaryContainer = Color(0xFFFFDAD4),
    secondary = Color(0xFFE7BDB6),
    onSecondary = Color(0xFF442926),
    background = Color(0xFF100E0E),
    onBackground = Color(0xFFF6EEEC),
    surface = Color(0xFF1C1818),
    onSurface = Color(0xFFF6EEEC),
    surfaceVariant = Color(0xFF2C2626),
    onSurfaceVariant = Color(0xFFD4C6C3),
    outline = Color(0xFF9C8C89),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF9E2B24),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF775752),
    onSecondary = Color.White,
    background = Color(0xFFF8F3F2),
    onBackground = Color(0xFF1C1413),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF1C1413),
    surfaceVariant = Color(0xFFF3DDD9),
    onSurfaceVariant = Color(0xFF534341),
    outline = Color(0xFF857370),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

@Composable
fun RingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

@Composable
fun monoStyle(): TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
