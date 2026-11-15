package com.hiynet.aspatoys.data.market

import com.hiynet.aspatoys.data.mods.ModPlatforms

/**
 * 市场（Forge）公共接口的返回模型，字段与 `https://forge.hiynet.com/api/mods` 一一对应。
 * 这些类只描述「服务端给了什么」，是否已装、是否内置由 [MarketCatalog] 在客户端补。
 */
data class MarketPage(
    val apiVersion: Int = 0,
    val generatedAt: String = "",
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 24,
    val tags: List<String> = emptyList(),
    val items: List<MarketModSummary> = emptyList(),
)

data class MarketModSummary(
    val id: String = "",
    val name: String = "",
    val summary: String = "",
    val author: String = "",
    val tags: List<String> = emptyList(),
    val coverUrl: String? = null,
    val latestVersion: String = "",
    val sdkVersion: String = "",
    val sizeBytes: Long = 0,
    val sha256: String = "",
    val downloadUrl: String = "",
    val updatedAt: String = "",
    val downloads: Long = 0,
    val downloads7Days: Long = 0,
    val downloads30Days: Long = 0,
    val featured: Boolean = false,
    /** 版本声明的支持平台；空 = 没声明（按双平台处理）。 */
    val platforms: List<String> = emptyList(),
) {
    val platformBadge: String? get() = ModPlatforms.badge(platforms)
}

data class MarketModDetail(
    val id: String = "",
    val name: String = "",
    val summary: String = "",
    val description: String = "",
    val author: String = "",
    val tags: List<String> = emptyList(),
    val repositoryUrl: String = "",
    val license: String = "",
    val licenseText: String = "",
    val readme: String = "",
    val coverUrl: String? = null,
    val featured: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val latestVersion: String = "",
    val downloads: Long = 0,
    val versions: List<MarketModVersion> = emptyList(),
)

data class MarketModVersion(
    val version: String = "",
    val downloadUrl: String = "",
    /** 作者托管的原始直链；市场自己托管包时为空。 */
    val sourceUrl: String = "",
    val hosted: Boolean = false,
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val sdkVersion: String = "",
    val permissions: Int = 0,
    val dependencies: List<MarketDependency> = emptyList(),
    val changelog: String = "",
    val publishedAt: String = "",
    val verifiedAt: String = "",
    val downloads: Long = 0,
    /** 版本声明的支持平台；空 = 没声明（按双平台处理）。 */
    val platforms: List<String> = emptyList(),
) {
    val platformBadge: String? get() = ModPlatforms.badge(platforms)
}

data class MarketDependency(
    val id: String = "",
    val minVersion: String = "",
)

/** 下载进度（总量未知时 [percent] 为 null）。 */
data class DownloadProgress(
    val receivedBytes: Long,
    val totalBytes: Long?,
) {
    val percent: Int?
        get() = totalBytes?.takeIf { it > 0 }
            ?.let { (receivedBytes * 100 / it).coerceIn(0, 100).toInt() }
}

/** 模组包下载结果：文件已落盘、哈希已算好。 */
data class PackageDownloadResult(
    val filePath: String,
    val sha256: String,
    val sizeBytes: Long,
    /** 下载到的哈希是否与市场登记的一致；市场没登记哈希时为 null。 */
    val sha256Matches: Boolean?,
    val expectedSha256: String = "",
)

/** 市场请求失败时抛出，消息直接面向用户。 */
class MarketException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 目录条目 + 本机已装状态。这两个字段不是服务端返回的，而是客户端扫工作区模组目录后补上的
 * （桌面版由宿主补 `installedVersion` / `enabled`，口径一致）。
 */
data class MarketListItem(
    val summary: MarketModSummary,
    val installedVersion: String,
    val enabled: Boolean,
) {
    val installed: Boolean get() = installedVersion.isNotEmpty()

    /** 市场登记的最新版本比已装的新 → 可更新。 */
    val updatable: Boolean
        get() = installed && summary.latestVersion.isNotEmpty() &&
            com.hiynet.aspatoys.data.mods.SdkVersions.compare(summary.latestVersion, installedVersion) > 0
}
