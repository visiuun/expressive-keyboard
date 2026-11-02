package com.vis.expressivekeyboard.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.vis.expressivekeyboard.data.Prefs

data class ThemeSpec(val id: String, val name: String, val seed: Color?, val style: PaletteStyle = PaletteStyle.TonalSpot)

object Themes {
    val all = listOf(
        ThemeSpec("dynamic", "Wallpaper", null),
        ThemeSpec("custom", "Custom", Color(0xFF6750A4), PaletteStyle.Expressive),
        ThemeSpec("lavender", "Lavender", Color(0xFF6750A4), PaletteStyle.TonalSpot),
        ThemeSpec("ocean", "Ocean", Color(0xFF0061A4), PaletteStyle.TonalSpot),
        ThemeSpec("forest", "Forest", Color(0xFF386A20), PaletteStyle.TonalSpot),
        ThemeSpec("mint", "Mint", Color(0xFF00897B), PaletteStyle.Vibrant),
        ThemeSpec("sunset", "Sunset", Color(0xFFFF6E40), PaletteStyle.Vibrant),
        ThemeSpec("rose", "Rose", Color(0xFFB4235B), PaletteStyle.Expressive),
        ThemeSpec("cherry", "Cherry", Color(0xFFD32F2F), PaletteStyle.Vibrant),
        ThemeSpec("sand", "Sand", Color(0xFF8B5000), PaletteStyle.TonalSpot),
        ThemeSpec("citrus", "Citrus", Color(0xFFC0CA33), PaletteStyle.Expressive),
        ThemeSpec("electric", "Electric", Color(0xFF3D5AFE), PaletteStyle.Vibrant),
        ThemeSpec("candy", "Candy", Color(0xFFE040FB), PaletteStyle.FruitSalad),
        ThemeSpec("rainbow", "Prism", Color(0xFF00BCD4), PaletteStyle.Rainbow),
        ThemeSpec("graphite", "Graphite", Color(0xFF607D8B), PaletteStyle.Monochrome),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all[2]
}

@Immutable
data class KeyColors(
    val background: Color,
    val key: Color,
    val keyText: Color,
    val func: Color,
    val funcText: Color,
    val accent: Color,
    val accentText: Color,
    val pressed: Color,
    val popup: Color,
    val popupText: Color,
    val hint: Color,
    val stripText: Color,
    val trail: Color,
    val borders: Boolean,
)

val LocalKeyColors = staticCompositionLocalOf<KeyColors> { error("no key colors") }

@Composable
fun isDarkMode(): Boolean = when (Prefs.darkMode.value) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun rememberScheme(themeId: String, dark: Boolean, oled: Boolean): ColorScheme {
    val ctx = LocalContext.current
    val seed = Prefs.customSeed.value
    val style = Prefs.customStyle.value
    return remember(themeId, dark, oled, seed, style) { buildScheme(ctx, themeId, dark, oled) }
}

val CUSTOM_STYLES = listOf("TonalSpot", "Vibrant", "Expressive", "Rainbow", "FruitSalad", "Fidelity", "Monochrome", "Neutral")

fun buildScheme(ctx: android.content.Context, themeId: String, dark: Boolean, oled: Boolean): ColorScheme {
    var spec = Themes.all.firstOrNull { it.id == themeId } ?: Themes.all[2]
    if (spec.id == "custom") {
        val style = runCatching { PaletteStyle.valueOf(Prefs.customStyle.value) }.getOrDefault(PaletteStyle.Expressive)
        spec = spec.copy(seed = Color(Prefs.customSeed.value), style = style)
    }
    val base = if (spec.seed == null) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else {
            dynamicColorScheme(seedColor = Color(0xFF6750A4), isDark = dark, isAmoled = false, style = PaletteStyle.TonalSpot)
        }
    } else {
        dynamicColorScheme(seedColor = spec.seed, isDark = dark, isAmoled = false, style = spec.style)
    }
    return if (dark && oled) base.toOled() else base
}

/** Pure-black surfaces for OLED panels while keeping tonal accents. */
fun ColorScheme.toOled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = lerp(Color.Black, surfaceContainerLow, 0.35f),
    surfaceContainer = lerp(Color.Black, surfaceContainer, 0.45f),
    surfaceContainerHigh = lerp(Color.Black, surfaceContainerHigh, 0.55f),
    surfaceContainerHighest = lerp(Color.Black, surfaceContainerHighest, 0.65f),
    surfaceBright = lerp(Color.Black, surfaceBright, 0.7f),
)

fun keyColors(s: ColorScheme, dark: Boolean, oled: Boolean, borders: Boolean): KeyColors {
    val bg: Color
    var key: Color
    var func: Color
    when {
        dark && oled -> {
            bg = Color.Black
            key = lerp(Color(0xFF161616), s.primary, 0.05f)
            func = lerp(Color.Black, s.secondaryContainer, 0.55f)
        }
        dark -> {
            bg = s.surfaceContainerLow
            key = s.surfaceContainerHighest
            func = s.secondaryContainer
        }
        else -> {
            bg = s.surfaceContainer
            key = s.surfaceContainerLowest
            func = s.secondaryContainer
        }
    }
    if (!borders) {
        key = Color.Transparent
        func = Color.Transparent
    }
    return KeyColors(
        background = bg,
        key = key,
        keyText = s.onSurface,
        func = func,
        funcText = if (borders) s.onSecondaryContainer else s.onSurfaceVariant,
        accent = s.primary,
        accentText = s.onPrimary,
        pressed = if (dark) lerp(s.surfaceContainerHighest, s.primary, 0.32f) else lerp(s.surfaceContainerHighest, s.primary, 0.22f),
        popup = if (dark && oled) lerp(Color(0xFF202020), s.primary, 0.08f) else s.surfaceContainerHighest,
        popupText = s.onSurface,
        hint = s.onSurfaceVariant.copy(alpha = 0.75f),
        stripText = s.onSurface,
        trail = s.primary,
        borders = borders,
    )
}

@Composable
fun KeyboardTheme(content: @Composable () -> Unit) {
    val dark = isDarkMode()
    val oled = Prefs.oled.value
    val scheme = rememberScheme(Prefs.theme.value, dark, oled)
    val borders = Prefs.keyBorders.value
    val kc = remember(scheme, dark, oled, borders) { keyColors(scheme, dark, oled, borders) }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive()) {
        CompositionLocalProvider(LocalKeyColors provides kc) { content() }
    }
}
