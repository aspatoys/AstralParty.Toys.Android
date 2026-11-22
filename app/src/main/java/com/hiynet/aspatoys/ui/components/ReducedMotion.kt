package com.hiynet.aspatoys.ui.components

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 用户是否在系统里关掉了动画（开发者选项 / 无障碍里的「移除动画」，都会把动画时长缩放设成 0）。
 *
 * 为 true 时，我们自己写的位移、缩放、呼吸、展开动画都换成直接切换（照 Win 版 `reduce-motion` 的做法）；
 * 颜色、文字这些帮助理解状态的变化照常。
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
