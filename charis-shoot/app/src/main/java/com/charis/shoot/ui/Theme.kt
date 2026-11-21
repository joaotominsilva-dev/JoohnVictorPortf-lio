package com.charis.shoot.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.charis.shoot.R

/** Identidade visual Charis. */
object Charis {
    val Creme = Color(0xFFF3F4EA)
    val Salvia = Color(0xFF9A9B89)
    val SalviaEscuro = Color(0xFF5E604F)
    val Grafite = Color(0xFF20211C)

    // Cores funcionais (rótulos e avisos de culling), iguais nos dois modos.
    val Vermelho = Color(0xFFD9534F)
    val Amarelo = Color(0xFFE8C547)
    val Verde = Color(0xFF6BAF6B)
    val Azul = Color(0xFF4A90D9)
    val Roxo = Color(0xFF9B6BD9)
    val Aviso = Color(0xFFE0A84F)
}

val InterCharis = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val DarkScheme = darkColorScheme(
    primary = Charis.Salvia,
    onPrimary = Charis.Grafite,
    primaryContainer = Charis.SalviaEscuro,
    onPrimaryContainer = Charis.Creme,
    secondary = Charis.Salvia,
    onSecondary = Charis.Grafite,
    secondaryContainer = Color(0xFF3A3B33),
    onSecondaryContainer = Charis.Creme,
    tertiary = Charis.Creme,
    onTertiary = Charis.Grafite,
    background = Charis.Grafite,
    onBackground = Charis.Creme,
    surface = Charis.Grafite,
    onSurface = Charis.Creme,
    surfaceVariant = Color(0xFF2E2F28),
    onSurfaceVariant = Color(0xFFC9CABB),
    surfaceContainerLowest = Color(0xFF1B1C17),
    surfaceContainerLow = Color(0xFF25261F),
    surfaceContainer = Color(0xFF292A24),
    surfaceContainerHigh = Color(0xFF31322B),
    surfaceContainerHighest = Color(0xFF3A3B33),
    outline = Charis.SalviaEscuro,
    outlineVariant = Color(0xFF3E3F36),
    inverseSurface = Charis.Creme,
    inverseOnSurface = Charis.Grafite,
    error = Color(0xFFE57373),
    onError = Charis.Grafite,
)

private val LightScheme = lightColorScheme(
    primary = Charis.SalviaEscuro,
    onPrimary = Charis.Creme,
    primaryContainer = Color(0xFFDADBCB),
    onPrimaryContainer = Charis.Grafite,
    secondary = Charis.Salvia,
    onSecondary = Charis.Grafite,
    secondaryContainer = Color(0xFFDDDED0),
    onSecondaryContainer = Charis.Grafite,
    tertiary = Charis.Grafite,
    onTertiary = Charis.Creme,
    background = Charis.Creme,
    onBackground = Charis.Grafite,
    surface = Charis.Creme,
    onSurface = Charis.Grafite,
    surfaceVariant = Color(0xFFE6E7DA),
    onSurfaceVariant = Color(0xFF4A4B40),
    surfaceContainerLowest = Color(0xFFFAFBF4),
    surfaceContainerLow = Color(0xFFF0F1E6),
    surfaceContainer = Color(0xFFEBECE0),
    surfaceContainerHigh = Color(0xFFE4E5D8),
    surfaceContainerHighest = Color(0xFFDDDED0),
    outline = Charis.Salvia,
    outlineVariant = Color(0xFFD3D4C4),
    inverseSurface = Charis.Grafite,
    inverseOnSurface = Charis.Creme,
    error = Color(0xFFB3423E),
    onError = Charis.Creme,
)

private fun TextStyle.inter() = copy(fontFamily = InterCharis)

private fun charisTypography(): Typography {
    val d = Typography()
    return Typography(
        displayLarge = d.displayLarge.inter(),
        displayMedium = d.displayMedium.inter(),
        displaySmall = d.displaySmall.inter(),
        headlineLarge = d.headlineLarge.inter(),
        headlineMedium = d.headlineMedium.inter(),
        headlineSmall = d.headlineSmall.inter(),
        titleLarge = d.titleLarge.inter().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = d.titleMedium.inter().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = d.titleSmall.inter(),
        bodyLarge = d.bodyLarge.inter(),
        bodyMedium = d.bodyMedium.inter(),
        bodySmall = d.bodySmall.inter(),
        labelLarge = d.labelLarge.inter(),
        labelMedium = d.labelMedium.inter(),
        labelSmall = d.labelSmall.inter(),
    )
}

private val CharisShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CharisTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = charisTypography(),
        shapes = CharisShapes,
        content = content,
    )
}
