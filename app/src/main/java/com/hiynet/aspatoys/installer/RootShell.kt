package com.hiynet.aspatoys.installer

import java.util.concurrent.TimeUnit

/**
 * Root 通道：直接通过 `su -c` 执行命令。
 *
 * 有 root 时不需要 Shizuku：目标仍然是游戏私有目录，但 `su` 直接给 uid 0，
 * 权限比 Shizuku 的 shell(uid 2000) 更高，读写、改包都不受限制。
 *
 * 探测只做一次并缓存，避免每次刷新都弹 root 授权框。
 */
object RootShell {

    @Volatile
    private var resolved = false

    @Volatile
    private var available = false

    /** 最近一次探测失败原因，给界面/日志用。 */
    @Volatile
    var lastError: String? = null
        private set

    fun isAvailable(): Boolean {
        if (!resolved) {
            synchronized(this) {
                if (!resolved) detect()
            }
        }
        return available
    }

    fun modeName(): String = if (isAvailable()) "Root" else "Shizuku"

    /** 设置页切回自动/重新检测时调用。 */
    fun reset() {
        synchronized(this) {
            resolved = false
            available = false
            lastError = null
        }
    }

    private fun detect() {
        resolved = true
        available = try {
            val process = ProcessBuilder("su", "-c", "id").start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                lastError = "su 探测超时"
                false
            } else {
                val text = (out + "\n" + err).trim()
                if (process.exitValue() == 0 && text.contains("uid=0")) {
                    true
                } else {
                    lastError = "su 返回非 root：${text.take(200)}"
                    false
                }
            }
        } catch (error: Throwable) {
            lastError = "没有可用的 su：${error.message ?: error}"
            false
        }
    }

    fun start(script: String): Process {
        check(isAvailable()) { "Root 不可用：${lastError ?: "su 不存在"}" }
        return ProcessBuilder("su", "-c", script).start()
    }

    /** 转义成单引号包裹的 shell 字面量。 */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
