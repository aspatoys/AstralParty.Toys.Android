package com.hiynet.aspatoys.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.data.market.CheckIssue
import com.hiynet.aspatoys.data.market.PackageCheckReport
import com.hiynet.aspatoys.data.mods.InspectedMod
import com.hiynet.aspatoys.ui.format.Formats

/** 模组包检查报告：结论、问题清单、每个模组的版本/覆盖情况。 */
@Composable
fun PackageReportContent(report: PackageCheckReport, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val headlineColor = when {
        report.inspection == null -> scheme.error
        !report.success -> scheme.error
        report.inspection.mods.any { it.isDowngrade } -> scheme.tertiary
        else -> scheme.primary
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            report.headline,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = headlineColor,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            report.fileName,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        InfoLine("文件大小", Formats.bytes(report.sizeBytes))
        InfoLine("包内模组数", report.inspection?.mods?.size?.toString() ?: "—")
        InfoLine("包内文件数", report.inspection?.let { "${it.fileCount} 个 / 解压 ${Formats.bytes(it.totalBytes)}" } ?: "—")
        InfoLine("加载器 SDK", report.installedSdkVersion ?: "未读到（跳过 SDK 检查）")
        if (report.sha256.isNotEmpty()) {
            InfoLine("SHA-256", report.sha256)
        }
        if (report.expectedSha256.isNotEmpty()) {
            InfoLine(
                "市场登记哈希",
                if (report.sha256.equals(report.expectedSha256, ignoreCase = true)) "一致" else "不一致",
            )
        }

        val issues = report.issues
        if (issues.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("检查结论", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                issues.forEach { issue -> IssueRow(issue) }
            }
        }

        val mods = report.inspection?.mods.orEmpty()
        if (mods.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("包内模组", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                mods.forEach { mod -> ModEntryCard(mod) }
            }
        }

        val skipped = report.inspection?.skipped.orEmpty()
        if (skipped.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "不属于任何模组的条目（安装时忽略）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            skipped.take(20).forEach { path ->
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (skipped.size > 20) {
                Text("…等 ${skipped.size} 个", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }

        if (report.inspection != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                "模组包已保存到下载目录。当前版本只做到下载与检查，安装进游戏 mods 目录的功能稍后接入。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IssueRow(issue: CheckIssue) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (issue.level) {
        CheckIssue.Level.ERROR -> scheme.errorContainer to scheme.onErrorContainer
        CheckIssue.Level.WARNING -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        CheckIssue.Level.INFO -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = issue.message,
            style = MaterialTheme.typography.bodySmall,
            color = content,
        )
    }
}

@Composable
private fun ModEntryCard(mod: InspectedMod) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                mod.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (mod.sdkBlocked) {
                Pill("SDK 不兼容", scheme.errorContainer, scheme.onErrorContainer)
            } else if (mod.isDowngrade) {
                Pill("降级", scheme.tertiaryContainer, scheme.onTertiaryContainer)
            } else if (mod.isUpgrade) {
                Pill("升级", scheme.primaryContainer, scheme.onPrimaryContainer)
            } else if (mod.installed) {
                Pill("已装", scheme.secondaryContainer, scheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(6.dp))
        InfoLine("模组 ID", mod.modId)
        InfoLine("版本", mod.versionText)
        InfoLine("要求 SDK", mod.sdkVersion.ifBlank { "未声明" })
        InfoLine("DLL 检查", if (mod.dllLooksManaged) "托管程序集" else "不是托管程序集")
        InfoLine("文件", "${mod.files.size} 个 / ${Formats.bytes(mod.totalBytes)}")
        if (mod.preservesConfig) InfoLine("玩家配置", "保留现有 config.json")
        if (mod.preservesDisabled) InfoLine("启用状态", "保持停用")
        if (mod.removedFiles.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "升级会清掉的旧文件（${mod.removedFiles.size}）",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            mod.removedFiles.take(10).forEach { file ->
                Text(
                    "· $file",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (mod.removedFiles.size > 10) {
                Text("…等 ${mod.removedFiles.size} 个", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}
