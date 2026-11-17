package com.hiynet.aspatoys.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hiynet.aspatoys.BuildConfig
import com.hiynet.aspatoys.data.updates.GitHubUpdates
import com.hiynet.aspatoys.data.updates.Updates

@Composable
fun UpdateAutomation() {
    val context = LocalContext.current
    val updates = remember { Updates.get(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) updates.foreground(true)
            if (event == Lifecycle.Event.ON_PAUSE) updates.foreground(false)
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) updates.foreground(true)
        onDispose { lifecycle.removeObserver(observer); updates.foreground(false) }
    }
}

@Composable
fun UpdateSettings() {
    val context = LocalContext.current
    val updates = remember { Updates.get(context) }
    val uri = LocalUriHandler.current
    var custom by remember { mutableStateOf(updates.customMirror) }
    var error by remember { mutableStateOf("") }
    Text("软件更新", style = MaterialTheme.typography.titleLarge)
    Text("当前 ${BuildConfig.VERSION_NAME} · ${updates.appRelease?.version?.let { "GitHub 最新 $it" } ?: "尚未检查"}")
    Text(updates.appStatus)
    if (updates.appBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !updates.appBusy, onClick = { updates.checkApp() }) { Text("检查更新") }
        OutlinedButton(enabled = !updates.appBusy && updates.appRelease?.let { GitHubUpdates.newer(it.version, BuildConfig.VERSION_NAME) } == true,
            onClick = { updates.downloadApp() }) { Text("下载更新") }
        Button(enabled = updates.ready && !updates.appBusy, onClick = { updates.installApp() }) { Text("安装更新") }
        if (updates.appBusy) TextButton(onClick = { updates.cancelApp() }) { Text("取消下载 / 检查") }
    }
    Text("安装会交给 Android 系统确认。首次安装更新需允许本应用安装未知来源应用；不会卸载旧版或清空数据。")
    UpdateToggle("自动检查更新", "每次打开应用时后台检查", updates.autoCheck) { updates.preference("autoCheck", it) }
    UpdateToggle("自动下载更新", "发现新版本后下载并校验 APK", updates.autoDownload) { updates.preference("autoDownload", it) }
    UpdateToggle("自动打开安装", "下载完成且应用在前台时打开系统安装界面", updates.autoInstall) { updates.preference("autoInstall", it) }
    TextButton(onClick = { uri.openUri("https://github.com/${GitHubUpdates.APP_REPO}/releases/latest") }) { Text("GitHub / 更新日志") }
    updates.appRelease?.notes?.takeIf(String::isNotBlank)?.let { Text(it.take(12_000), style = MaterialTheme.typography.bodySmall) }
    HorizontalDivider()
    Text("GitHub 下载源", style = MaterialTheme.typography.titleMedium)
    Text("与 Win 版相同的镜像选项，供软件和加载器更新共用。")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("direct" to "GitHub 直连", "gh-proxy" to "gh-proxy.com", "ghfast" to "ghfast.top").forEach { (type, label) ->
            FilterChip(selected = updates.mirrorType == type, enabled = !updates.appBusy && !updates.loaderBusy, onClick = { updates.mirror(type, custom) }, label = { Text(label) })
        }
    }
    OutlinedTextField(custom, { custom = it }, label = { Text("自定义 HTTPS 镜像前缀") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        enabled = !updates.appBusy && !updates.loaderBusy)
    OutlinedButton(enabled = !updates.appBusy && !updates.loaderBusy, onClick = {
        runCatching { updates.mirror("custom", custom) }.onSuccess { error = "" }.onFailure { error = it.message.orEmpty() }
    }) { Text("使用自定义镜像") }
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun UpdateToggle(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title); Text(detail, style = MaterialTheme.typography.bodySmall) }
        Switch(value, onCheckedChange = onChange)
    }
}

@Composable
fun LoaderUpdateCard(onUpdated: () -> Unit) {
    val context = LocalContext.current
    val updates = remember { Updates.get(context) }
    val uri = LocalUriHandler.current
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { updates.refreshInstalled() }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CesiumLoader", style = MaterialTheme.typography.titleMedium)
            Text("已装 ${updates.installedLoader.ifBlank { "版本未知" }} · ${updates.loaderRelease?.let { "GitHub 最新 ${it.version}" } ?: "尚未检查"}")
            Text(updates.loaderStatus, style = MaterialTheme.typography.bodySmall)
            if (updates.loaderBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !updates.loaderBusy, onClick = { updates.checkLoader() }) { Text("检查 GitHub 最新版") }
                Button(enabled = !updates.loaderBusy && updates.loaderRelease != null, onClick = { confirm = true }) {
                    Text(updates.loaderRelease?.let { "更新到 ${it.version}" } ?: "从 GitHub 更新")
                }
                TextButton(onClick = { uri.openUri("https://github.com/${GitHubUpdates.LOADER_REPO}/releases/latest") }) { Text("GitHub") }
                if (updates.loaderBusy) TextButton(onClick = { updates.cancelLoader() }) { Text("取消下载") }
            }
            Text("保留安卓悬浮窗、变速和自由相机；官方包中的同名模组不会覆盖它们。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("更新加载器？") }, text = {
        Text("请先完全退出游戏。更新官方 SDK 和模组，保留 Android 引导、游戏注入与现有配置。完成后需重启游戏。")
    }, confirmButton = { TextButton(onClick = { confirm = false; updates.updateLoader(onUpdated) }) { Text("已退出游戏，开始更新") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
