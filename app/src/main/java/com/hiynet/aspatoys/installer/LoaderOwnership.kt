package com.hiynet.aspatoys.installer

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

    @Volatile
    private var cachedUid: Int? = null

    @Volatile
    private var cachedIds: Ids? = null

    /** 最近一次失败原因；成功时为空。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 提权通道自己的 uid：0 = 能 chown，2000 = 只能写有权限的地方。 */
    fun channelUid(): Int? {
        cachedUid?.let { return it }
        val value = runCatching { exec("id -u").trim().toIntOrNull() }.getOrNull()
        if (value != null) cachedUid = value
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
        cachedIds?.let { return it }
        if (!isSupported()) return null
        val output = runCatching { exec("stat -c '%u %g' " + ShizukuShell.quote(dataDir())) }.getOrNull()
        val parts = output?.trim()?.split(Regex("\\s+")).orEmpty()
        val uid = parts.getOrNull(0)?.toIntOrNull()
        val gid = parts.getOrNull(1)?.toIntOrNull()
        if (uid == null || gid == null) {
            lastError = "读不到游戏 uid（$gamePackage）"
            return null
        }
        return Ids(uid, gid).also { cachedIds = it }
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
            // 改属主失败不等于安装失败：文件已经写好了，只是游戏可能读不到。
            // 记下原因，交给 [verify] 在安装结束时给出明确提示。
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
        val ids = ids() ?: return if (isSupported()) {
            "读不到游戏 uid，无法确认加载器可读"
        } else {
            "提权通道是 uid ${channelUid() ?: "?"}，不能改文件属主（需要 root 或 root 启动的 Shizuku）"
        }
        val bad = mutableListOf<String>()
        for (path in paths) {
            val owner = runCatching { exec("stat -c %u " + ShizukuShell.quote(path)).trim().toIntOrNull() }
                .getOrNull() ?: continue
            if (owner != ids.uid) bad += path
        }
        if (bad.isEmpty()) return null
        val sample = bad.first()
        val tail = if (bad.size > 1) " 等 ${bad.size} 个文件" else ""
        return "加载器文件属主不是游戏（$sample$tail），游戏会读不到；" +
            "需要 root 或 root 启动的 Shizuku 才能修好"
    }

    private fun dataDir(): String = "/sdcard/Android/data/$gamePackage"

    /** 换模式、重装应用、或换游戏包之后要重新探测。 */
    fun reset() {
        cachedUid = null
        cachedIds = null
        lastError = null
    }

    companion object {
        /** 全进程共用一份（uid 缓存跟着它走）。 */
        val default: LoaderOwnership by lazy { LoaderOwnership() }
    }
}
