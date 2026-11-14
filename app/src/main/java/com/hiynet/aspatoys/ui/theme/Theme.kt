package com.hiynet.aspatoys.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 品牌配色。不跟随系统动态取色：动态色会把主题换成壁纸的调子，
 * 与启动图标、导航图标对不上，看着像两个应用拼起来的。
 */
private val LightColorScheme = lightColorScheme(
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
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = InkVariant,
    outline = InkVariant,
)

private val DarkColorScheme = darkColorScheme(
    primary = RaspberryLight,
    onPrimary = Color(0xFF64122F),
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
    onBackground = Color(0xFFF0DEE1),
    surface = Night,
    onSurface = Color(0xFFF0DEE1),
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = Color(0xFFD6C2C5),
    outline = Color(0xFF9E8C8F),
)

@Composable
fun AstralPartyToysTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
