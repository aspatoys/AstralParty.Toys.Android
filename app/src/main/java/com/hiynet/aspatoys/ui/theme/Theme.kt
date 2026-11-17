package com.hiynet.aspatoys.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 备用浅色配色（系统低于 Android 12 或不支持动态配色时使用）。 */
internal val LightColorScheme = lightColorScheme(
    primary = Raspberry,
    onPrimary = Color.White,
    primaryContainer = RaspberryContainer,
    onPrimaryContainer = OnRaspberryContainer,
    secondary = Denim,
    onSecondary = Color.White,
    secondaryContainer = DenimContainer,
    onSecondaryContainer = OnDenimContainer,
    tertiary = Gold,
    onTertiary = Color.White,
    tertiaryContainer = GoldContainer,
    onTertiaryContainer = OnGoldContainer,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = InkVariant,
    outline = Color(0xFF85899B),
    outlineVariant = Color(0xFFDADCE6),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xEDFFFFFF),
    surfaceContainer = Color(0xFFF1F0F6),
    surfaceContainerHigh = Color(0xFFE9E8F0),
    surfaceContainerHighest = Color(0xFFE3E3ED),
    surfaceTint = Color.Transparent,
)

/** 备用深色配色。 */
internal val DarkColorScheme = darkColorScheme(
    primary = RaspberryLight,
    onPrimary = Color(0xFF300014),
    primaryContainer = RaspberryContainerDark,
    onPrimaryContainer = RaspberryContainer,
    secondary = DenimLight,
    onSecondary = Color(0xFF132048),
    secondaryContainer = DenimContainerDark,
    onSecondaryContainer = DenimContainer,
    tertiary = GoldLight,
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = GoldContainerDark,
    onTertiaryContainer = GoldContainer,
    background = Night,
    onBackground = Color(0xFFF3F5FA),
    surface = Color(0xFF1A1D2E),
    onSurface = Color(0xFFF3F5FA),
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = Color(0xFFC6CDDB),
    outline = Color(0xFF8B92AA),
    outlineVariant = Color(0xFF3A3F55),
    surfaceContainerLowest = Color(0xFF0D0F18),
    surfaceContainerLow = Color(0xD91A1D2E),
    surfaceContainer = Color(0xFF20243A),
    surfaceContainerHigh = Color(0xFF282D45),
    surfaceContainerHighest = Color(0xFF323851),
    surfaceTint = Color.Transparent,
)

private val ToysShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * Android 原生主题：
 * 默认开启 Material You 动态配色（基于用户壁纸生成调色板），
 * 低于 Android 12 (API 31) 时回退至经典配色。
 */
@Composable
fun AstralPartyToysTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ToysShapes,
        content = content,
    )
}
