package com.hiynet.aspatoys.ui.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 轻提示。
 *
 * 只用来回报「刚刚那一下做成了/没做成」——启用、停用、安装这类一次性动作；
 * 需要用户读一长段原因的错误仍然走页面里的横幅。
 */
object Toasts {

    private val main = Handler(Looper.getMainLooper())

    fun show(context: Context, text: String) {
        if (text.isBlank()) return
        main.post {
            runCatching { Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show() }
        }
    }
}
