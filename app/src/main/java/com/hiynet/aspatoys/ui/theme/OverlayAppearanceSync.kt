package com.hiynet.aspatoys.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hiynet.aspatoys.data.loader.BuiltinOverlay
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.installer.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun OverlayAppearanceSync(settings: AppSettings) {
    val context = LocalContext.current.applicationContext
    val overlay = remember { BuiltinOverlay(context) }
    val mode by rememberThemeMode(settings)
    val systemDark = isSystemInDarkTheme()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumed++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val root = settings.loaderRootPath
    val permission = ShizukuShell.permissionVersion
    LaunchedEffect(mode, systemDark, root, permission, resumed) {
        withContext(Dispatchers.IO) {
            runCatching {
                val tokens = OverlayPalette.tokens(context)
                overlay.syncAppearance(root, mode, systemDark) { tokens }
                com.hiynet.aspatoys.data.settings.OverlayOptionsStore(context).sync(root)
            }
                .onFailure { android.util.Log.w("ToysOverlay", "主题同步失败，下次连接或安装时重试", it) }
        }
    }
}
