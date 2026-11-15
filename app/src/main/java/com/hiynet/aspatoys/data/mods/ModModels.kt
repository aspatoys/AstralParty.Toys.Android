package com.hiynet.aspatoys.data.mods

import com.hiynet.aspatoys.core.booleanOrDefault
import com.hiynet.aspatoys.core.int
import com.hiynet.aspatoys.core.long
import com.hiynet.aspatoys.core.nullableString
import com.hiynet.aspatoys.core.objectArray
import com.hiynet.aspatoys.core.parseJsonObjectOrNull
import com.hiynet.aspatoys.core.string
import com.hiynet.aspatoys.core.stringArray

/** 模组权限位（与 SDK 的 `ModPermission` 枚举一致）。 */
object ModPermissions {
    const val NONE = 0
    const val READ_GAME_STATE = 1 shl 0
    const val GAME_ACTIONS = 1 shl 1
    const val SPEED_HACK = 1 shl 2
    const val FILE_WRITE = 1 shl 3

    /** 把权限位掩码翻成界面用的标签；位全都认不出时返回空列表（不猜）。 */
    fun labels(mask: Int): List<String> = buildList {
        if (mask and READ_GAME_STATE != 0) add("读取游戏状态")
        if (mask and GAME_ACTIONS != 0) add("游戏内动作")
        if (mask and SPEED_HACK != 0) add("变速")
        if (mask and FILE_WRITE != 0) add("写文件")
    }
}

/**
 * 模组支持的平台标记（sidecar 的 `platforms` 数组）。
 *
 * 取值只有 [WINDOWS] 与 [ANDROID]；两个都写就是双平台。没有声明（或只写了认不出的取值）
 * 按双平台处理，旧模组与第三方模组不受影响。平台只用于界面标注：引导照常加载，
 * 不会因为平台不匹配拒绝某个模组。
 */
object ModPlatforms {
    const val WINDOWS = "windows"
    const val ANDROID = "android"

    /** 规范化平台列表：只保留已知取值、去重、Windows 在前（与 SDK 的 `ModPlatform.Normalize` 一致）。 */
    fun normalize(platforms: List<String>): List<String> {
        val lowered = platforms.map { it.trim().lowercase() }
        return buildList {
            if (WINDOWS in lowered) add(WINDOWS)
            if (ANDROID in lowered) add(ANDROID)
        }
    }

    /** 声明里是否包含安卓；没有声明按支持处理。 */
    fun supportsAndroid(declared: List<String>): Boolean =
        normalize(declared).let { it.isEmpty() || ANDROID in it }

    /** 界面标注：只在单平台时给一个标签，双平台/未声明不给（免得每张卡都挂一句「都支持」）。 */
    fun badge(declared: List<String>): String? = when (normalize(declared)) {
        listOf(WINDOWS) -> "仅 Windows"
        listOf(ANDROID) -> "仅 Android"
        else -> null
    }
}

/** 模组声明依赖的另一个模组。 */
data class ModDependency(
    val id: String = "",
    val minVersion: String = "",
)

/**
 * 已安装模组的一条记录：既是加载器扫描 `mods\{ModId}\{ModId}.dll` 的结果，
 * 也是 sidecar（`{ModId}.json`）里声明的元数据。
 */
data class ModEntry(
    val name: String,
    val fileName: String,
    /** 模组文件夹名（新布局）；平铺旧布局为 null。 */
    val directoryName: String?,
    val sizeBytes: Long,
    val modifiedEpochMs: Long,
    val id: String = "",
    val displayName: String = "",
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val sdkVersion: String = "",
    val enabled: Boolean = true,
    val disabledFolder: Boolean = false,
    val permissions: Int = ModPermissions.NONE,
    val dependencies: List<ModDependency> = emptyList(),
    /** sidecar 声明的支持平台；空 = 没声明（按双平台处理）。只作界面标注。 */
    val platforms: List<String> = emptyList(),
) {
    /** 界面显示名：sidecar 的 name 优先，其次 DLL 文件名。 */
    val shownName: String get() = displayName.ifBlank { name }

    val permissionLabels: List<String> get() = ModPermissions.labels(permissions)

    /** 单平台模组的标签（「仅 Windows」/「仅 Android」）；双平台或未声明为 null。 */
    val platformBadge: String? get() = ModPlatforms.badge(platforms)
}

/** 模组的 sidecar 元数据（`{ModId}.json`）；允许带注释。 */
data class ModSidecar(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val sdkVersion: String = "",
    /** sidecar 里显式写的 enabled；null = 没写（缺省视为启用）。 */
    val enabled: Boolean? = null,
    val permissions: Int = 0,
    val dependencies: List<ModDependency> = emptyList(),
    /** sidecar 的 `platforms` 数组；空 = 没声明（按双平台处理）。 */
    val platforms: List<String> = emptyList(),
) {
    companion object {
        /** 解析 sidecar 文本；损坏时返回 null（调用方按「没有 sidecar」处理，不猜）。 */
        fun parse(text: String?): ModSidecar? {
            val root = parseJsonObjectOrNull(text) ?: return null
            return ModSidecar(
                id = root.string("id"),
                name = root.string("name"),
                version = root.string("version"),
                author = root.string("author"),
                description = root.string("description"),
                sdkVersion = root.string("sdkVersion"),
                enabled = if (root.containsKey("enabled")) root.booleanOrDefault("enabled", true) else null,
                permissions = root.int("permissions"),
                dependencies = root.objectArray("dependencies").map { dependency ->
                    ModDependency(
                        id = dependency.string("id"),
                        minVersion = dependency.nullableString("minVersion") ?: "",
                    )
                }.filter { it.id.isNotEmpty() },
                platforms = ModPlatforms.normalize(root.stringArray("platforms")),
            )
        }
    }
}

/**
 * 模组包（ZIP）的结构校验、版本与覆盖情况。
 *
 * 字段与 Toys 的 `ModPackageInspection` 对齐：预检只读不写，用来在真正导入前告诉用户
 * 「包里有什么、会不会降级、会保留哪些玩家配置、会清掉哪些旧文件」。
 */
data class ModPackageInspection(
    val sourceName: String,
    val mods: List<InspectedMod>,
    val skipped: List<String> = emptyList(),
    /** 压缩包本身的 SHA-256（十六进制小写）；市场下载时用于与登记的哈希对比。 */
    val sha256: String = "",
    val sizeBytes: Long = 0,
    /** 包内条目数（不含目录条目与解包垃圾文件）。 */
    val fileCount: Int = 0,
    /** 包内条目解压后的总字节数。 */
    val totalBytes: Long = 0,
) {
    /** 包里有模组比已装的旧 —— 导入前需要用户确认。 */
    val hasDowngrade: Boolean get() = mods.any { it.isDowngrade }

    val hasSdkBlocked: Boolean get() = mods.any { it.sdkBlocked }

    /** 包里声明为仅 Windows 的模组（只用于提示，不阻止导入）。 */
    val windowsOnlyMods: List<InspectedMod> get() = mods.filter { it.windowsOnly }
}

/** 预检里单个模组的情况。 */
data class InspectedMod(
    val modId: String,
    /** 包内 sidecar 的 name；没写时等于 [modId]。 */
    val name: String,
    val folderName: String,
    /** 导入时写入的根目录（`mods` 或 `disabled_mods`）；已停用的模组不会被悄悄搬回 `mods`。 */
    val containerRoot: String,
    val installed: Boolean,
    val installedVersion: String,
    val packageVersion: String,
    val sdkVersion: String,
    val isDowngrade: Boolean,
    val isUpgrade: Boolean,
    val preservesConfig: Boolean,
    val preservesDisabled: Boolean,
    val removedFiles: List<String> = emptyList(),
    /** 包内属于这个模组的文件（相对模组目录，`/` 分隔）。 */
    val files: List<String> = emptyList(),
    /** 这些文件解压后的总字节数。 */
    val totalBytes: Long = 0,
    /** 当前加载器 SDK 版本（读不到时为空）。 */
    val installedSdkVersion: String = "",
    /** 包内声明的 SDK 高于当前加载器 → 装上去也会被加载器跳过。 */
    val sdkBlocked: Boolean = false,
    /** 模组 DLL 看起来是托管程序集（PE + CLI 元数据），加载器才认。 */
    val dllLooksManaged: Boolean = false,
    /** 包内 sidecar 声明的支持平台；空 = 没声明（按双平台处理）。 */
    val platforms: List<String> = emptyList(),
) {
    /** 单平台模组的标签（「仅 Windows」/「仅 Android」）；双平台或未声明为 null。 */
    val platformBadge: String? get() = ModPlatforms.badge(platforms)

    /** 声明为仅 Windows：安卓上装了也会加载，但功能不一定可用（只提示，不拦）。 */
    val windowsOnly: Boolean get() = platformBadge == "仅 Windows"

    val versionText: String
        get() = when {
            packageVersion.isBlank() -> "版本未声明"
            isUpgrade -> "v$installedVersion → v$packageVersion"
            isDowngrade -> "v$installedVersion → v$packageVersion（降级）"
            installed -> "已装 v$installedVersion"
            else -> "v$packageVersion"
        }
}

/** 模组包（ZIP）不合法时抛出；消息直接面向用户。 */
class ModPackageException(message: String) : Exception(message)

/** 模组包内单个文件条目：按 `/` 拆好的相对路径 + 原始条目。 */
internal class PackageFile(
    val segments: List<String>,
    val zipPath: String,
    val length: Long,
) {
    val fileName: String get() = segments.last()
    val relativePath: String get() = segments.joinToString("/")
}

/** 一个待安装模组的计划：包内文件 + 已装状态 + 预检结果。 */
internal class PackageModPlan(
    val modId: String,
    /** 模组在包内的目录前缀；根目录平铺的包为空。 */
    val folderSegments: List<String>,
) {
    /** 实际写入的文件夹名（沿用已装模组的大小写写法）。 */
    var folderName: String = modId
    /** 这个模组该写进哪个根目录（mods 或 disabled_mods）。 */
    var containerRoot: String = ""
    val files: MutableList<PackageFile> = mutableListOf()

    var packageVersion: String = ""
    var packageDisplayName: String = ""
    var packageSdkVersion: String = ""
    var packagePlatforms: List<String> = emptyList()
    var installedVersion: String = ""
    var installed: Boolean = false
    var isDowngrade: Boolean = false
    var isUpgrade: Boolean = false
    var configExisted: Boolean = false
    var disabledBefore: Boolean = false
    var staleFiles: List<String> = emptyList()
    var totalBytes: Long = 0
    var dllLooksManaged: Boolean = false

    val displayName: String get() = packageDisplayName.ifBlank { modId }

    /** 条目相对模组目录的路径（用 `/` 分隔）。 */
    fun relativePathOf(file: PackageFile): String =
        if (folderSegments.isEmpty()) file.fileName
        else file.segments.drop(folderSegments.size).joinToString("/")

    fun add(file: PackageFile) {
        if (files.any { it.relativePath.equals(file.relativePath, ignoreCase = true) }) return
        files += file
    }
}
