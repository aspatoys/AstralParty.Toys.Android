package com.hiynet.aspatoys

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hiynet.aspatoys.installer.ShizukuShell
import com.hiynet.aspatoys.ui.ToysApp
import com.hiynet.aspatoys.ui.theme.AstralPartyToysTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 平板默认横屏：管理器的列表/详情、加载器状态都更适合横向。
        // 手机仍跟随系统旋转，不影响竖屏使用。
        if (resources.configuration.smallestScreenWidthDp >= 600) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        enableEdgeToEdge()
        ShizukuShell.init(applicationContext)
        setContent {
            AstralPartyToysTheme {
                ToysApp()
            }
        }
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
