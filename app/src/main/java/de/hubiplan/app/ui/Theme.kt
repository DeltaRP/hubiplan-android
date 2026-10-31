package de.hubiplan.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import de.hubiplan.app.data.Theme

/**
 * Farbschema aus den Vereinsfarben in HUBIplan (Primär- und Akzentfarbe, mit Vererbung
 * Zug ← Verein ← Dachverband). Die dynamischen Systemfarben von Android werden bewusst
 * nicht genutzt, damit die Vereinsfarben bleiben (Konzept, Ralf 30.10.).
 */
fun colorSchemeFor(theme: Theme?, dark: Boolean): ColorScheme {
    val primary = parseHexColor(theme?.primary) ?: DEFAULT_PRIMARY
    val onPrimary = parseHexColor(theme?.onPrimary) ?: WHITE
    val accent = parseHexColor(theme?.accent) ?: DEFAULT_ACCENT
    val onAccent = parseHexColor(theme?.onAccent) ?: BLACK
    return if (!dark) {
        // Primärfarbe dient in Material als Text- und Rahmenfarbe (Textknöpfe, aktive Felder,
        // Schalter): bei hellen Vereinsfarben abdunkeln (Barrierefreiheit A-03). Die
        // unveränderte Vereinsfarbe bleibt für die Kopfleiste (LocalBrand).
        val uiPrimary = withContrast(primary, listOf(WHITE, LIGHT_SURFACE), BLACK)
        lightColorScheme(
            primary = Color(uiPrimary),
            onPrimary = Color(if (uiPrimary == primary) onPrimary else readableOn(uiPrimary)),
            primaryContainer = Color(mix(primary, WHITE, 0.85f)),
            onPrimaryContainer = Color(mix(primary, BLACK, 0.5f)),
            secondary = Color(accent),
            onSecondary = Color(onAccent),
            secondaryContainer = Color(mix(accent, WHITE, 0.8f)),
            onSecondaryContainer = Color(mix(accent, BLACK, 0.6f)),
            tertiary = Color(accent),
            onTertiary = Color(onAccent),
        )
    } else {
        // Im Dunkeln hellere Töne derselben Farben, damit Text lesbar bleibt
        val lightPrimary = withContrast(mix(primary, WHITE, 0.45f), listOf(DARK_SURFACE), WHITE)
        val lightAccent = mix(accent, WHITE, 0.3f)
        darkColorScheme(
            primary = Color(lightPrimary),
            onPrimary = Color(mix(primary, BLACK, 0.6f)),
            primaryContainer = Color(mix(primary, BLACK, 0.35f)),
            onPrimaryContainer = Color(mix(primary, WHITE, 0.85f)),
            secondary = Color(lightAccent),
            onSecondary = Color(mix(accent, BLACK, 0.7f)),
            secondaryContainer = Color(mix(accent, BLACK, 0.5f)),
            onSecondaryContainer = Color(mix(accent, WHITE, 0.85f)),
            tertiary = Color(lightAccent),
            onTertiary = Color(mix(accent, BLACK, 0.7f)),
        )
    }
}

/** Unveränderte Vereinsfarbe für farbige Flächen (Kopfleiste) samt lesbarer Schrift. */
data class BrandColors(val brand: Color, val onBrand: Color)

val LocalBrand = staticCompositionLocalOf { BrandColors(Color(DEFAULT_PRIMARY), Color(WHITE)) }

fun brandFor(theme: Theme?): BrandColors {
    val primary = parseHexColor(theme?.primary) ?: DEFAULT_PRIMARY
    val onPrimary = parseHexColor(theme?.onPrimary)?.takeIf { contrast(it, primary) >= 4.5 } ?: readableOn(primary)
    return BrandColors(Color(primary), Color(onPrimary))
}

@Composable
fun HubiplanTheme(theme: Theme?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBrand provides brandFor(theme)) {
        MaterialTheme(colorScheme = colorSchemeFor(theme, isSystemInDarkTheme()), content = content)
    }
}
