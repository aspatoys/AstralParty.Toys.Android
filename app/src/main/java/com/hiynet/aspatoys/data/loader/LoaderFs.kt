package com.hiynet.aspatoys.data.loader

import com.hiynet.aspatoys.installer.LoaderOwnership
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

    /** 文件字节数；读不到时返回 0（不猜）。 */
    fun fileSize(path: String): Long

    /** 把本地文件复制到设备路径（`cat local > remote`）。 */
    fun copyIn(localFile: java.io.File, remotePath: String)

    /** 最近一次失败原因；成功时为空。界面用它说明「为什么读不到」。 */
    val lastError: String?
}

/** 经 Shizuku 执行 shell 的实现；所有路径都按单引号字面量转义。 */
class ShizukuLoaderFs(
    private val owner: LoaderOwnership = LoaderOwnership.default,
    private val exec: (String) -> String = { ShizukuShell.exec(it) },
    private val writeStream: (String, java.io.InputStream) -> Unit = { command, source -> ShizukuShell.writeStream(command, source) },
) : LoaderFs {

    override fun isAvailable(): Boolean = ShizukuShell.isServiceAlive() && ShizukuShell.hasPermission()

    override fun listDirectories(path: String): List<String> =
        lines("ls -p " + q(path) + " 2>/dev/null | grep '/$' | sed 's#/$##'; true")

    override fun listFiles(path: String): List<String> =
        lines("ls -p " + q(path) + " 2>/dev/null | grep -v '/$'; true")

    /** 路径不存在是常态（扫 disabled_mods、找 sidecar 都会问），结尾补 `; true` 别让它变成「命令失败」。 */
    override fun exists(path: String): Boolean =
        run("test -e " + q(path) + " && echo yes; true")?.trim() == "yes"

    /** 读不到（不存在、无权限）就返回 null；这类失败不该污染 [lastError]。 */
    override fun readText(path: String): String? = run("cat " + q(path) + " 2>/dev/null; true")

    override fun writeText(path: String, text: String) {
        // 走标准输入，不落中转文件：加载器目录在游戏私有目录里，中转文件放哪都可能读不到。
        text.byteInputStream(Charsets.UTF_8).use { writeAtomic(path, it) }
    }

    override fun readBytes(path: String): ByteArray? =
        runCatching { ShizukuShell.execBytes("cat " + q(path) + " 2>/dev/null; true") }.getOrNull()

    override fun move(from: String, to: String) {
        owner.requireOwned(from, recursive = true)
        exec("mv " + q(from) + " " + q(to))
    }

    override fun delete(path: String) {
        ShizukuShell.exec("rm -rf " + q(path))
    }

    override fun makeDirectories(path: String) {
        exec("mkdir -p " + q(path))
        // root 建的目录属主是 root，游戏连遍历都不行；建完立刻改回游戏 uid。
        owner.requireDirectories(path)
    }

    /** 文件字节数；读不到时返回 0（不猜）。 */
    override fun fileSize(path: String): Long =
        run("stat -c %s " + q(path) + " 2>/dev/null; true")?.trim()?.toLongOrNull() ?: 0L

    override fun copyIn(localFile: java.io.File, remotePath: String) {
        // 同上：字节从本进程的标准输入推过去，不依赖 shell 能不能读到本地文件。
        localFile.inputStream().buffered().use { writeAtomic(remotePath, it) }
    }

    /**
     * 先写 `<path>.cesium-tmp` 再 `mv` 覆盖目标。
     *
     * `cat > path` 会**先创建/截断目标**再开始读输入，中途失败就留下一个 0 字节的假文件 ——
     * 模组 DLL 变成 0 字节后加载器照样会去加载它，然后每局报一次错。中转文件名带 `.cesium-tmp`
     * 后缀，不会和真实文件重名，失败时也不会污染目标。
     *
     * 代价是 `mv -f` 换成新 inode，属主变成执行者（Root 模式下就是 root），游戏就读不到了，
     * 暂存文件必须先修正并验证属主，再替换旧文件。shell(2000) 不能 chown，
     * 仅当挂载层已将文件归属游戏时才允许发布，否则保留旧文件并明确报错。
     */
    private fun writeAtomic(remotePath: String, source: java.io.InputStream) {
        val temp = "$remotePath.cesium-tmp"
        try {
            writeStream("cat > " + q(temp), source)
            owner.requireDirectories(remotePath.substringBeforeLast('/'))
            owner.requireOwned(temp)
            exec("mv -f " + q(temp) + " " + q(remotePath))
            owner.requireOwned(remotePath)
        } catch (error: Throwable) {
            runCatching { exec("rm -f " + q(temp)) }
            throw error
        }
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
     * 不用靠界面提示来猜）。成功时把上一次的错误清掉 —— 否则一次偶发失败会让界面永远挂着红条。
     */
    private fun run(script: String): String? {
        if (!isAvailable()) {
            lastError = "提权不可用（${ShizukuShell.modeName()}：服务 ${ShizukuShell.isServiceAlive()}，权限 ${ShizukuShell.hasPermission()}）"
            android.util.Log.w("CesiumLoaderFs", lastError!!)
            return null
        }
        return try {
            val output = ShizukuShell.exec(script)
            lastError = null
            output
        } catch (error: Throwable) {
            lastError = "${error.message ?: error} ← $script"
            android.util.Log.w("CesiumLoaderFs", lastError!!)
            null
        }
    }
}
