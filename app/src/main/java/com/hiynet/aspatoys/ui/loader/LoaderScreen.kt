package com.hiynet.aspatoys.ui.loader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.installer.LoaderInstaller
import com.hiynet.aspatoys.installer.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 加载器页：装 / 还原。
 *
 * 所有设备操作都经 Shizuku（`shell` 身份）完成，所以第一步是授权，第二步才是安装。
 */
@Composable
fun LoaderScreen(installer: LoaderInstaller) {
    val scope = rememberCoroutineScope()
    val permissionVersion = ShizukuShell.permissionVersion

    var status by remember { mutableStateOf<LoaderInstaller.Status?>(null) }
    var busy by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }

    fun refresh() {
        scope.launch {
            status = withContext(Dispatchers.IO) { runCatching { installer.status() }.getOrDefault(LoaderInstaller.Status(note = "状态读取失败")) }
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
            } catch (error: Throwable) {
                logs = logs + ("失败：" + (error.message ?: error.toString()))
            } finally {
                busy = false
                refresh()
            }
        }
    }

    LaunchedEffect(permissionVersion) { refresh() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("加载器", style = MaterialTheme.typography.titleLarge)
        Text(
            "把引导调用注入游戏的热更缓存包，并把 SDK 与引导 DLL 放进加载器目录。需要 Shizuku。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("状态", style = MaterialTheme.typography.titleSmall)
                val current = status
                if (current == null) {
                    Text("读取中…", style = MaterialTheme.typography.bodySmall)
                } else {
                    StatusLine("Shizuku 服务", if (current.shizukuAlive) "运行中" else "未运行")
                    StatusLine("Shizuku 授权", if (current.shizukuGranted) "已授权" else "未授权")
                    StatusLine("缓存包", current.bundlePath ?: "未找到")
                    StatusLine("缓存包指纹", current.bundleSha256?.take(16) ?: "—")
                    StatusLine("加载器", if (current.injected) "已注入" else "原版")
                    StatusLine("载荷", if (current.loaderPayload) "已就位" else "缺失")
                    StatusLine("本地备份", if (current.hasBackup) "有" else "无")
                    if (current.note.isNotBlank()) {
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

        if (busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                Text("执行中…", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (logs.isNotEmpty()) {
            HorizontalDivider()
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
