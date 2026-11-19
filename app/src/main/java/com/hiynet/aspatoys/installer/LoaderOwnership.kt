package com.hiynet.aspatoys.installer

import com.hiynet.cesiumloader.core.AndroidPaths

/**
 * 把加载器文件的属主改回游戏。
 *
 * 游戏的私有目录 `/sdcard/Android/data/<游戏包名>` 在模拟存储上按 **uid** 判权。
 * 加载器目录实际是 `drwxr-s--- u0_a331 ext_data_rw`：组只有 rx（没有 w），
 * 所以能进去读，但**建不了新文件**。而 `chmod` 在这个挂载点上一律返回
 * `Operation not permitted` —— 唯一能改的只有 `chown`，且只有 uid 0 能做。
 *
 * 为什么必须做这件事：注入进程序集的 `<Module>.cctor` 会
 * `File.ReadAllBytes(bootstrap)`，一旦属主是 root 而游戏是 `u0_a331`，这里就抛
 * `UnauthorizedAccessException`，又被 cctor 的 try/catch 吞掉 —— 表现为
 * 「装好了、游戏能开、就是没模组、一行日志都没有」。
 *
 * 能力判定不看用户选的模式，而看通道真正的 uid：用 root 启动的 Shizuku，
 * `newProcess` 跑在 uid 0，同样能 chown；用 adb 启动的 Shizuku 是 2000，做不到。
 */
class LoaderOwnership(
    private val gamePackage: String = LoaderInstaller.GAME_PACKAGE,
    private val exec: (String) -> String = { ShizukuShell.exec(it) },
) {

    /** 游戏在存储上的 uid / gid（gid 通常是 1078 = `ext_data_rw`）。 */
    data class Ids(val uid: Int, val gid: Int) {
        val spec: String get() = "$uid:$gid"
    }

    /** 最近一次失败原因；成功时为空。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 提权通道自己的 uid：0 = 能 chown，2000 = 只能写有权限的地方。 */
    fun channelUid(): Int? {
        val value = runCatching { exec("id -u").trim().toIntOrNull() }.getOrNull()
        return value
    }

    /** 这个通道能不能改属主。 */
    fun isSupported(): Boolean = channelUid() == 0

    /**
     * 读游戏 uid / gid。
     *
     * 直接从游戏的数据目录上取，不写死 —— uid 是装应用时分配的，换机器、重装都会变。
     * 取不到就返回 null，让调用方跳过而不是猜一个值去 chown。
     */
    fun ids(): Ids? {
        val output = runCatching { exec("stat -c '%u %g' " + ShizukuShell.quote(dataDir())) }.getOrNull()
        val parts = output?.trim()?.split(Regex("\\s+")).orEmpty()
        val uid = parts.getOrNull(0)?.toIntOrNull()
        val gid = parts.getOrNull(1)?.toIntOrNull()
        if (uid == null || uid % 100000 !in 10000..19999 || gid == null || gid < 0) {
            lastError = "读不到游戏 uid（$gamePackage）"
            return null
        }
        return Ids(uid, gid)
    }

    /** 把单个路径（文件或目录）的属主改回游戏。通道做不到时静默跳过。 */
    fun adopt(path: String): Boolean = adopt(listOf(path), recursive = false)

    /** 递归改整个目录树；加载器部署完之后用它兜底（也修掉旧版本留下的 root 文件）。 */
    fun adoptTree(root: String): Boolean = adopt(listOf(root), recursive = true)

    private fun adopt(paths: List<String>, recursive: Boolean): Boolean {
        if (paths.isEmpty() || !isSupported()) return false
        val ids = ids() ?: return false
        val command = buildString {
            append("chown ")
            if (recursive) append("-R ")
            append(ids.spec).append(' ')
            append(paths.joinToString(" ") { ShizukuShell.quote(it) })
        }
        return try {
            exec(command)
            lastError = null
            true
        } catch (error: Throwable) {
            // 保留诊断；写入调用方必须通过 requireOwned 阻止误报成功。
            // 这里刻意不调 android.util.Log —— 纯逻辑要能在 JVM 单测里跑。
            lastError = "${error.message ?: error} ← $command"
            false
        }
    }

    /**
     * 装完自检：这些路径的属主是否都等于游戏 uid。
     *
     * 只有这里发现问题才值得让用户看见 —— 属主不对等于加载器永远静默失效，
     * 与其让他反复点安装，不如直接说清楚要 root。
     *
     * @return 有问题时返回一句可读的说明；全部正常返回 null。
     */
    fun verify(paths: List<String>): String? {
        if (!isSupported()) return verifyAccess(paths)
        val ids = ids() ?: return if (isSupported()) {
            "读不到游戏 uid，无法确认加载器可读"
        } else {
            "提权通道是 uid ${channelUid() ?: "?"}，不能改文件属主（需要 root 或 root 启动的 Shizuku）"
        }
        val bad = mutableListOf<String>()
        for (path in paths) {
            val owner = runCatching { exec("stat -c %u " + ShizukuShell.quote(path)).trim().toIntOrNull() }
                .getOrNull() ?: return "无法确认文件属主（$path），文件缺失或权限不足；未通过安装校验"
            if (owner != ids.uid) bad += path
        }
        if (bad.isEmpty()) return null
        val sample = bad.first()
        val tail = if (bad.size > 1) " 等 ${bad.size} 个文件" else ""
        return "加载器文件属主不是游戏（$sample$tail），游戏会读不到；" +
            "需要 root 或 root 启动的 Shizuku 才能修好"
    }

    private fun dataDir(): String = "/sdcard/Android/data/$gamePackage"

    /** Repair before publication, then verify even when the transport cannot chown. */
    fun requireOwned(path: String, recursive: Boolean = false) {
        requirePaths(listOf(path), recursive)
    }

    private fun requirePaths(paths: List<String>, recursive: Boolean = false) {
        if (!isSupported()) {
            val problem = verifyAccess(paths)
            check(problem == null) { problem!! }
            // Storage ACL/FUSE access cannot be inferred from st_uid. Actual writes and
            // content hashes are checked by the transport; game readability needs a boot log.
            return
        }
        if (isSupported()) {
            check(adopt(paths, recursive)) {
                "文件属主修复失败：${lastError ?: paths.first()}"
            }
        }
        val problem = verify(paths)
        check(problem == null) { problem!! }
        if (recursive) {
            val expected = checkNotNull(ids()) { "读不到游戏 uid" }.uid
            // find does not propagate a failing -exec status; emit an invalid owner instead.
            val stat = ShizukuShell.quote("stat -c %u \"\$1\" 2>/dev/null || echo invalid")
            val owners = exec("find " + paths.joinToString(" ") { ShizukuShell.quote(it) } +
                " -exec sh -c $stat sh {} \\;")
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            check(owners.isNotEmpty() && owners.all { it.toIntOrNull() == expected }) {
                "加载器目录仍有属主异常，未通过安装校验：${paths.first()}"
            }
        }
    }

    private fun verifyAccess(paths: List<String>): String? {
        for (path in paths) {
            val quoted = ShizukuShell.quote(path)
            val result = runCatching {
                exec("if [ -d $quoted ]; then test -r $quoted && test -x $quoted; else test -f $quoted && test -r $quoted; fi && echo accessible")
            }.getOrNull()?.trim()
            if (result != "accessible") return "Shizuku 无法访问路径：$path；请检查授权及系统存储限制"
        }
        return null
    }

    /** mkdir -p may create several ancestors. Repair only this game's files hierarchy. */
    fun requireDirectories(path: String) {
        val normalized = path.replaceFirst(Regex("^/storage/emulated/0(?=/|$)"), "/sdcard").trimEnd('/')
        val base = dataDir() + "/files"
        if (normalized == base || normalized.startsWith("$base/")) {
            require(normalized.split('/').none { it == "." || it == ".." }) { "目录路径无效" }
            var current = base
            val parents = mutableListOf(current)
            for (part in normalized.removePrefix(base).split('/').filter { it.isNotEmpty() }) {
                current += "/$part"
                parents += current
            }
            requirePaths(parents)
        } else {
            requireOwned(path)
        }
    }

    /** Clear diagnostics on channel changes. Identities are always read afresh, including reconnects. */
    fun reset() {
        lastError = null
    }

    companion object {
        /** 全进程共用诊断实例，不缓存 uid，防止服务重连后身份过期。 */
        val default: LoaderOwnership by lazy { LoaderOwnership() }

        fun forPath(path: String): LoaderOwnership =
            AndroidPaths.gamePackageForPath(path)?.takeUnless { it == LoaderInstaller.GAME_PACKAGE }
                ?.let { LoaderOwnership(it) } ?: default
    }
}
