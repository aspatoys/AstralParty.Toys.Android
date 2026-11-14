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

    /** 用户在加载器页选的模式：`shizuku` 或 `root`。 */
    @Volatile
    private var privilegeMode = "shizuku"

    /** 启动时调用一次：注册授权结果回调，并在后台探测 root。 */
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (initialized) return
        initialized = true
        runCatching {
            Shizuku.addRequestPermissionResultListener { _, _ -> onPermissionResult() }
        }
        if (privilegeMode != "shizuku") {
            Thread {
                RootShell.isAvailable()
            }.apply { isDaemon = true }.start()
        }
    }

    /** 由 MainActivity 在启动时把持久化的选择灌进来。 */
    fun setMode(mode: String) {
        privilegeMode = if (mode == "root") "root" else "shizuku"
        if (privilegeMode == "root") {
            RootShell.reset()
            Thread { RootShell.isAvailable() }.apply { isDaemon = true }.start()
        }
        permissionVersion++
    }

    fun selectedMode(): String = privilegeMode

    /** 当前实际使用的提权方式：Root 模式下有 su 才是 Root。 */
    fun modeName(): String = if (isRootMode()) "Root" else "Shizuku"

    fun isRootMode(): Boolean = privilegeMode == "root" && RootShell.isAvailable()

    /**
     * 是否已有可用的提权通道。
     *
     * 名字保留 `isServiceAlive` 是为了不动调用方；
     * Root 模式只认 root，Shizuku 模式只认 Shizuku。
     */
    fun isServiceAlive(): Boolean {
        return when (privilegeMode) {
            "root" -> RootShell.isAvailable()
            "shizuku" -> runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            else -> RootShell.isAvailable() || runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        }
    }

    /**
     * 等提权通道就绪。
     *
     * Root 直接可用；否则等 Shizuku 的 binder（它由 `ShizukuProvider` 异步接上）。
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
        if (privilegeMode == "root") return RootShell.isAvailable()
        if (!isServiceAlive()) return false
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun requestPermission() {
        if (privilegeMode == "root") return
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
    fun exec(script: String): String = execBytes(script).toString(Charsets.UTF_8)

    /**
     * 同上，但按字节返回。
     *
     * 读文件必须走这个入口：`bufferedReader().readText()` 会把二进制内容按 `UTF-8` 解坏，
     * 拿回来的字节数都不对。
     */
    fun execBytes(script: String): ByteArray {
        val process = start(script)
        val out = process.inputStream.use { it.readBytes() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0) {
            throw IllegalStateException(
                "命令失败（退出码 $code）：" + (err.ifBlank { "没有输出" }).trim().take(400),
            )
        }
        return out
    }

    /**
     * 把 [source] 的字节写进远端文件（`cat > <path>`，走子进程的标准输入）。
     *
     * 不能改用「本机中转文件 + `cat local > remote`」：App 私有目录里的文件是 `0600`、
     * 目录是 `0700`，以 `shell` 身份运行的 Shizuku 读不到，会直接失败。标准输入没有权限问题，
     * 也省了一份落盘。
     */
    fun writeStream(script: String, source: java.io.InputStream) {
        val process = start(script)
        // stderr 必须同时抽干：只写不读，管道写满后双方都会卡住。
        val stderr = StringBuilder()
        val drain = Thread {
            runCatching { process.errorStream.bufferedReader().use { stderr.append(it.readText()) } }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            process.outputStream.use { sink -> source.copyTo(sink, 64 * 1024) }
        } catch (error: Throwable) {
            runCatching { process.destroy() }
            throw IllegalStateException("写入通道中断：${error.message ?: error}")
        }
        val code = process.waitFor()
        drain.join(2000)
        if (code != 0) {
            throw IllegalStateException(
                "写入失败（退出码 $code）：" + stderr.toString().ifBlank { "没有输出" }.trim().take(400),
            )
        }
    }

    private fun start(script: String): Process {
        if (privilegeMode == "root" && RootShell.isAvailable()) {
            return RootShell.start(script)
        }
        check(isServiceAlive()) { if (privilegeMode == "root") "Root 不可用：${RootShell.lastError}" else "Shizuku 没有运行" }
        check(hasPermission()) { if (privilegeMode == "root") "Root 不可用：${RootShell.lastError}" else "还没拿到 Shizuku 权限" }

        val method = newProcessMethod ?: throw IllegalStateException("这个 Shizuku 版本没有 newProcess，无法执行命令")
        return method.invoke(null, arrayOf("sh", "-c", script), null, null) as? Process
            ?: throw IllegalStateException("Shizuku 起不了 shell")
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
