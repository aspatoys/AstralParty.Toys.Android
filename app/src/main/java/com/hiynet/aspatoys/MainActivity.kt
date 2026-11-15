package com.hiynet.aspatoys

import android.os.Bundle
import android.content.res.Configuration
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.installer.ShizukuShell
import com.hiynet.aspatoys.ui.ToysApp
import com.hiynet.aspatoys.ui.theme.AstralPartyToysTheme
import com.hiynet.aspatoys.ui.theme.rememberThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 平板和手机都跟随系统旋转：竖屏用悬浮 Dock，横屏自动切 NavigationRail。
        val settings = AppSettings(applicationContext)
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        updateSystemBars(settings.themeMode.resolveDark(systemDark))
        ShizukuShell.init(applicationContext)
        ShizukuShell.setMode(settings.privilegeMode)
        setContent {
            val themeMode by rememberThemeMode(settings)
            val darkTheme = themeMode.resolveDark(isSystemInDarkTheme())
            SideEffect { updateSystemBars(darkTheme) }
            AstralPartyToysTheme(darkTheme = darkTheme) {
                ToysApp()
            }
        }
    }

    private fun updateSystemBars(darkTheme: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ShizukuShell.PERMISSION_REQUEST_CODE) {
            ShizukuShell.onPermissionResult()
        }
    }
}
