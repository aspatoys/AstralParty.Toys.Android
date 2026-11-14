package com.hiynet.aspatoys.data.mods

import java.io.File

/** 一条模组健康问题；[level] 只决定界面配色。 */
data class ModIssue(
    val modName: String,
    val level: Level,
    val message: String,
) {
    enum class Level { INFO, WARNING, ERROR }
}

/**
 * 扫描 [ModLayout.modsRoot] 与 [ModLayout.disabledModsRoot]，并把每个模组的 sidecar 读出来。
 *
 * 扫描规则与加载器一致：
 * 1. 新布局 `mods\{ModId}\{ModId}.dll`（DLL 与文件夹同名才算一个模组）；
 * 2. 旧布局 `mods\{ModId}.dll` 平铺，sidecar 在同级；
 * 3. `disabled_mods` 里的同名模组排在后面、强制 Enabled = false（加载器只扫 `mods`）。
 */
object InstalledMods {
    /**
     * 经 Shizuku 扫描加载器目录。
     *
     * 加载器根目录在游戏的私有目录里，普通 File API 读不到，所以列表/元数据都要靠 shell。
     * 只读 DLL 是否存在与 sidecar 内容；体积与时间戳这类字段留空（不猜）。
     */
    fun scanRemote(layout: ModLayout, fs: com.hiynet.aspatoys.data.loader.LoaderFs): List<ModEntry> {
        val result = mutableListOf<ModEntry>()
        val seen = mutableSetOf<String>()

        for ((root, disabled) in listOf(layout.modsRoot.absolutePath to false, layout.disabledModsRoot.absolutePath to true)) {
            for (dir in fs.listDirectories(root)) {
                val dllPath = "$root/$dir/$dir.dll"
                if (!fs.exists(dllPath)) continue
                if (!seen.add(dir.lowercase())) continue
                result += remoteEntry(dir, "$dir.dll", dir, disabled, fs.readText("$root/$dir/$dir.json"), fs.fileSize(dllPath))
            }
            for (file in fs.listFiles(root)) {
                if (!file.endsWith(".dll", ignoreCase = true)) continue
                val modId = file.removeSuffix(".dll").removeSuffix(".DLL")
                if (!seen.add(modId.lowercase())) continue
                result += remoteEntry(modId, file, null, disabled, fs.readText("$root/$modId.json"), fs.fileSize("$root/$file"))
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }

    private fun remoteEntry(
        name: String,
        fileName: String,
        directoryName: String?,
        disabled: Boolean,
        sidecarText: String?,
        sizeBytes: Long = 0L,
    ): ModEntry {
        val metadata = try {
            if (sidecarText.isNullOrBlank()) ModSidecar() else (ModSidecar.parse(sidecarText) ?: ModSidecar())
        } catch (_: Exception) {
            ModSidecar()
        }
        return ModEntry(
            name = name,
            fileName = fileName,
            directoryName = directoryName,
            sizeBytes = sizeBytes,
            modifiedEpochMs = 0L,
            id = metadata.id,
            displayName = metadata.name,
            version = metadata.version,
            author = metadata.author,
            description = metadata.description,
            sdkVersion = metadata.sdkVersion,
            enabled = (metadata.enabled ?: true) && !disabled,
            disabledFolder = disabled,
            permissions = metadata.permissions,
            dependencies = metadata.dependencies,
        )
    }

    fun scan(layout: ModLayout): List<ModEntry> {
        val list = scanIn(layout.modsRoot, disabled = false).toMutableList()
        val seen = list.map { it.name.lowercase() }.toMutableSet()
        for (entry in scanIn(layout.disabledModsRoot, disabled = true)) {
            if (seen.add(entry.name.lowercase())) list += entry
        }
        return list
    }

    private fun scanIn(directory: File, disabled: Boolean): List<ModEntry> {
        val list = mutableListOf<ModEntry>()
        try {
            if (!directory.isDirectory) return list
            // 新布局：{ModId}\{ModId}.dll
            directory.listFiles { file -> file.isDirectory }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { sub ->
                    val dll = File(sub, sub.name + ".dll")
                    if (!dll.isFile) return@forEach
                    list += scanEntry(dll, sub.name, disabled)
                }
            // 旧布局：根下平铺的 .dll
            directory.listFiles { file -> file.isFile && file.extension.equals("dll", true) }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { dll ->
                    if (list.any { it.name.equals(dll.nameWithoutExtension, ignoreCase = true) }) return@forEach
                    list += scanEntry(dll, null, disabled)
                }
        } catch (_: Exception) {
            // 扫描失败按空列表处理：没授权或目录不可读时不要把界面打断。
        }
        return list
    }

    /** 读一个模组 DLL 与其 sidecar；sidecar 损坏按「没有 sidecar」处理。 */
    fun scanEntry(dll: File, directoryName: String?, disabled: Boolean): ModEntry {
        val sidecarFile = File(dll.parentFile, dll.nameWithoutExtension + ".json")
        val sidecar = try {
            if (sidecarFile.isFile) ModSidecar.parse(sidecarFile.readText(Charsets.UTF_8)) else null
        } catch (_: Exception) {
            null
        }
        val metadata = sidecar ?: ModSidecar()
        val enabled = (metadata.enabled ?: true) && !disabled
        return ModEntry(
            name = dll.nameWithoutExtension,
            fileName = dll.name,
            directoryName = directoryName,
            sizeBytes = dll.length(),
            modifiedEpochMs = dll.lastModified(),
            id = metadata.id,
            displayName = metadata.name,
            version = metadata.version,
            author = metadata.author,
            description = metadata.description,
            sdkVersion = metadata.sdkVersion,
            enabled = enabled,
            disabledFolder = disabled,
            permissions = metadata.permissions,
            dependencies = metadata.dependencies,
        )
    }

    /**
     * 算出界面要提示的问题：SDK 不兼容、依赖缺失或版本过低、缺 sidecar、同名 ID 冲突。
     * 只有能确定的问题才报，读不到的字段一律跳过（不猜）。
     */
    fun diagnose(entries: List<ModEntry>, installedSdkVersion: String?): List<ModIssue> {
        val issues = mutableListOf<ModIssue>()
        val byId = entries.filter { it.id.isNotEmpty() }
            .groupBy { it.id.lowercase() }
            .filterValues { it.size > 1 }

        for (entry in entries) {
            val name = entry.shownName
            if (entry.sdkVersion.isNotEmpty() && SdkVersions.isIncompatible(entry.sdkVersion, installedSdkVersion)) {
                issues += ModIssue(
                    name,
                    ModIssue.Level.ERROR,
                    "要求 SDK ${entry.sdkVersion}，当前加载器是 ${installedSdkVersion ?: "未知"}；加载器会跳过这个模组。",
                )
            }
            if (entry.id.isEmpty() || entry.version.isEmpty()) {
                issues += ModIssue(name, ModIssue.Level.INFO, "缺少 sidecar 元数据（${entry.name}.json），版本与依赖无法核对。")
            }
            for (dependency in entry.dependencies) {
                val installed = entries.firstOrNull { it.id.equals(dependency.id, ignoreCase = true) }
                when {
                    installed == null -> issues += ModIssue(
                        name,
                        ModIssue.Level.WARNING,
                        "依赖 ${dependency.id} 未安装。",
                    )
                    dependency.minVersion.isNotEmpty() && installed.version.isNotEmpty() &&
                        SdkVersions.compare(installed.version, dependency.minVersion) < 0 ->
                        issues += ModIssue(
                            name,
                            ModIssue.Level.WARNING,
                            "依赖 ${dependency.id} 需要 ≥ ${dependency.minVersion}，当前是 ${installed.version}。",
                        )
                }
            }
        }

        for ((id, group) in byId) {
            issues += ModIssue(
                group.first().shownName,
                ModIssue.Level.WARNING,
                "有 ${group.size} 个模组声明了同一个 id（$id），加载器只会按文件夹区分。",
            )
        }
        return issues
    }
}
