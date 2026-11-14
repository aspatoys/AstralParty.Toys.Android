package com.hiynet.aspatoys.data.loader

import com.hiynet.aspatoys.installer.ShizukuShell

/**
 * 加载器目录的读写能力。
 *
 * 加载器根目录在**游戏的私有目录**里（`/sdcard/Android/data/<游戏包名>/files/CesiumLoader`），
 * 普通应用直接碰不到 —— 所以真实实现只能经 Shizuku 以 `shell` 身份执行命令。
 * 抽成接口是为了让单元测试可以用内存实现，也让「以后要不要支持 root」不影响上层。
 */
interface LoaderFs {

    fun isAvailable(): Boolean

    fun listDirectories(path: String): List<String>

    fun listFiles(path: String): List<String>

    fun exists(path: String): Boolean

    fun readText(path: String): String?

    fun writeText(path: String, text: String)

    fun readBytes(path: String): ByteArray?

    fun move(from: String, to: String)

    fun delete(path: String)

    fun makeDirectories(path: String)

    /** 把本地文件复制到设备路径（`cat local > remote`）。 */
    fun copyIn(localFile: java.io.File, remotePath: String)

    /** 最近一次失败原因；成功时为空。界面用它说明「为什么读不到」。 */
    val lastError: String?
}

/** 经 Shizuku 执行 shell 的实现；所有路径都按单引号字面量转义。 */
class ShizukuLoaderFs : LoaderFs {

    override fun isAvailable(): Boolean = ShizukuShell.isServiceAlive() && ShizukuShell.hasPermission()

    override fun listDirectories(path: String): List<String> =
        lines("ls -p " + q(path) + " 2>/dev/null | grep '/$' | sed 's#/$##'")

    override fun listFiles(path: String): List<String> =
        lines("ls -p " + q(path) + " 2>/dev/null | grep -v '/$'")

    override fun exists(path: String): Boolean =
        run("test -e " + q(path) + " && echo yes")?.trim() == "yes"

    override fun readText(path: String): String? = run("cat " + q(path))

    override fun writeText(path: String, text: String) {
        // 走一次中转文件：shell 的 heredoc 里带引号的正文不好转义，直接用本地文件更稳。
        val local = java.io.File.createTempFile("cesium-write", ".tmp")
        try {
            local.writeText(text, Charsets.UTF_8)
            ShizukuShell.exec("cat " + q(local.absolutePath) + " > " + q(path))
        } finally {
            local.delete()
        }
    }

    override fun readBytes(path: String): ByteArray? =
        runCatching {
            val local = java.io.File.createTempFile("cesium-read", ".bin")
            try {
                ShizukuShell.exec("cat " + q(path) + " > " + q(local.absolutePath))
                local.readBytes()
            } finally {
                local.delete()
            }
        }.getOrNull()

    override fun move(from: String, to: String) {
        ShizukuShell.exec("mv " + q(from) + " " + q(to))
    }

    override fun delete(path: String) {
        ShizukuShell.exec("rm -rf " + q(path))
    }

    override fun makeDirectories(path: String) {
        ShizukuShell.exec("mkdir -p " + q(path))
    }

    override fun copyIn(localFile: java.io.File, remotePath: String) {
        ShizukuShell.exec("cat " + q(localFile.absolutePath) + " > " + q(remotePath))
    }

    override var lastError: String? = null
        private set

    /**
     * 统一成 `/sdcard/...`：`Environment.getExternalStorageDirectory()` 给的是
     * `/storage/emulated/0/...`，两者指向同一处，但 shell 侧走 `/sdcard` 更稳。
     */
    private fun q(path: String): String {
        val normalized = when {
            path.startsWith("/storage/emulated/0/") -> "/sdcard/" + path.removePrefix("/storage/emulated/0/")
            path == "/storage/emulated/0" -> "/sdcard"
            else -> path
        }
        return ShizukuShell.quote(normalized)
    }

    private fun lines(script: String): List<String> {
        val output = run(script)
        val list = output?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()
        if (list.isEmpty()) {
            // 空结果也记一笔：区分「真的没有」和「命令跑空」。
            android.util.Log.i("CesiumLoaderFs", "empty ← $script")
        }
        return list
    }

    /**
     * 统一执行入口：失败时记下原因并写一条 logcat（`adb logcat -s CesiumLoaderFs` 就能看到，
     * 不用靠界面提示来猜）。
     */
    private fun run(script: String): String? {
        if (!isAvailable()) {
            lastError = "Shizuku 不可用（服务 ${ShizukuShell.isServiceAlive()}，授权 ${ShizukuShell.hasPermission()}）"
            android.util.Log.w("CesiumLoaderFs", lastError!!)
            return null
        }
        return try {
            ShizukuShell.exec(script)
        } catch (error: Throwable) {
            lastError = "${error.message ?: error} ← $script"
            android.util.Log.w("CesiumLoaderFs", lastError!!)
            null
        }
    }
}
