package com.hiynet.aspatoys.data.apps

import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.cesiumloader.core.AndroidPaths

/**
 * 一个可以装加载器的目标应用。
 *
 * [knownChannel] / [hasDataDir] / [hasLoader] 都只用来**标注和排序**，不参与"能不能选"的判断：
 * 各渠道服的包名我们事先并不知道，用白名单筛会把用户直接挡在外面。
 */
data class TargetApp(
    val packageName: String,
    val label: String = "",
    /** 来自 PackageManager 的已安装应用（手动写的包名在列表里没有这一项）。 */
    val installed: Boolean = true,
    val versionName: String = "",
    /** 命中加载器已知的渠道规则 —— 没命中不代表不是游戏，只代表要更依赖指纹匹配。 */
    val knownChannel: Boolean = false,
    /** 设备上已经有这个包的数据目录（游戏至少启动过一次）。 */
    val hasDataDir: Boolean = false,
    /** 这个包名下已经装过加载器。 */
    val hasLoader: Boolean = false,
) {
    val displayLabel: String get() = label.ifBlank { packageName }

    val isDefault: Boolean get() = packageName == AppSettings.GamePackage
}

/**
 * 目标应用列表的纯逻辑：解析、排序、过滤。全部是纯函数，放在 JVM 单测里跑。
 *
 * 安卓侧真正取列表的是 [InstalledAppRepository]，这里只负责"拿到的一堆字符串怎么用"。
 */
object TargetApps {

    /** 列表默认停在"推荐"，但一键就能切到"全部应用"。 */
    enum class Filter { Recommended, All }

    /** 包名或应用名里带这些片段就当作"很可能就是目标游戏"，只影响推荐里的排序。 */
    private val NameHints = listOf("astralparty", "feimo", "jxpd", "吉星", "派星")

    /**
     * 解析 `pm list packages` / `pm list packages --user current` 的输出。
     *
     * 只认 `package:` 开头的行：设备上还会混着警告和别的行，不能整段当包名用。
     */
    fun parsePackageList(output: String): List<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { AndroidPaths.isValidPackageName(it) }
            .distinct()
            .sorted()
            .toList()

    /** 解析 `ls /sdcard/Android/data`：取出存在数据目录的包名。 */
    fun parseDataDirs(output: String): Set<String> =
        output.split('\n', ' ', '\t', '\r')
            .asSequence()
            .map { it.trim().substringAfterLast('/') }
            .filter { AndroidPaths.isValidPackageName(it) }
            .toSet()

    /** 解析加载器目录列表（`ls -d` 里以 `files/CesiumLoader` 结尾的那些行）：取出已经装了加载器的包名。 */
    fun parseLoaderDirs(output: String): Set<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.contains("/files/CesiumLoader") }
            .map { it.substringBeforeLast("/files/").substringAfterLast('/') }
            .filter { AndroidPaths.isValidPackageName(it) }
            .toSet()

    fun looksLikeGame(app: TargetApp): Boolean {
        val haystack = (app.packageName + " " + app.label).lowercase()
        return NameHints.any { haystack.contains(it) }
    }

    /** 默认包永远在最前；然后是已知渠道、装过加载器的、有数据目录的、名字像游戏的。 */
    fun rank(apps: List<TargetApp>): List<TargetApp> =
        apps.sortedWith(
            compareByDescending<TargetApp> { it.isDefault }
                .thenByDescending { it.knownChannel }
                .thenByDescending { it.hasLoader }
                .thenByDescending { it.hasDataDir }
                .thenByDescending { looksLikeGame(it) }
                .thenBy { it.displayLabel.lowercase() }
                .thenBy { it.packageName },
        )

    /**
     * [Filter.Recommended] 只是"缩小到最可能的那几个"，不是白名单：
     * 找不到就切到 [Filter.All]，搜索也是在全量里搜。
     */
    fun visible(apps: List<TargetApp>, filter: Filter, query: String): List<TargetApp> {
        val base = when (filter) {
            Filter.All -> apps
            Filter.Recommended -> apps.filter {
                it.isDefault || it.knownChannel || it.hasLoader || it.hasDataDir || looksLikeGame(it)
            }
        }
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return base
        return base.filter {
            it.packageName.lowercase().contains(needle) || it.label.lowercase().contains(needle)
        }
    }
}
