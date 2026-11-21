package com.hiynet.aspatoys.ui.loader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import com.hiynet.aspatoys.ui.theme.ToysPageHeading
import com.hiynet.aspatoys.ui.theme.ToysSection
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.hiynet.aspatoys.ui.theme.toysPanel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.hiynet.aspatoys.data.updates.Updates
import com.hiynet.aspatoys.ui.settings.LoaderUpdateCard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.installer.LoaderInstaller
import com.hiynet.aspatoys.installer.RootShell
import com.hiynet.aspatoys.installer.ShizukuShell
import com.hiynet.aspatoys.ui.components.SkeletonLine
import com.hiynet.aspatoys.ui.mods.ModsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 日志保留的行数上限。
 *
 * 日志是渲染在普通 `Column` 里的，安装一次可能打几百行；不设上限的话每追加一行都要重组
 * 全部已有行，页面会越用越卡，而且整页长度无限增长。
 */
private const val MAX_LOG_LINES = 300

/**
 * 加载器页：加载器目录的状态（原来在模组页顶部的「模组工作区」）+ 装 / 还原。
 *
 * 所有设备操作都经 Shizuku（`shell` 身份）完成，所以第一步是授权，第二步才是安装。
 */
@Composable
fun LoaderScreen(
    mods: ModsController,
    settings: AppSettings,
    onModeChanged: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onLoaderChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val updates = remember { Updates.get(context) }
    var gamePackage by remember { mutableStateOf(settings.gamePackage) }
    val installer = remember(gamePackage) { LoaderInstaller(context.applicationContext, gamePackage) }
    var gamePackages by remember { mutableStateOf(emptyList<String>()) }
    var showGamePackages by remember { mutableStateOf(false) }
    var forceChannelCompatibility by remember { mutableStateOf(settings.forceChannelCompatibility) }
    var pendingAction by remember { mutableStateOf<String?>(null) }
    val permissionVersion = ShizukuShell.permissionVersion

    var status by remember(gamePackage) { mutableStateOf<LoaderInstaller.Status?>(null) }
    var busy by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }

    /** 追加一行日志；只保留最近 [MAX_LOG_LINES] 行。 */
    fun appendLog(line: String) {
        logs = (logs + line).takeLast(MAX_LOG_LINES)
    }
    var mode by remember { mutableStateOf(settings.privilegeMode) }
    val operationBusy = busy || updates.loaderBusy

    fun selectGame(next: String) {
        settings.gamePackage = next
        gamePackage = next
        logs = emptyList()
        onLoaderChanged()
        mods.reloadWorkspace()
        updates.refreshInstalled()
    }

    fun refresh() {
        val persisted = settings.gamePackage
        if (persisted != gamePackage) {
            gamePackage = persisted
            return
        }
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                runCatching { installer.status() }.getOrDefault(LoaderInstaller.Status(note = "状态读取失败"))
            }
            status = snapshot
            if (snapshot.shizukuGranted) {
                gamePackages = withContext(Dispatchers.IO) {
                    runCatching { LoaderInstaller.installedGamePackages() }.getOrDefault(emptyList())
                }
                val auto = LoaderInstaller.autoSelectPackage(gamePackages, gamePackage, settings.selectedGamePackage)
                if (auto != null && !operationBusy) {
                    selectGame(auto)
                }
            }
        }
    }

    fun run(action: String, block: ((String) -> Unit) -> LoaderInstaller.Status) {
        if (busy || updates.loaderBusy) return
        busy = true
        logs = emptyList()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    block { line -> appendLog(line) }
                }
                status = result
                appendLog("—— $action 完成 ——")
                // 装/还原都会改动加载器目录，模组页下次进入要重扫。
                onLoaderChanged()
            } catch (error: Throwable) {
                appendLog("失败：" + (error.message ?: error.toString()))
            } finally {
                busy = false
                refresh()
                updates.refreshInstalled()
            }
        }
    }

    LaunchedEffect(permissionVersion, gamePackage) { refresh() }
    LaunchedEffect(Unit) { mods.onEnter() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ToysPageHeading("加载器", "授权访问后，安装或管理游戏加载器。")

        ToysSection("访问权限") {
            PrivilegeModeCard(
                mode = mode,
                enabled = !operationBusy,
                onModeChanged = { next ->
                    mode = next
                    onModeChanged(next)
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (ShizukuShell.selectedMode() == AppSettings.PrivilegeRoot) {
                    OutlinedButton(
                        onClick = {
                            RootShell.reset()
                            refresh()
                        },
                        enabled = !operationBusy,
                    ) { Text("重新检测 Root") }
                } else {
                    OutlinedButton(onClick = { ShizukuShell.requestPermission() }, enabled = !operationBusy) {
                        Text("授权 Shizuku")
                    }
                }
                OutlinedButton(onClick = { refresh() }, enabled = !operationBusy) { Text("刷新") }
            }
        }

        ToysSection("游戏资源") {
            OutlinedButton(
                onClick = { showGamePackages = true },
                enabled = !operationBusy && gamePackages.any { it != gamePackage },
            ) { Text(gamePackage) }
            DropdownMenu(expanded = showGamePackages, onDismissRequest = { showGamePackages = false }) {
                gamePackages.forEach { candidate ->
                    DropdownMenuItem(text = { Text(candidate) }, onClick = {
                        showGamePackages = false
                        selectGame(candidate)
                    })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("实验性强制兼容渠道资源", style = MaterialTheme.typography.titleSmall)
                    Text("允许安装未收录的渠道缓存。必要时使用 3.3.0 基准，可能影响登录或运行。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = forceChannelCompatibility, enabled = !operationBusy, onCheckedChange = {
                    settings.forceChannelCompatibility = it
                    forceChannelCompatibility = it
                })
            }
        }

        LoaderUpdateCard(
            enabled = !busy,
            onUpdated = { refresh(); onLoaderChanged() },
        ) {
            Text("操作前请完全退出游戏。禁用会保留文件；卸载会删除模组、配置和预设。",
                style = MaterialTheme.typography.bodySmall)
            Button(modifier = Modifier.fillMaxWidth(),
                enabled = !operationBusy && status?.shizukuGranted == true,
                onClick = { pendingAction = "安装内置加载器" }) { Text("安装内置加载器") }
            val canToggle = status?.let { it.supportedVersion && it.loaderPayload } == true
            val toggleLabel = if (status?.injected == true) "禁用加载器" else "启用加载器"
            OutlinedButton(modifier = Modifier.fillMaxWidth(),
                enabled = !operationBusy && canToggle,
                onClick = { pendingAction = toggleLabel }) { Text(toggleLabel) }
            OutlinedButton(modifier = Modifier.fillMaxWidth(),
                enabled = !operationBusy && status?.hasBackup == true,
                onClick = { pendingAction = "卸载加载器" }) { Text("卸载加载器") }
            AnimatedVisibility(visible = busy, enter = fadeIn() + slideInVertically { -it / 3 }, exit = fadeOut()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("执行中…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Column(Modifier.fillMaxWidth().toysPanel().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("内置悬浮窗", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "游戏内头像入口，提供模组操作与变速控制。每次启动为 1 倍，仅通过悬浮窗变速。窗口跟随设置中的主题；无需系统悬浮窗权限。更新后请完全重启游戏。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { run("安装 / 更新悬浮窗") { log -> installer.installOverlay(log) } },
                enabled = !operationBusy && (status?.shizukuGranted == true),
            ) { Text("安装 / 更新悬浮窗") }
        }

        ToysSection("运行状态") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(0.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val current = status
                if (current == null) {
                    // 第一次读状态要跑几条 shell 命令，先铺骨架，别让这一块空着。
                    repeat(4) { SkeletonLine(widthFraction = if (it % 2 == 0) 0.85f else 0.6f, height = 11.dp) }
                } else {
                    val rootMode = ShizukuShell.isRootMode()
                    val rootSelected = ShizukuShell.selectedMode() == AppSettings.PrivilegeRoot
                    StatusLine("提权方式", if (rootSelected) "Root" else "Shizuku")
                    StatusLine(
                        "提权状态",
                        when {
                            current.shizukuGranted -> "已就绪"
                            rootSelected -> "Root 不可用"
                            else -> "未授权"
                        },
                    )
                    StatusLine("缓存包", current.bundlePath ?: "未找到")
                    StatusLine("缓存包指纹", current.bundleSha256?.take(16) ?: "—")
                    StatusLine("缓存状态", current.cacheState)
                    StatusLine("载荷", if (current.loaderPayload) "已就位" else "缺失")
                    StatusLine("加载器", when {
                        current.injected -> "已启用"
                        current.supportedVersion && current.loaderPayload -> "已禁用（保留本地文件）"
                        else -> "未安装或状态未知"
                    })
                    StatusLine("原包恢复", if (current.hasBackup) "已校验原包可用" else "无可用恢复来源")
                    current.ownershipWarning?.let { warning ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (current.note.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            current.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (!rootMode) {
                        RootShell.lastError?.let { reason ->
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Root 检测：$reason",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        WorkspaceCard(mods = mods, onOpenSettings = onOpenSettings)

        AnimatedVisibility(visible = logs.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Column {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        logs.forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
    pendingAction?.let { action ->
        AlertDialog(onDismissRequest = { pendingAction = null },
            title = { Text("$action？") },
            text = { Text(when (action) {
                "卸载加载器" -> "将先恢复并校验游戏原包，再永久删除加载器、全部模组、配置、皮肤、预设、日志和安装备份。请先完全退出游戏。"
                "禁用加载器" -> "暂停游戏内加载，保留所有模组、配置和预设。请先完全退出游戏；下次启动生效。"
                "启用加载器" -> "恢复游戏内加载，继续使用现有加载器版本、模组和配置。请先完全退出游戏。"
                else -> "安装 APK 内置版本；会覆盖 SDK 和内置模组，已有用户配置保留。请先完全退出游戏。"
            } + "\n\n游戏：$gamePackage") },
            confirmButton = { TextButton(enabled = !operationBusy, onClick = {
                pendingAction = null
                run(action) { log -> when (action) {
                    "卸载加载器" -> installer.uninstall(log)
                    "禁用加载器" -> installer.setEnabled(false, log)
                    "启用加载器" -> installer.setEnabled(true, log)
                    else -> installer.install(log)
                } }
            }) { Text(if (action == "卸载加载器") "永久卸载" else "已退出游戏，继续") } },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("取消") } })
    }

}

/**
 * 提权方式选择：Shizuku 模式 / Root 模式。
 *
 * 有 Root 的设备切到 Root 模式后，安装与读写不再依赖 Shizuku 服务。
 */
@Composable
private fun PrivilegeModeCard(
    mode: String,
    enabled: Boolean,
    onModeChanged: (String) -> Unit,
) {
    val rootMode = mode == AppSettings.PrivilegeRoot
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("提权方式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                enabled = enabled,
                selected = !rootMode,
                onClick = { onModeChanged(AppSettings.PrivilegeShizuku) },
                label = { Text("Shizuku 模式") },
            )
            FilterChip(
                enabled = enabled,
                selected = rootMode,
                onClick = { onModeChanged(AppSettings.PrivilegeRoot) },
                label = { Text("Root 模式") },
            )
        }
        Text(
            if (rootMode) {
                "通过 su 直接读写游戏私有目录，不需要 Shizuku 服务和授权。"
            } else {
                "需要启动并授权 Shizuku。若系统限制游戏目录属主，请以 Root 启动 Shizuku，或切换 Root 模式。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 加载器目录的状态卡：路径、已装数量、SDK 版本、读取方式。
 *
 * 这些是加载器的属性，原来挤在模组页顶部，把模组列表推得太靠下。
 */
@Composable
private fun WorkspaceCard(mods: ModsController, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .toysPanel(20)
            .padding(14.dp),
    ) {
        Text("加载器目录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            mods.loaderRootPath,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "模组目录：${mods.loaderRootPath.trimEnd('/')}/mods",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append("已装 ${mods.installed.size} 个模组 · SDK ")
                append(mods.installedSdkVersion ?: "未知")
                if (mods.scanMode.isNotBlank() && !mods.scanMode.startsWith("直读")) {
                    append(" · 经 ").append(mods.scanMode).append(" 读取")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenSettings) { Text("修改目录") }
            OutlinedButton(onClick = mods::refreshAccess) { Text("重新检测") }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
