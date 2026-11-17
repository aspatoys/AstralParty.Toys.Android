package com.hiynet.aspatoys.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Exports the resolved Material 3 colour scheme for the in-game overlay.
 *
 * The overlay mod runs inside the game process and cannot call `dynamicLightColorScheme()` itself,
 * so the app resolves the scheme here and writes it to `data/toys-appearance.txt` as
 * `light.role=#AARRGGBB` / `dark.role=#AARRGGBB` lines. The mod parses those lines on every poll and
 * repaints when they change.
 *
 * Both schemes are exported on purpose: the mod keeps running while the user flips the system dark
 * toggle from quick settings, and the app cannot rewrite the file at that moment. With both sets
 * present the mod always has the right palette for whichever scheme it resolved. Older overlays
 * ignore unknown keys, so the file stays backward compatible.
 *
 * Only the colour is taken from the scheme; the alpha is the overlay's own design value, because the
 * surfaces are translucent by design and Compose schemes always resolve to fully opaque colours.
 */
internal object OverlayPalette {

    /** Schema version of the token block; bump when names or semantics change. */
    const val VERSION = 1

    private data class Token(val name: String, val darkAlpha: Float, val lightAlpha: Float)

    private val tokens = listOf(
        Token("surface", .92f, .94f),
        Token("surfaceContainer", .95f, .96f),
        Token("surfaceContainerHigh", .96f, .97f),
        Token("primary", 1f, 1f),
        Token("onPrimary", 1f, 1f),
        Token("primaryContainer", 1f, 1f),
        Token("onPrimaryContainer", 1f, 1f),
        Token("secondaryContainer", 1f, 1f),
        Token("tertiary", 1f, 1f),
        Token("onTertiary", 1f, 1f),
        Token("onSurface", 1f, 1f),
        Token("onSurfaceVariant", 1f, 1f),
        Token("outline", .5f, .5f),
        Token("outlineVariant", .7f, .7f),
        Token("scrim", .32f, .18f),
    )

    /** Resolves the same scheme [AstralPartyToysTheme] would use for [dark]. */
    internal fun scheme(context: Context, dark: Boolean): ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColorScheme
        else -> LightColorScheme
    }

    /** `light.role=#AARRGGBB` then `dark.role=#AARRGGBB` lines; empty when unavailable. */
    fun tokens(context: Context): String {
        val dark = runCatching { scheme(context, true) }.getOrNull() ?: return ""
        val light = runCatching { scheme(context, false) }.getOrNull() ?: return ""
        val builder = StringBuilder()
        append(builder, "light", light, lightScheme = true)
        append(builder, "dark", dark, lightScheme = false)
        return builder.toString()
    }

    private fun append(builder: StringBuilder, prefix: String, scheme: ColorScheme, lightScheme: Boolean) {
        for (token in tokens) {
            val colour = runCatching { role(scheme, token.name) }.getOrNull() ?: continue
            builder.append(prefix).append('.').append(token.name).append('=')
                .append(pack(colour, if (lightScheme) token.lightAlpha else token.darkAlpha))
                .append('\n')
        }
    }

    private fun role(scheme: ColorScheme, name: String): Color = when (name) {
        "surface" -> scheme.surface
        "surfaceContainer" -> scheme.surfaceContainer
        "surfaceContainerHigh" -> scheme.surfaceContainerHigh
        "primary" -> scheme.primary
        "onPrimary" -> scheme.onPrimary
        "primaryContainer" -> scheme.primaryContainer
        "onPrimaryContainer" -> scheme.onPrimaryContainer
        "secondaryContainer" -> scheme.secondaryContainer
        "tertiary" -> scheme.tertiary
        "onTertiary" -> scheme.onTertiary
        "onSurface" -> scheme.onSurface
        "onSurfaceVariant" -> scheme.onSurfaceVariant
        "outline" -> scheme.outline
        "outlineVariant" -> scheme.outlineVariant
        "scrim" -> scheme.scrim
        else -> error("unknown token: $name")
    }

    /** `#AARRGGBB`, matching the mod's `TryParse`. */
    private fun pack(colour: Color, alpha: Float): String {
        val argb = colour.copy(alpha = 1f).toArgb()
        val a = (alpha.coerceIn(0f, 1f) * 255f + .5f).toInt()
        return String.format(java.util.Locale.ROOT, "#%02X%06X", a, argb and 0xFFFFFF)
    }
}
