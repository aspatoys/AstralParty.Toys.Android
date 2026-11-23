package com.hiynet.aspatoys.data.loader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.hiynet.aspatoys.data.mods.InstalledMods
import com.hiynet.aspatoys.data.mods.ModEntry
import com.hiynet.aspatoys.data.mods.ModIssue
import com.hiynet.aspatoys.data.mods.ModLayout
import com.hiynet.aspatoys.installer.ShizukuShell
import java.io.File

/** 一次扫描的完整结果：模组列表、SDK 版本、诊断问题、读取方式与失败原因。 */
data class LoaderSnapshot(
    val entries: List<ModEntry>,
    val sdkVersion: String?,
    val issues: List<ModIssue>,
    /** true = 经 Shizuku 读的；false = Shizuku 不可用，退化成直读。 */
    val viaShizuku: Boolean,
    val error: String?,
)

/**
 * 加载器目录的唯一入口。
 *
 * 模组页、市场页、安装流程都从这里读写加载器，避免各自 new 一个 Shizuku 通道、各自缓存一份
 * 「已装什么」——那种写法会让两个页面看到不一样的状态（市场显示未装、模组页里明明有）。
 *
 * [version] 是数据变更计数：安装或启停成功后自增，页面据此决定要不要重新扫码。
 */
class LoaderRepo(
    private val layoutProvider: () -> ModLayout,
    /** 安装时的中转目录：必须让 Shizuku 的 `shell` 读得到，见 [ModInstaller]。 */
    stagingRoot: File,
) {

    /** 所有操作共用一条 Shizuku 通道，顺手共享它的错误信息。 */
    val fs: LoaderFs = ShizukuLoaderFs()

    private val installer = ModInstaller(fs, stagingRoot)

    var version by mutableIntStateOf(0)
        private set

    private var cached: LoaderSnapshot? = null

    fun layout(): ModLayout = layoutProvider()

    /** Shizuku 是否可用（服务在跑且已授权）。 */
    fun ready(): Boolean = fs.isAvailable() || ShizukuShell.awaitBinder()

    /** 不可用时给界面看的原因。 */
    fun unavailableReason(): String = when {
        !ShizukuShell.isServiceAlive() -> "Shizuku 服务没在运行：先启动 Shizuku，再回到这里点「刷新」。"
        !ShizukuShell.hasPermission() -> "还没拿到 Shizuku 授权：点「授权」允许后重试。"
        else -> "Shizuku 通道暂时不可用。"
    }

    /**
     * 扫描加载器目录。不可用时退化成普通文件读取（读得到就读，读不到返回空并带原因），
     * 让界面至少能说清「为什么看不到模组」。
     */
    fun scan(): LoaderSnapshot {
        val layout = layout()
        val usable = ready()
        if (!usable) {
            val entries = runCatching { InstalledMods.scan(layout) }.getOrDefault(emptyList())
            val sdk = layout.installedSdkVersion()
            val snapshot = LoaderSnapshot(
                entries = entries,
                sdkVersion = sdk,
                issues = InstalledMods.diagnose(entries, sdk),
                viaShizuku = false,
                error = if (entries.isEmpty()) unavailableReason() else null,
            )
            cached = snapshot
            return snapshot
        }

        val entries = InstalledMods.scanRemote(layout, fs)
        val sdk = layout.installedSdkVersion { path -> fs.readText(path) }
        val snapshot = LoaderSnapshot(
            entries = entries,
            sdkVersion = sdk,
            issues = InstalledMods.diagnose(entries, sdk),
            viaShizuku = true,
            // 只有一条都没读到才把 Shizuku 的错误顶到界面上；读到一半的偶发失败不值得打断用户。
            error = if (entries.isEmpty()) fs.lastError else null,
        )
        cached = snapshot
        return snapshot
    }

    /** 取最近一次扫描结果；没有就扫一次。市场页用它标「已安装」。 */
    fun snapshot(): LoaderSnapshot = cached ?: scan()

    /** 模组 ID / 文件夹名 → (版本, 是否启用)。 */
    fun installedIndex(): Map<String, Pair<String, Boolean>> {
        val map = mutableMapOf<String, Pair<String, Boolean>>()
        for (entry in snapshot().entries) {
            val value = entry.version to entry.enabled
            if (entry.id.isNotEmpty()) map[entry.id.lowercase()] = value
            map[entry.name.lowercase()] = value
        }
        return map
    }

    /** 启用/停用；成功后 [version] 自增。失败抛异常，消息可直接给用户看。 */
    fun setEnabled(entry: ModEntry, enabled: Boolean, log: (String) -> Unit = {}): ModInstaller.ToggleResult {
        requireReady()
        val result = installer.setEnabled(layout(), entry.name, entry.directoryName, enabled, log)
        if (result.changed) invalidate()
        return result
    }

    /** 删除已安装模组；只删本体，配置保留。 */
    fun deleteMod(entry: ModEntry, log: (String) -> Unit = {}): Boolean {
        requireReady()
        val deleted = installer.delete(
            layout = layout(),
            modId = entry.name,
            folderName = entry.directoryName,
            disabled = entry.disabledFolder,
            log = log,
        )
        if (deleted) invalidate()
        return deleted
    }

    /** 把本地 ZIP 装进加载器；成功后 [version] 自增。 */
    fun install(zip: File, log: (String) -> Unit = {}): ModInstaller.Result {
        requireReady()
        val result = installer.install(zip, layout(), log)
        invalidate()
        return result
    }

    /** 配置文件位置（4.0.0 起唯一）：`mods/<ModId>/config.json`；停用模组是 `disabled_mods/<ModId>/config.json`。 */
    fun configPath(modId: String, disabled: Boolean = false): String =
        layout().modConfigFile(modId, disabled).absolutePath

    /**
     * 读玩家配置：模组目录里的 `config.json`（启用 / 停用看目录）。
     * 旧版集中放的 `configs/<ModId>.json` 已在 4.0.0 废弃，不再读取。
     */
    fun readConfig(modId: String, disabled: Boolean = false): String? {
        if (!ready()) return null
        return fs.readText(configPath(modId, disabled))?.takeIf { it.isNotBlank() }
    }

    fun writeConfig(modId: String, text: String, disabled: Boolean = false) {
        requireReady()
        installer.writeConfig(layout(), modId, text, disabled)
    }

    /** 外部改动了加载器目录（装/还原加载器、导入模组）后调用，让各页面下次进入时重扫。 */
    fun invalidate() {
        cached = null
        version++
    }

    private fun requireReady() {
        if (!ready()) throw IllegalStateException(unavailableReason())
    }
}
