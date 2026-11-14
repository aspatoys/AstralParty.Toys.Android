package com.hiynet.aspatoys.ui.mods

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.data.mods.ModEntry
import com.hiynet.aspatoys.data.mods.ModIssue
import com.hiynet.aspatoys.ui.components.MessageBanner
import com.hiynet.aspatoys.ui.components.PackageReportContent
import com.hiynet.aspatoys.ui.components.Pill
import com.hiynet.aspatoys.ui.format.Formats

/**
 * 模组页：工作区里的已装模组 + 下载目录里的模组包 + 本地包检查入口。
 *
 * 工作区目录（游戏 `AstralParty_ModLoader` 的等价物）来自设置；模组项目的实际位置确定后，
 * 只需把设置里的路径指过去，本页不需要改代码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModsScreen(
    controller: ModsController,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showReport by remember { mutableStateOf(false) }
    LaunchedEffect(controller.report) { showReport = controller.report != null }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.checkContentUri(uri)
    }

    // 进页面时按需扫一次：数据没变（比如只是切了个 tab）就不重扫，
    // 市场刚装完模组则因为数据版本变了而自动重扫。
    LaunchedEffect(Unit) { controller.onEnter() }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { WorkspaceCard(controller, onOpenSettings) }

            if (controller.scanMode.isNotEmpty() || controller.scanError != null) {
                item {
                    Column {
                        Text(
                            "读取方式：${controller.scanMode}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        controller.scanError?.let { error ->
                            MessageBanner(
                                text = error,
                                container = MaterialTheme.colorScheme.errorContainer,
                                content = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            if (!controller.allFilesAccess) {
                item { StorageAccessCard(controller) }
            }

            if (controller.message != null) {
                item {
                    Column {
                        MessageBanner(
                            text = controller.message!!,
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = controller::clearMessage) { Text("知道了") }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                        Text("选择 ZIP 检查")
                    }
                    OutlinedButton(onClick = controller::refresh, enabled = !controller.scanning) {
                        Text(if (controller.scanning) "刷新中…" else "刷新")
                    }
                }
            }

            val issues = controller.issues
            if (issues.isNotEmpty()) {
                item {
                    Text(
                        "发现 ${issues.size} 个问题",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(issues.size) { index -> IssueCard(issues[index]) }
            }

            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader(
                    title = "已装模组（${controller.installed.size}）",
                    subtitle = if (controller.installedSdkVersion != null) {
                        "加载器 SDK ${controller.installedSdkVersion}"
                    } else {
                        "未读到加载器 SDK 版本：SDK 兼容性检查已跳过"
                    },
                )
            }
            if (controller.installed.isEmpty()) {
                item {
                    EmptyHint("工作区里还没有模组。把 AstralParty_ModLoader 目录放到工作区，或先在市场下载模组包。")
                }
            }
            items(controller.installed.size) { index ->
                val entry = controller.installed[index]
                InstalledModCard(
                    entry = entry,
                    busy = controller.busyModId == entry.name,
                    onToggle = { enabled -> controller.setEnabled(entry, enabled) },
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader(
                    title = "下载的模组包（${controller.packages.size}）",
                    subtitle = controller.downloadsPath,
                )
            }
            if (controller.packages.isEmpty()) {
                item { EmptyHint("还没有下载的模组包。去市场下载，或用上面的「选择 ZIP 检查」。") }
            }
            items(controller.packages.size) { index ->
                val packageFile = controller.packages[index]
                PackageCard(
                    packageFile = packageFile,
                    busy = controller.checking,
                    onCheck = { controller.checkPackage(packageFile.file) },
                    onDelete = { controller.deletePackage(packageFile.file) },
                )
            }
        }
    }

    if (controller.checking) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在检查") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(controller.checkingName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = { },
        )
    }

    val report = controller.report
    if (showReport && report != null) {
        ModalBottomSheet(
            onDismissRequest = {
                showReport = false
                controller.dismissReport()
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            ) {
                PackageReportContent(report)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { controller.installCheckedPackage() },
                    enabled = controller.checkedPackage != null && !controller.installing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (controller.installing) "安装中…" else "安装到加载器") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        showReport = false
                        controller.dismissReport()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("完成") }
            }
        }
    }
}

@Composable
private fun WorkspaceCard(controller: ModsController, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
    ) {
        Text("模组工作区", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            controller.loaderRootPath,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "模组目录：${controller.loaderRootPath.trimEnd('/')}/mods",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append("已装 ${controller.installed.size} 个模组 · SDK ")
                append(controller.installedSdkVersion ?: "未知")
                // 直读不到不等于读不到：加载器在游戏私有目录里，正常路径就是经 Shizuku。
                val access = controller.loaderRootAccess
                val viaShizuku = controller.scanMode.startsWith("Shizuku")
                if (viaShizuku) {
                    append(" · 经 Shizuku 读取")
                } else if (access != null) {
                    append(" · ")
                    append(
                        when {
                            !access.exists -> "目录不存在"
                            !access.readable -> "不可读（缺权限）"
                            !access.writable -> "只读"
                            else -> "可读写"
                        },
                    )
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenSettings) { Text("修改目录") }
            OutlinedButton(onClick = controller::refreshAccess) { Text("重新检测") }
        }
    }
}

/** 没有「所有文件访问权限」时的提示：加载器目录在 /sdcard 下，读写它必须授权。 */
@Composable
private fun StorageAccessCard(controller: ModsController) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(14.dp),
    ) {
        Text(
            "需要「所有文件访问权限」",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "加载器目录在游戏的私有目录里，安卓只允许经 Shizuku 读写。没有这个权限时市场仍可浏览，" +
                "但读不到已装模组、也写不进加载器目录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = controller::requestStorageAccess) { Text("去授权") }
            OutlinedButton(onClick = controller::refreshAccess) { Text("已授权，重新检测") }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp),
    )
}

@Composable
private fun IssueCard(issue: ModIssue) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (issue.level) {
        ModIssue.Level.ERROR -> scheme.errorContainer to scheme.onErrorContainer
        ModIssue.Level.WARNING -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        ModIssue.Level.INFO -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .padding(12.dp),
    ) {
        Text(issue.modName, style = MaterialTheme.typography.labelLarge, color = content)
        Spacer(Modifier.height(2.dp))
        Text(issue.message, style = MaterialTheme.typography.bodySmall, color = content)
    }
}

@Composable
private fun InstalledModCard(entry: ModEntry, busy: Boolean, onToggle: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.shownName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!entry.enabled) {
                Pill("停用", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
            }
            Pill("v${entry.version.ifBlank { "?" }}")
            Spacer(Modifier.width(8.dp))
            Switch(checked = entry.enabled, enabled = !busy, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append(entry.id.ifBlank { entry.name })
                if (entry.author.isNotBlank()) append(" · ").append(entry.author)
                append(" · ").append(Formats.bytes(entry.sizeBytes))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.sdkVersion.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                "要求 SDK ${entry.sdkVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.dependencies.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                "依赖：" + entry.dependencies.joinToString("、") {
                    if (it.minVersion.isBlank()) it.id else "${it.id} ≥ ${it.minVersion}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                entry.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PackageCard(
    packageFile: DownloadedPackage,
    busy: Boolean,
    onCheck: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
    ) {
        Text(
            packageFile.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${Formats.bytes(packageFile.sizeBytes)} · ${Formats.dateTime(packageFile.modifiedEpochMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCheck, enabled = !busy) { Text("检查") }
            OutlinedButton(onClick = onDelete, enabled = !busy) { Text("删除") }
        }
    }
}
