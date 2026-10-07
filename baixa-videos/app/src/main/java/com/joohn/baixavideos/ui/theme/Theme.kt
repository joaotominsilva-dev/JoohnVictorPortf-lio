package com.joohn.baixavideos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.joohn.baixavideos.R

/** Paleta do portfólio JOOHN: papel, tinta, azul de interface e o vermelho do "REC". */
@Immutable
data class BrandColors(
    val paper: Color,
    val panel: Color,
    val panelStrong: Color,
    val ink: Color,
    val inkSoft: Color,
    val line: Color,
    val lineDark: Color,
    val blue: Color,
    val blueBright: Color,
    val rec: Color,
    val gold: Color,
    val green: Color,
    val strip: Color,
    val onStrip: Color,
)

private val LightBrand = BrandColors(
    paper = Color(0xFFF2EEE4),
    panel = Color(0xFFEDE7D8),
    panelStrong = Color(0xFFE7E0CF),
    ink = Color(0xFF22221E),
    inkSoft = Color(0xFF54534A),
    line = Color(0xFFC9BFA8),
    lineDark = Color(0xFFB0A78F),
    blue = Color(0xFF1B4F8C),
    blueBright = Color(0xFF2E6DB4),
    rec = Color(0xFFC0392B),
    gold = Color(0xFFB98A2E),
    green = Color(0xFF3E6B5E),
    strip = Color(0xFF1B4F8C),
    onStrip = Color(0xFFEAF1F8),
)

private val DarkBrand = BrandColors(
    paper = Color(0xFF161510),
    panel = Color(0xFF1E1C15),
    panelStrong = Color(0xFF26231B),
    ink = Color(0xFFECE7D8),
    inkSoft = Color(0xFFA7A290),
    line = Color(0xFF39362B),
    lineDark = Color(0xFF4B4737),
    blue = Color(0xFF5E93D6),
    blueBright = Color(0xFF82B2EE),
    rec = Color(0xFFE15A4C),
    gold = Color(0xFFCB9D41),
    green = Color(0xFF5F9485),
    strip = Color(0xFF173A63),
    onStrip = Color(0xFFEAF1F8),
)

private val LocalBrand = staticCompositionLocalOf { LightBrand }

object Brand {
    val colors: BrandColors
        @Composable @ReadOnlyComposable get() = LocalBrand.current
}

@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: Int, width: Float = 100f) = Font(
    R.font.archivo_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

val Archivo = FontFamily(archivo(500), archivo(600), archivo(700), archivo(800))
val ArchivoExpanded = FontFamily(archivo(800, width = 125f))
val CourierPrime = FontFamily(
    Font(R.font.courier_prime_regular, FontWeight.Normal),
    Font(R.font.courier_prime_bold, FontWeight.Bold),
)

/** Rótulos em fonte de máquina de escrever, como na interface do portfólio. */
val MonoLabel = TextStyle(fontFamily = CourierPrime, fontSize = 11.sp, letterSpacing = 1.2.sp)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontFamily = ArchivoExpanded, fontWeight = FontWeight.ExtraBold),
        titleLarge = base.titleLarge.copy(fontFamily = Archivo, fontWeight = FontWeight.ExtraBold),
        titleMedium = base.titleMedium.copy(fontFamily = Archivo, fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.copy(fontFamily = Archivo, fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontFamily = Archivo, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
    )
}

@Composable
fun BaixaVideosTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val brand = if (dark) DarkBrand else LightBrand
    val scheme = if (dark) {
        darkColorScheme(
            primary = brand.blue,
            onPrimary = Color(0xFF0B1E33),
            primaryContainer = Color(0xFF1F3A5C),
            onPrimaryContainer = Color(0xFFD5E4F7),
            secondary = brand.green,
            onSecondary = Color(0xFF0E1F1A),
            secondaryContainer = Color(0xFF1F3A5C),
            onSecondaryContainer = Color(0xFFD5E4F7),
            tertiary = brand.gold,
            error = brand.rec,
            onError = Color(0xFF2B0905),
            background = brand.paper,
            onBackground = brand.ink,
            surface = brand.paper,
            onSurface = brand.ink,
            surfaceVariant = brand.panel,
            onSurfaceVariant = brand.inkSoft,
            surfaceContainerLowest = brand.paper,
            surfaceContainerLow = brand.panel,
            surfaceContainer = brand.panel,
            surfaceContainerHigh = brand.panelStrong,
            surfaceContainerHighest = brand.panelStrong,
            outline = brand.lineDark,
            outlineVariant = brand.line,
        )
    } else {
        lightColorScheme(
            primary = brand.blue,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD5E2F2),
            onPrimaryContainer = Color(0xFF0E2C50),
            secondary = brand.green,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFD5E2F2),
            onSecondaryContainer = Color(0xFF0E2C50),
            tertiary = brand.gold,
            error = brand.rec,
            onError = Color.White,
            background = brand.paper,
            onBackground = brand.ink,
            surface = brand.paper,
            onSurface = brand.ink,
            surfaceVariant = brand.panel,
            onSurfaceVariant = brand.inkSoft,
            surfaceContainerLowest = Color(0xFFFAF7F0),
            surfaceContainerLow = brand.panel,
            surfaceContainer = brand.panel,
            surfaceContainerHigh = brand.panelStrong,
            surfaceContainerHighest = brand.panelStrong,
            outline = brand.lineDark,
            outlineVariant = brand.line,
        )
    }
    CompositionLocalProvider(LocalBrand provides brand) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
