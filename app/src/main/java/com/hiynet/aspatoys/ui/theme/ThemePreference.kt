package com.hiynet.aspatoys.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.data.settings.ThemeMode

@Composable
fun rememberBackgroundImage(settings: AppSettings): State<String> {
    val image = remember(settings) { mutableStateOf(settings.backgroundImage) }
    DisposableEffect(settings) {
        val unsubscribe = settings.observeBackgroundImage { image.value = it }
        image.value = settings.backgroundImage
        onDispose { unsubscribe() }
    }
    return image
}

/** Observes only appearance preferences; data-controller reload callbacks stay untouched. */
@Composable
fun rememberThemeMode(settings: AppSettings): State<ThemeMode> {
    val mode = remember(settings) { mutableStateOf(settings.themeMode) }
    DisposableEffect(settings) {
        val unsubscribe = settings.observeThemeMode { mode.value = it }
        mode.value = settings.themeMode
        onDispose { unsubscribe() }
    }
    return mode
}
