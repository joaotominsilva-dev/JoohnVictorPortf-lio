package com.joohn.baixavideos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.joohn.baixavideos.R

/**
 * Cores do logo Charis: o creme e a sálvia da marca, mais o grafite das telas.
 * O app tem um tema só: telas escuras com cartões claros, como na referência aprovada.
 */
object Charis {
    val Charcoal = Color(0xFF20211C)
    val Charcoal2 = Color(0xFF2C2D27)
    val Cream = Color(0xFFF3F4EA)
    val Cream2 = Color(0xFFE9EADD)
    val Sage = Color(0xFF9A9B89)
    val SageLight = Color(0xFFC8C9B7)
    val SageDeep = Color(0xFF5E604F)
    val Ink = Color(0xFF1D1E19)
    val InkSoft = Color(0xFF55564B)
    val OnDarkSoft = Color(0xFFA9AA98)
    val DarkLine = Color(0xFF34352E)
    val Danger = Color(0xFF953521)
    val DangerSoft = Color(0xFFF0DDD6)
}

@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: Int) = Font(
    R.font.archivo_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Archivo = FontFamily(archivo(400), archivo(500), archivo(600), archivo(700), archivo(800))

/** Rótulo pequeno em caixa alta dos cartões ("LINK", "QUALIDADE"). */
val CardLabelStyle = TextStyle(
    fontFamily = Archivo,
    fontWeight = FontWeight.Bold,
    fontSize = 10.sp,
    letterSpacing = 1.4.sp,
)

private val AppTypography = Typography().let { base ->
    fun TextStyle.archivo(weight: FontWeight) = copy(fontFamily = Archivo, fontWeight = weight)
    base.copy(
        displaySmall = base.displaySmall.archivo(FontWeight.ExtraBold),
        headlineLarge = base.headlineLarge.archivo(FontWeight.ExtraBold).copy(letterSpacing = (-0.6).sp),
        headlineMedium = base.headlineMedium.archivo(FontWeight.ExtraBold).copy(letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.archivo(FontWeight.ExtraBold).copy(letterSpacing = (-0.3).sp),
        titleLarge = base.titleLarge.archivo(FontWeight.ExtraBold),
        titleMedium = base.titleMedium.archivo(FontWeight.Bold),
        titleSmall = base.titleSmall.archivo(FontWeight.Bold),
        bodyLarge = base.bodyLarge.archivo(FontWeight.Normal),
        bodyMedium = base.bodyMedium.archivo(FontWeight.Normal),
        bodySmall = base.bodySmall.archivo(FontWeight.Normal),
        labelLarge = base.labelLarge.archivo(FontWeight.Bold),
        labelMedium = base.labelMedium.archivo(FontWeight.Bold),
        labelSmall = base.labelSmall.archivo(FontWeight.Bold),
    )
}

private val Scheme = darkColorScheme(
    primary = Charis.Sage,
    onPrimary = Charis.Charcoal,
    primaryContainer = Charis.SageDeep,
    onPrimaryContainer = Charis.Cream,
    inversePrimary = Charis.SageDeep,
    secondary = Charis.SageLight,
    onSecondary = Charis.Ink,
    secondaryContainer = Charis.Sage,
    onSecondaryContainer = Charis.Charcoal,
    tertiary = Charis.SageLight,
    onTertiary = Charis.Ink,
    background = Charis.Charcoal,
    onBackground = Charis.Cream,
    surface = Charis.Charcoal,
    onSurface = Charis.Cream,
    surfaceVariant = Charis.Charcoal2,
    onSurfaceVariant = Charis.OnDarkSoft,
    surfaceTint = Charis.Sage,
    surfaceContainerLowest = Charis.Charcoal,
    surfaceContainerLow = Charis.Charcoal2,
    surfaceContainer = Charis.Charcoal2,
    surfaceContainerHigh = Charis.Charcoal2,
    surfaceContainerHighest = Color(0xFF37382F),
    inverseSurface = Charis.Cream,
    inverseOnSurface = Charis.Ink,
    outline = Charis.Sage,
    outlineVariant = Charis.DarkLine,
    error = Color(0xFFE59A86),
    onError = Charis.Charcoal,
)

@Composable
fun CharisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
