package com.hiynet.aspatoys.ui.mods

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import com.hiynet.aspatoys.ui.components.SkeletonList
import com.hiynet.aspatoys.ui.format.Formats

/**
 * 模组页：已装模组（启用/停用、配置）、下载目录里的模组包、本地包检查与安装。
 *
 * 加载器目录的信息（路径、SDK、读取方式）挪到「加载器」页：那些是加载器的状态，
 * 放在模组列表上面只是挡路。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModsScreen(
    controller: ModsController,
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

    // 首次加载（还没有任何数据）用骨架屏，避免一片空白让人以为没有模组。
    val showSkeleton = controller.scanning && controller.installed.isEmpty()

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "已装模组（${controller.installed.size}）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            buildString {
                                append("经 ${controller.scanMode.ifBlank { "…" }} 读取")
                                controller.installedSdkVersion?.let { append(" · SDK ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = controller::refresh, enabled = !controller.scanning) {
                        Text(if (controller.scanning) "刷新中…" else "刷新")
                    }
                }
            }

            if (controller.scanError != null) {
                item {
                    MessageBanner(
                        text = controller.scanError!!,
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.animateContentSize(),
                    )
                }
            }

            if (!controller.allFilesAccess) {
                item { StorageAccessCard(controller) }
            }

            item {
                AnimatedVisibility(
                    visible = controller.message != null,
                    enter = fadeIn() + slideInVertically { -it / 2 },
                    exit = fadeOut() + slideOutVertically { -it / 2 },
                ) {
                    Column {
                        MessageBanner(
                            text = controller.message.orEmpty(),
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
                Button(onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                    Text("从文件安装模组包")
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

            if (showSkeleton) {
                item { SkeletonList(count = 5) }
            } else if (controller.installed.isEmpty()) {
                item { EmptyHint("还没有模组。去市场装一个，或用上面的「从文件安装模组包」。") }
            } else {
                items(controller.installed, key = { it.name.lowercase() }) { entry ->
                    InstalledModCard(
                        entry = entry,
                        busy = controller.busyModId == entry.name,
                        onToggle = { enabled -> controller.setEnabled(entry, enabled) },
                        onConfig = { controller.openConfig(entry) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader(
                    title = "下载的模组包（${controller.packages.size}）",
                    subtitle = controller.downloadsPath,
                )
            }
            if (controller.packages.isEmpty()) {
                item { EmptyHint("还没有下载的模组包。去市场点「下载并安装」就会出现在这里。") }
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

    val config = controller.configEditor
    if (config != null) {
        ModConfigSheet(
            controller = controller,
            state = config,
            onDismiss = controller::closeConfig,
        )
    }
}

/** 没有「所有文件访问权限」时的提示：加载器目录在游戏私有目录里，读写必须经 Shizuku。 */
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
private fun InstalledModCard(
    entry: ModEntry,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onConfig: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp)
            .animateContentSize(),
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
            AnimatedVisibility(visible = !entry.enabled) {
                Row {
                    Pill("停用", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                }
            }
            Pill("v${entry.version.ifBlank { "?" }}")
            Spacer(Modifier.width(8.dp))
            // 正在切换时只加一个小转圈，不把开关置灰：置灰的开关既分不清「在忙」还是「不可用」，
            // 也会让配色看起来像状态出错。重复点由控制器挡掉。
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Switch(checked = entry.enabled, onCheckedChange = onToggle)
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
        TextButton(onClick = onConfig, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text("设置")
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
