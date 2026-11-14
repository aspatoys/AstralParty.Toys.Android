package com.hiynet.aspatoys.installer

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

/**
 * Shizuku 通道。
 *
 * 安装器要写游戏的私有目录 `/sdcard/Android/data/<游戏包名>/…`：这个位置对自己以外的应用
 * 一律不可见（Android 11+ 把别的应用的 `Android/data` 排除在存储权限之外），
 * 所以只能借 Shizuku 以 `shell`(uid 2000) 身份执行命令 —— 与 `adb shell` 同级。
 *
 * 这里只做三件事：探测服务、申请权限、跑一段 `sh -c`。
 */
object ShizukuShell {

    /** 与 MainActivity.onRequestPermissionsResult 里判断的值保持一致。 */
    const val PERMISSION_REQUEST_CODE = 4711

    /** 权限状态变化时自增，UI 观察它来刷新。 */
    var permissionVersion by mutableIntStateOf(0)
        private set

    private var initialized = false

    /** 启动时调用一次：注册授权结果回调。 */
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (initialized) return
        initialized = true
        runCatching {
            Shizuku.addRequestPermissionResultListener { _, _ -> onPermissionResult() }
        }
    }

    fun isServiceAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * 等 binder 就绪。
     *
     * Shizuku 的 binder 由 `ShizukuProvider` 在进程启动时异步接上，界面第一次组合时
     * `pingBinder()` 可能还是 false；这里轮询一小会儿，避免把「还没连上」当成「没有 Shizuku」。
     */
    fun awaitBinder(timeoutMs: Long = 3000): Boolean {
        if (isServiceAlive()) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100) } catch (_: InterruptedException) { return false }
            if (isServiceAlive()) return true
        }
        return false
    }

    fun hasPermission(): Boolean {
        if (!isServiceAlive()) return false
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun requestPermission() {
        if (!isServiceAlive()) return
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    /** 由 Activity 的回调转发进来。 */
    fun onPermissionResult() {
        permissionVersion++
    }

    /**
     * 执行一段 `sh` 脚本并返回标准输出；退出码非零时抛出带 stderr 的异常。
     *
     * 调用方要放在 IO 线程上（会阻塞等待进程结束）。
     */
    fun exec(script: String): String {
        check(isServiceAlive()) { "Shizuku 没有运行" }
        check(hasPermission()) { "还没拿到 Shizuku 权限" }

        val method = newProcessMethod ?: throw IllegalStateException("这个 Shizuku 版本没有 newProcess，无法执行命令")
        val process = method.invoke(null, arrayOf("sh", "-c", script), null, null) as? Process
            ?: throw IllegalStateException("Shizuku 起不了 shell")

        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0) {
            throw IllegalStateException(
                "命令失败（退出码 $code）：" + err.ifBlank { out }.trim().take(400),
            )
        }
        return out
    }

    /**
     * `Shizuku.newProcess` 在 13.1.5 的公开 AAR 里是 private（只有 shell 通道用得到），
     * 但 `ShizukuRemoteProcess` 是 public 且继承 [Process]，所以按方法签名反射取。
     */
    private val newProcessMethod: Method? by lazy {
        runCatching {
            Shizuku::class.java
                .getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                .apply { isAccessible = true }
        }.getOrNull()
    }

    /** 转义成单引号包裹的 shell 字面量。 */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
