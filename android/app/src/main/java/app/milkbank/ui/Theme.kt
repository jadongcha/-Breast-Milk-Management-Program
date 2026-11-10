package app.milkbank.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colors from design spec 3.8. */
object MilkbankColors {
    val Primary = Color(0xFF11695C)
    val PrimaryLight = Color(0xFFE2F1ED)
    val Background = Color(0xFFF4F8F7)
    val Border = Color(0xFFD5E2DE)
    val Text = Color(0xFF1D2A2E)
    val TextSub = Color(0xFF5B6B70)
    val Error = Color(0xFFC0392B)
    val Badge = Color(0xFFFCEBC7)
}

private val Scheme = lightColorScheme(
    primary = MilkbankColors.Primary,
    onPrimary = Color.White,
    primaryContainer = MilkbankColors.PrimaryLight,
    onPrimaryContainer = MilkbankColors.Text,
    tertiaryContainer = MilkbankColors.Badge,
    background = MilkbankColors.Background,
    onBackground = MilkbankColors.Text,
    surface = Color.White,
    onSurface = MilkbankColors.Text,
    onSurfaceVariant = MilkbankColors.TextSub,
    outline = MilkbankColors.Border,
    outlineVariant = MilkbankColors.Border,
    error = MilkbankColors.Error,
    onError = Color.White,
)

@Composable
fun MilkbankTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
