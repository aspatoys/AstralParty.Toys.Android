package com.hiynet.aspatoys.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 一次性的轻提示（启用、停用、保存这类很快、做完就完的操作）。
 *
 * 和系统 Toast 的区别：画在 App 里面、悬浮导航栏上方，可以带一个操作按钮（例如「撤销」），
 * 停留时间按字数算——和 Win 版 toast 一样 `字数 × 90ms`，限制在 3.8～10 秒，长句子来得及读完。
 * 新提示直接替换旧的，不排队。
 *
 * 多步骤、要等的操作不走这里，用 [Operations] 的进度面板。
 * 注意进度面板是单独的窗口，会盖住这里的提示，面板里的反馈别用它。
 */
object Snackbars {

    val host = SnackbarHostState()

    private val scope = MainScope()
    private var current: Job? = null

    /** 可以在任何线程调用。 */
    fun show(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        if (text.isBlank()) return
        scope.launch {
            // 取消上一条的协程，SnackbarHostState 会把它立刻收起。
            current?.cancel()
            current = launch {
                val timer = launch {
                    delay(durationMillis(text))
                    host.currentSnackbarData?.dismiss()
                }
                val result = host.showSnackbar(
                    message = text,
                    actionLabel = actionLabel,
                    duration = SnackbarDuration.Indefinite,
                )
                timer.cancel()
                if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
            }
        }
    }

    /** 和 Win 版 `TOAST_REWRITES` 那边一致：字数 × 90ms，3.8～10 秒。 */
    internal fun durationMillis(text: String): Long = (text.length * 90L).coerceIn(3_800L, 10_000L)
}

/** 挂在 App 外壳上；竖屏放在悬浮导航栏上方，横屏放在内容区底部。 */
@Composable
fun ToysSnackbarHost(modifier: Modifier = Modifier) {
    SnackbarHost(hostState = Snackbars.host, modifier = modifier)
}
