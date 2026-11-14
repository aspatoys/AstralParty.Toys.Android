package com.hiynet.aspatoys

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
        // 平板和手机都跟随系统旋转：竖屏用底部导航，横屏自动切 NavigationRail。
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
