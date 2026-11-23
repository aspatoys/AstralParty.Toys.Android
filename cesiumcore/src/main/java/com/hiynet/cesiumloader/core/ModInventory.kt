package com.hiynet.cesiumloader.core

import java.io.File

/** 一个已安装模组的现场事实。 */
data class InstalledMod(
    val modId: String,
    /** 在 `mods/` 下为 true，在 `disabled_mods/` 下为 false。 */
    val enabled: Boolean,
    /** 实际加载的 DLL（启用时的路径，停用时指向 disabled_mods 下的副本）。 */
    val dll: File,
    /** 一模组一目录时的目录；扁平存放（`mods/X.dll`）时为 null。 */
    val folder: File?,
    /** 玩家配置：`mods/<ModId>/config.json`（停用模组在 `disabled_mods/<ModId>/config.json`）。 */
    val configFile: File,
    val hasConfig: Boolean,
    /** `<ModId>/<ModId>.json` sidecar 是否在位。 */
    val hasSidecar: Boolean,
    /** 同一 ModId 同时出现在 `mods/` 与 `disabled_mods/`。 */
    val duplicated: Boolean,
)

/** 扫描 `<root>/mods` 与 `<root>/disabled_mods`，得到已装模组清单。 */
object ModInventory {

    fun scan(layout: CesiumLayout): List<InstalledMod> {
        val result = LinkedHashMap<String, InstalledMod>()

        for (entry in collect(layout, layout.mods, enabled = true)) {
            result[entry.modId] = entry
        }
        for (entry in collect(layout, layout.disabledMods, enabled = false)) {
            val enabledCopy = result[entry.modId]
            if (enabledCopy != null) {
                // 目录事实里启用优先，但要标记重复，交给上层提示用户清理。
                result[entry.modId] = enabledCopy.copy(duplicated = true)
            } else {
                result[entry.modId] = entry
            }
        }

        return result.values.sortedBy { it.modId.lowercase() }
    }

    private fun collect(layout: CesiumLayout, root: File, enabled: Boolean): List<InstalledMod> {
        if (!root.isDirectory) return emptyList()
        val entries = ArrayList<InstalledMod>()

        // 扁平：<root>/<ModId>.dll
        for (file in root.listFiles().orEmpty()) {
            if (!file.isFile || !file.extension.equals("dll", ignoreCase = true)) continue
            val modId = file.nameWithoutExtension
            entries += InstalledMod(
                modId = modId,
                enabled = enabled,
                dll = file,
                folder = null,
                configFile = layout.configFile(modId),
                hasConfig = layout.configFile(modId).isFile,
                hasSidecar = false,
                duplicated = false,
            )
        }

        // 一模组一目录：<root>/<ModId>/<ModId>.dll
        for (dir in root.listFiles().orEmpty()) {
            if (!dir.isDirectory) continue
            val dll = File(dir, "${dir.name}.dll")
            if (!dll.isFile) continue
            val legacyConfig = File(dir, "config.json")
            entries += InstalledMod(
                modId = dir.name,
                enabled = enabled,
                dll = dll,
                folder = dir,
                configFile = layout.configFile(dir.name),
                hasConfig = layout.configFile(dir.name).isFile || legacyConfig.isFile,
                hasSidecar = layout.modSidecar(dir.name).isFile,
                duplicated = false,
            )
        }
        return entries
    }
}
