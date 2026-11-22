package com.hiynet.aspatoys.ui.mods

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.R
import com.hiynet.aspatoys.data.mods.ModEntry
import com.hiynet.aspatoys.data.mods.ModIssue
import com.hiynet.aspatoys.ui.components.MessageBanner
import com.hiynet.aspatoys.ui.components.Operations
import com.hiynet.aspatoys.ui.components.PackageReportContent
import com.hiynet.aspatoys.ui.components.Pill
import com.hiynet.aspatoys.ui.components.SkeletonList
import com.hiynet.aspatoys.ui.format.Formats
import com.hiynet.aspatoys.ui.theme.ToysMotion
import com.hiynet.aspatoys.ui.theme.ToysPageHeading
import com.hiynet.aspatoys.ui.theme.toysPanel
import com.hiynet.aspatoys.ui.theme.toysPressFeedback

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
    val configEditor = controller.configEditor

    AnimatedContent(
        targetState = configEditor,
        // 编辑器的全部状态都在 targetState 里（text / fields / dirty…），不指定 contentKey 时
        // 每敲一个字都会被当成一次页面切换：播放转场、重建内容、丢掉焦点与滚动位置。
        // 这里只把「切换模组」算作导航，编辑内容的变化原地更新。
        contentKey = { it?.modId },
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(ToysMotion.Enter)) { it / 5 } + fadeIn(tween(ToysMotion.Enter)))
                    .togetherWith(slideOutHorizontally(tween(ToysMotion.Exit)) { -it / 6 } + fadeOut(tween(ToysMotion.Fast)))
            } else {
                (slideInHorizontally(tween(ToysMotion.Enter)) { -it / 6 } + fadeIn(tween(ToysMotion.Enter)))
                    .togetherWith(slideOutHorizontally(tween(ToysMotion.Exit)) { it / 5 } + fadeOut(tween(ToysMotion.Fast)))
            }
        },
        label = "mods-navigation",
        modifier = modifier.fillMaxSize(),
    ) { currentConfig ->
        if (currentConfig != null) {
            ModConfigScreen(
                controller = controller,
                state = currentConfig,
                onDismiss = controller::closeConfig,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { ToysPageHeading("模组管理", "管理已装模组，或从本地导入模组包。") }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "已装模组（${controller.installed.size}）",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    buildString {
                                        append("经 ${controller.scanMode.ifBlank { "…" }} 读取")
                                        controller.installedSdkVersion?.let { append(" · SDK ").append(it) }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(
                                onClick = controller::refresh,
                                enabled = !controller.scanning,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text(if (controller.scanning) "刷新中…" else "刷新", style = MaterialTheme.typography.labelMedium)
                            }
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
                    Button(
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_extension),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("从本地文件安装模组包", style = MaterialTheme.typography.titleSmall)
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

    // 安装进度画在全局进度面板（Operations）里；检查报告等面板关掉再弹出，免得两层叠在一起。
    val report = controller.report
    if (showReport && report != null && Operations.current == null) {
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
    val scheme = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .toysPressFeedback(interactions)
            .clickable(
                interactionSource = interactions,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onConfig,
            )
            .animateContentSize(),
        shape = RoundedCornerShape(18.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 顶栏：模组主标题与副信息 + 启用开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.shownName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append(entry.id.ifBlank { entry.name })
                            if (entry.author.isNotBlank()) append(" · ").append(entry.author)
                            append(" · ").append(Formats.bytes(entry.sizeBytes))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = onToggle,
                    )
                }
            }

            // 标签栏：版本、状态（启用/停用）、平台（仅 Android / 仅 Windows）、要求 SDK
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Pill(
                    text = "v${entry.version.ifBlank { "?" }}",
                    container = scheme.secondaryContainer.copy(alpha = 0.8f),
                    content = scheme.onSecondaryContainer,
                )
                if (entry.enabled) {
                    Pill(
                        text = "已启用",
                        container = scheme.primaryContainer.copy(alpha = 0.6f),
                        content = scheme.onPrimaryContainer,
                    )
                } else {
                    Pill(
                        text = "已停用",
                        container = scheme.surfaceVariant,
                        content = scheme.onSurfaceVariant,
                    )
                }
                entry.platformBadge?.let { badge ->
                    Pill(
                        text = badge,
                        container = scheme.tertiaryContainer.copy(alpha = 0.8f),
                        content = scheme.onTertiaryContainer,
                    )
                }
                if (entry.sdkVersion.isNotBlank()) {
                    Pill(
                        text = "SDK ${entry.sdkVersion}",
                        container = scheme.surfaceVariant.copy(alpha = 0.7f),
                        content = scheme.onSurfaceVariant,
                    )
                }
            }

            // 说明文案（若有）
            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            // 底部辅助信息与操作区
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (entry.dependencies.isNotEmpty()) {
                    Text(
                        text = "依赖：" + entry.dependencies.joinToString("、") {
                            if (it.minVersion.isBlank()) it.id else "${it.id} ≥ ${it.minVersion}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                Spacer(Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onConfig,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_nav_settings),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = scheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "配置",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}


