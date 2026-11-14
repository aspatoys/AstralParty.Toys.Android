package com.hiynet.aspatoys.installer

import java.util.concurrent.TimeUnit

/**
 * Root 通道：直接通过 `su -c` 执行命令。
 *
 * 有 root 时不需要 Shizuku：目标仍然是游戏私有目录，但 `su` 直接给 uid 0，
 * 权限比 Shizuku 的 shell(uid 2000) 更高，读写、改包都不受限制。
 *
 * 探测只做一次并缓存；探测失败也会记录原因，界面可点「重新检测提权」重试。
 */
object RootShell {

    @Volatile
    private var resolved = false

    @Volatile
    private var available = false

    @Volatile
    private var suPath: String? = null

    /** 最近一次探测失败原因，给界面/日志用。 */
    @Volatile
    var lastError: String? = null
        private set

    private val candidates = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/debug_ramdisk/su",
        "/sbin/su",
        "su",
    )

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
            suPath = null
            lastError = null
        }
    }

    private fun detect() {
        resolved = true
        lastError = null
        for (candidate in candidates) {
            val result = runCatching { probe(candidate) }.getOrElse { error ->
                lastError = "${candidate} 启动失败：${error.message ?: error}"
                null
            }
            if (result == true) {
                suPath = candidate
                available = true
                return
            }
        }
        available = false
        if (lastError == null) lastError = "没有找到可用的 su"
    }

    private fun probe(path: String): Boolean {
        val process = ProcessBuilder(path, "-c", "id").start()
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { out.append(it).append('\n') }
                }
            }
        }.apply { isDaemon = true; start() }
        val errThread = Thread {
            runCatching {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { err.append(it).append('\n') }
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            runCatching { process.destroy() }
            lastError = "$path 探测超时（可能等待 root 授权）"
            return false
        }
        outThread.join(1000)
        errThread.join(1000)
        val text = (out.toString() + "\n" + err.toString()).trim()
        if (process.exitValue() == 0 && text.contains("uid=0")) return true
        lastError = "$path 返回非 root：${text.take(200)}"
        return false
    }

    fun start(script: String): Process {
        check(isAvailable()) { "Root 不可用：${lastError ?: "su 不存在"}" }
        val path = suPath ?: "su"
        return ProcessBuilder(path, "-c", script).start()
    }

    /** 转义成单引号包裹的 shell 字面量。 */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
