package com.hiynet.aspatoys.data.market

import com.hiynet.aspatoys.core.HttpCancellation
import com.hiynet.aspatoys.data.mods.InspectedMod
import com.hiynet.aspatoys.data.mods.ModLayout
import com.hiynet.aspatoys.data.mods.ModPackageException
import com.hiynet.aspatoys.data.mods.ModPackageInspection
import com.hiynet.aspatoys.data.mods.ModPackageInspector
import java.io.File

/** 检查结论的一个条目；[level] 为 [Level.ERROR] 时不应继续安装。 */
data class CheckIssue(
    val level: Level,
    val message: String,
) {
    enum class Level { INFO, WARNING, ERROR }
}

/**
 * 一次「下载 → 校验 → 预检」的结果。
 *
 * 与桌面版的市场安装链路保持一致：下载时边下边算 SHA-256，哈希对不上、包里不止一个模组、
 * 模组 ID 或版本与登记不一致、SDK 版本高于当前加载器，都算检查失败（[success] = false）。
 */
data class PackageCheckReport(
    /** 市场侧登记的模组 ID（本地包检查时为空）。 */
    val modId: String,
    val modName: String,
    /** 市场侧登记的版本（本地包检查时为空）。 */
    val registeredVersion: String,
    val fileName: String,
    val filePath: String,
    val sha256: String,
    val expectedSha256: String,
    val sizeBytes: Long,
    val inspection: ModPackageInspection?,
    val issues: List<CheckIssue>,
    val installedSdkVersion: String?,
) {
    val success: Boolean get() = issues.none { it.level == CheckIssue.Level.ERROR }

    /** 报告顶部的一句话结论。 */
    val headline: String
        get() = when {
            inspection == null -> "检查未完成"
            !success -> "检查未通过"
            inspection.mods.any { it.isDowngrade } -> "检查通过，但包内版本比已装的旧"
            else -> "检查通过"
        }
}

/**
 * 市场模组包的下载与检查。
 *
 * 这里只负责「下载 → 校验 → 预检 → 出报告」，包留在 [downloadsDir]；真正写进加载器由
 * `LoaderRepo.install` 完成。分开的理由：报告要把「装进去会发生什么」讲清楚
 * （降级、SDK 不兼容、会保留哪个玩家配置、会清掉哪些旧文件），检查不过就不该动加载器目录。
 */
class ModPackageService(
    private val client: MarketClient,
    private val inspector: ModPackageInspector,
    private val layout: ModLayout,
    private val downloadsDir: File,
    /**
     * 当前加载器实际使用的 SDK 版本。加载器目录在游戏私有目录里，读它得走 Shizuku，
     * 所以由外面注入；读不到返回 null，此时跳过 SDK 门槛而不是猜一个版本。
     */
    private val installedSdkVersion: () -> String? = { layout.installedSdkVersion() },
) {
    /** 下载并检查市场里的某个版本。 */
    fun downloadAndCheck(
        baseUrl: String,
        detail: MarketModDetail,
        version: MarketModVersion,
        onProgress: (DownloadProgress) -> Unit = {},
        onPhase: (String) -> Unit = {},
        cancellation: HttpCancellation? = null,
    ): PackageCheckReport {
        val target = File(downloadsDir, "${sanitize(detail.id)}-${sanitize(version.version)}.zip")
        val part = File(target.path + ".part")
        downloadsDir.mkdirs()

        val result = try {
            client.downloadPackage(
                url = MarketClient.resolveUrl(baseUrl, version.downloadUrl),
                target = part,
                expectedSha256 = version.sha256,
                onProgress = onProgress,
                cancellation = cancellation,
            )
        } catch (error: Exception) {
            part.delete()
            throw error
        }

        // 先改名再检查：包留在下载目录里，用户可以直接重看报告或拷到电脑上安装。
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            throw MarketException("下载完成但无法保存到 ${target.name}，请检查存储空间。")
        }

        val issues = mutableListOf<CheckIssue>()
        onPhase("verify")
        if (result.sha256Matches == false) {
            issues += CheckIssue(
                CheckIssue.Level.ERROR,
                "下载到的文件与市场登记的 SHA-256 不一致，已拒绝安装。可能是作者替换了同版本文件。",
            )
        }

        onPhase("inspect")
        val inspection = inspectFile(target, issues)
        if (inspection != null) {
            appendPackageChecks(inspection, issues, expectedModId = detail.id, expectedVersion = version.version)
        }

        return PackageCheckReport(
            modId = detail.id,
            modName = detail.name.ifBlank { detail.id },
            registeredVersion = version.version,
            fileName = target.name,
            filePath = target.path,
            sha256 = result.sha256,
            expectedSha256 = result.expectedSha256,
            sizeBytes = result.sizeBytes,
            inspection = inspection,
            issues = issues,
            installedSdkVersion = installedSdkVersion(),
        )
    }

    /** 检查一个本地模组包（用户从文件管理器选的、或之前下载留下的）。 */
    fun checkLocal(file: File): PackageCheckReport {
        val issues = mutableListOf<CheckIssue>()
        val inspection = inspectFile(file, issues)
        if (inspection != null) appendPackageChecks(inspection, issues, expectedModId = null, expectedVersion = null)
        return PackageCheckReport(
            modId = inspection?.mods?.singleOrNull()?.modId.orEmpty(),
            modName = inspection?.mods?.singleOrNull()?.name.orEmpty(),
            registeredVersion = "",
            fileName = file.name,
            filePath = file.path,
            sha256 = inspection?.sha256.orEmpty(),
            expectedSha256 = "",
            sizeBytes = file.length(),
            inspection = inspection,
            issues = issues,
            installedSdkVersion = installedSdkVersion(),
        )
    }

    /** 下载目录里已有的模组包（按修改时间倒序）。 */
    fun downloadedPackages(): List<File> {
        val files = downloadsDir.listFiles { file -> file.isFile && file.extension.equals("zip", true) }
            ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
    }

    private fun inspectFile(file: File, issues: MutableList<CheckIssue>): ModPackageInspection? = try {
        inspector.inspect(file)
    } catch (error: ModPackageException) {
        issues += CheckIssue(CheckIssue.Level.ERROR, error.message ?: "模组包不合法。")
        null
    } catch (error: Exception) {
        issues += CheckIssue(CheckIssue.Level.ERROR, "读取模组包失败：${error.message}")
        null
    }

    /** 结构检查通过之后的语义检查：个数、ID/版本是否与市场登记一致、SDK、降级、托管程序集。 */
    private fun appendPackageChecks(
        inspection: ModPackageInspection,
        issues: MutableList<CheckIssue>,
        expectedModId: String?,
        expectedVersion: String?,
    ) {
        val mods = inspection.mods
        if (mods.isEmpty()) {
            issues += CheckIssue(CheckIssue.Level.ERROR, "压缩包里没有模组。")
            return
        }
        if (expectedModId != null && mods.size != 1) {
            issues += CheckIssue(CheckIssue.Level.ERROR, "压缩包里应当只有一个模组，实际有 ${mods.size} 个。")
        }
        for (mod in mods) {
            appendInstalledStateHint(mod, issues)
            if (expectedModId != null && !mod.modId.equals(expectedModId, ignoreCase = true)) {
                issues += CheckIssue(
                    CheckIssue.Level.ERROR,
                    "包里的模组是 ${mod.modId}，与市场登记的 $expectedModId 不一致。",
                )
            }
            if (!expectedVersion.isNullOrBlank() && mod.packageVersion.isNotEmpty() &&
                !mod.packageVersion.equals(expectedVersion, ignoreCase = true)
            ) {
                issues += CheckIssue(
                    CheckIssue.Level.ERROR,
                    "包内版本（v${mod.packageVersion}）与市场登记的 v$expectedVersion 不一致。",
                )
            }
            if (mod.packageVersion.isBlank()) {
                issues += CheckIssue(CheckIssue.Level.WARNING, "${mod.name} 的包内 sidecar 没写版本号，无法核对版本。")
            }
            if (!mod.dllLooksManaged) {
                issues += CheckIssue(
                    CheckIssue.Level.ERROR,
                    "${mod.modId}.dll 不像 .NET 托管程序集（缺少 CLI 元数据），加载器不会加载它。",
                )
            }
            if (mod.sdkBlocked) {
                issues += CheckIssue(
                    CheckIssue.Level.ERROR,
                    "${mod.name} 要求 SDK ${mod.sdkVersion}，当前加载器是 ${mod.installedSdkVersion}；装上去会被加载器跳过。",
                )
            }
            if (mod.windowsOnly) {
                issues += CheckIssue(
                    CheckIssue.Level.INFO,
                    "${mod.name} 声明只支持 Windows；安卓引导照常加载，但功能不一定可用。",
                )
            }
            if (mod.isDowngrade) {
                issues += CheckIssue(
                    CheckIssue.Level.WARNING,
                    "${mod.name} 已装 v${mod.installedVersion}，包内是 v${mod.packageVersion}，这是降级。",
                )
            }
        }
        if (inspection.skipped.isNotEmpty()) {
            issues += CheckIssue(
                CheckIssue.Level.INFO,
                "包里有 ${inspection.skipped.size} 个文件不属于任何模组，安装时会被忽略：" +
                    inspection.skipped.take(5).joinToString("、") +
                    if (inspection.skipped.size > 5) " 等" else "",
            )
        }
    }

    private fun appendInstalledStateHint(mod: InspectedMod, issues: MutableList<CheckIssue>) {
        if (!mod.installed) return
        val notes = mutableListOf<String>()
        if (mod.preservesConfig) notes += "保留玩家改过的 config.json"
        if (mod.preservesDisabled) notes += "保持停用状态"
        if (mod.removedFiles.isNotEmpty()) {
            notes += "清掉 ${mod.removedFiles.size} 个旧版本残留（回收站）"
        }
        if (notes.isNotEmpty()) {
            issues += CheckIssue(CheckIssue.Level.INFO, "${mod.name} 已安装：安装时会${notes.joinToString("、")}。")
        }
    }

    private fun sanitize(value: String): String {
        val cleaned = value.trim().map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_' || char == '.') char else '_'
        }.joinToString("")
        return cleaned.ifBlank { "mod" }.take(64)
    }
}
