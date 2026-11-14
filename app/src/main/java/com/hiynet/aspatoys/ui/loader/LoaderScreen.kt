package com.hiynet.aspatoys.ui.loader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.installer.LoaderInstaller
import com.hiynet.aspatoys.installer.ShizukuShell
import com.hiynet.aspatoys.ui.components.SkeletonLine
import com.hiynet.aspatoys.ui.mods.ModsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 加载器页：加载器目录的状态（原来在模组页顶部的「模组工作区」）+ 装 / 还原。
 *
 * 所有设备操作都经 Shizuku（`shell` 身份）完成，所以第一步是授权，第二步才是安装。
 */
@Composable
fun LoaderScreen(
    installer: LoaderInstaller,
    mods: ModsController,
    onOpenSettings: () -> Unit,
    onLoaderChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val permissionVersion = ShizukuShell.permissionVersion

    var status by remember { mutableStateOf<LoaderInstaller.Status?>(null) }
    var busy by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }

    fun refresh() {
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching { installer.status() }.getOrDefault(LoaderInstaller.Status(note = "状态读取失败"))
            }
        }
    }

    fun run(action: String, block: ((String) -> Unit) -> LoaderInstaller.Status) {
        if (busy) return
        busy = true
        logs = emptyList()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    block { line -> logs = logs + line }
                }
                status = result
                logs = logs + "—— $action 完成 ——"
                // 装/还原都会改动加载器目录，模组页下次进入要重扫。
                onLoaderChanged()
            } catch (error: Throwable) {
                logs = logs + ("失败：" + (error.message ?: error.toString()))
            } finally {
                busy = false
                refresh()
            }
        }
    }

    LaunchedEffect(permissionVersion) { refresh() }
    LaunchedEffect(Unit) { mods.onEnter() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("加载器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        WorkspaceCard(mods = mods, onOpenSettings = onOpenSettings)

        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("注入状态", style = MaterialTheme.typography.titleSmall)
                val current = status
                if (current == null) {
                    // 第一次读状态要跑几条 shell 命令，先铺骨架，别让这一块空着。
                    repeat(4) { SkeletonLine(widthFraction = if (it % 2 == 0) 0.85f else 0.6f, height = 11.dp) }
                } else {
                    StatusLine("Shizuku 服务", if (current.shizukuAlive) "运行中" else "未运行")
                    StatusLine("Shizuku 授权", if (current.shizukuGranted) "已授权" else "未授权")
                    StatusLine("缓存包", current.bundlePath ?: "未找到")
                    StatusLine("缓存包指纹", current.bundleSha256?.take(16) ?: "—")
                    StatusLine("加载器", if (current.injected) "已注入" else "原版")
                    StatusLine("载荷", if (current.loaderPayload) "已就位" else "缺失")
                    StatusLine("本地备份", if (current.hasBackup) "有" else "无")
                    if (current.note.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            current.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { ShizukuShell.requestPermission() }, enabled = !busy) { Text("授权 Shizuku") }
            OutlinedButton(onClick = { refresh() }, enabled = !busy) { Text("刷新") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { run("安装") { log -> installer.install(log) } },
                enabled = !busy && (status?.shizukuGranted == true),
            ) { Text("安装加载器") }
            OutlinedButton(
                onClick = { run("还原") { log -> installer.restore(log) } },
                enabled = !busy && (status?.hasBackup == true),
            ) { Text("还原原包") }
        }

        AnimatedVisibility(
            visible = busy,
            enter = fadeIn() + slideInVertically { -it / 3 },
            exit = fadeOut(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                Text("执行中…", style = MaterialTheme.typography.bodySmall)
            }
        }

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
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
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
                if (mods.scanMode.startsWith("Shizuku")) append(" · 经 Shizuku 读取")
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
