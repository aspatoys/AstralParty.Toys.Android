package com.hiynet.aspatoys.data.mods

import com.hiynet.aspatoys.core.parseJsonObjectOrNull
import com.hiynet.aspatoys.core.string
import com.hiynet.aspatoys.core.stringArray
import java.io.File

/**
 * 模组目录布局：与 CesiumLoader 的结构一致。
 *
 * 安卓版的加载器根目录是 **`/sdcard/CesiumLoader`**（探针给出的 `GameRootDirectory`），
 * 市场下载、随包内置与更新替换的模组都写进它的 `mods\<ModId>\<ModId>.dll`：
 *
 * ```
 * /sdcard/CesiumLoader/                       ← 加载器根目录（SdkDirectory 也在这一层）
 * /sdcard/CesiumLoader/mods/<ModId>/<ModId>.dll
 * /sdcard/CesiumLoader/mods/<ModId>/config.json   ← 模组玩家配置（4.0.0 起唯一位置）
 * /sdcard/CesiumLoader/logs/                  ← 加载器与模组日志
 * /sdcard/CesiumLoader/manifests/packages/    ← 本 App 记录「上次导入写了哪些文件」
 * ```
 *
 * 停用的模组整个目录在 `disabled_mods/<ModId>/`，它的配置也跟着在那一层（见 [modConfigFile]）。
 * 旧版集中放配置的 `configs/` 已在 4.0.0 废弃（加载器启动时改名 `configs.bak`），
 * 这里既不再读也不再写。
 */
class ModLayout(val loaderRoot: File) {
    val modsRoot: File get() = File(loaderRoot, MODS_FOLDER)
    val disabledModsRoot: File get() = File(loaderRoot, DISABLED_MODS_FOLDER)
    val sdkRoot: File get() = File(loaderRoot, SDK_FOLDER)
    val bootstrapRoot: File get() = File(loaderRoot, BOOTSTRAP_FOLDER)
    /** 旧版集中配置目录（4.0.0 起废弃，加载器启动时改名 `configs.bak`）；不再读写它。 */
    val configsRoot: File get() = File(loaderRoot, CONFIGS_FOLDER)
    val configFile: File get() = File(loaderRoot, CONFIG_FILE_NAME)

    /**
     * 模组配置的唯一位置（4.0.0 起）：模组在 `disabled_mods` 时写那边，否则写
     * `mods/<ModId>/config.json`（与加载器 SDK 的查找规则一致）。
     */
    fun modConfigFile(modId: String, disabled: Boolean = false): File =
        File(if (disabled) disabledModsRoot else modsRoot, "$modId/$MOD_CONFIG_FILE_NAME")

    /** 安装器写的装载状态：加载器版本、SDK 版本、装了哪些内置模组。 */
    val installedIndex: File get() = File(loaderRoot, INSTALLED_INDEX)
    val manifestRoot: File get() = File(loaderRoot, MANIFEST_FOLDER)

    /**
     * 当前加载器实际用于模组版本校验的 SDK 版本。
     *
     * 优先取加载器配置里的 `sdkVersion`（加载器真正读取并用于拒绝模组的值）。安卓版配置文件名
     * 还没确认，这里按 [ConfigFileCandidates] 依次尝试；都读不到就返回 null ——
     * 调用方据此跳过 SDK 检查，而不是猜一个版本。
     */
    fun installedSdkVersion(fs: (String) -> String? = { null }): String? {
        // 优先读安装器写的 installed.json：安卓加载器没有 doorstop_config.json，
        // SDK 版本只能由安装器在投放时记录。
        val indexText = try {
            if (installedIndex.isFile) installedIndex.readText(Charsets.UTF_8) else fs(installedIndex.absolutePath)
        } catch (_: Exception) {
            fs(installedIndex.absolutePath)
        }
        if (indexText != null) {
            val version = parseJsonObjectOrNull(indexText)?.string("sdkVersion")
            if (!version.isNullOrBlank()) return version
        }

        for (name in ConfigFileCandidates) {
            val file = File(loaderRoot, name)
            if (!file.isFile) continue
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                continue
            }
            val sdkVersion = parseJsonObjectOrNull(text)?.string("sdkVersion") ?: continue
            if (sdkVersion.isNotBlank()) return sdkVersion
        }
        return null
    }

    /** 上次导入为某个模组写了哪些文件；没有清单时返回 null（按「手动安装」处理）。 */
    fun readPackageManifest(modId: String): Set<String>? {
        val file = File(File(manifestRoot, "packages"), "$modId.json")
        if (!file.isFile) return null
        val root = try {
            parseJsonObjectOrNull(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        } ?: return null
        val files = root.stringArray("files").filter { it.isNotEmpty() }
        if (files.isEmpty()) return null
        return files.map { it.replace('\\', '/') }.toSet()
    }

    companion object {
        const val MODS_FOLDER = "mods"
        const val DISABLED_MODS_FOLDER = "disabled_mods"
        const val SDK_FOLDER = "sdk"
        const val BOOTSTRAP_FOLDER = "bootstrap"
        const val CONFIGS_FOLDER = "configs"
        const val INSTALLED_INDEX = "installed.json"
        const val CONFIG_FILE_NAME = "doorstop_config.json"
        const val MANIFEST_FOLDER = "manifests"
        const val MOD_CONFIG_FILE_NAME = "config.json"

        /** 加载器配置文件候选名：安卓版的实际文件名还没确认，按顺序找第一个带 sdkVersion 的。 */
        val ConfigFileCandidates = listOf(
            "doorstop_config.json",
            "cesium-loader.json",
            "cesiumloader.json",
            "loader.json",
            "config.json",
        )
    }
}
