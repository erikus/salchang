package dev.estaab.salchang.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/** Background behind the terminal view; matches the emulator's default black. */
val TerminalBackground: Color = Color(0xFF000000)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    secondary = Color(0xFFB0C6FF),
    tertiary = Color(0xFF7FD8A8),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1E2329),
    error = Color(0xFFFFB4AB),
)

/** Monospace body style for JSON, fingerprints and public keys. */
val MonoTextStyle: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

@Composable
fun SalchangTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = Typography(),
        content = content,
    )
}
