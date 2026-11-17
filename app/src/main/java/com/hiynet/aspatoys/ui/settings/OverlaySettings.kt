package com.hiynet.aspatoys.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.data.AppContainer
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.data.settings.OverlayGroup
import com.hiynet.aspatoys.data.settings.OverlayOptions
import com.hiynet.aspatoys.data.settings.OverlayOptionsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OverlaySettings(settings: AppSettings) {
    val context = LocalContext.current.applicationContext
    val store = remember { OverlayOptionsStore(context) }
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(OverlayOptions()) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf("") }
    var mods by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var newName by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<String?>(null) }
    val root = settings.loaderRootPath
    suspend fun load() {
        busy = true
        try {
            value = withContext(Dispatchers.IO) { store.read(root) }
            mods = withContext(Dispatchers.IO) {
                AppContainer(context).loader.scan().entries.map { it.id to it.shownName }
                    .filter { it.first != "SpeedHackMod" }.distinctBy { it.first }
            }
        } catch (error: Exception) { notice = error.message ?: "读取失败。" }
        finally { busy = false }
    }
    LaunchedEffect(root) { load() }
    fun save() {
        busy = true
        val snapshot = value.normalized()
        scope.launch {
            try {
                val synced = withContext(Dispatchers.IO) { store.save(root, snapshot) }
                value = snapshot
                notice = if (synced) "已同步到游戏，约一秒后生效。首次使用新版请先更新悬浮窗并重启游戏。"
                    else "已保存在 APK，连接 Shizuku / Root 并安装加载器后，重开应用即可同步。"
            } catch (error: Exception) { notice = "已保存在 APK；同步失败：${error.message}。连接后会重试。" }
            finally { busy = false }
        }
    }
    Text("侧栏分组与统一大小", style = MaterialTheme.typography.titleLarge)
    Text("主框为正方形，与独立侧栏等高。透明度请在游戏悬浮窗侧栏的「设置」中调整。")
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("统一大小 ${(value.width * 100).toInt()}%")
        Slider(value.width, { value = value.copy(width = it, height = it) }, enabled = !busy, valueRange = 0.65f..1.6f)
        OutlinedButton(enabled = !busy, onClick = { value = value.copy(width = 1f, height = 1f) }) { Text("恢复默认大小") }
        Text("自定义分组", style = MaterialTheme.typography.titleMedium)
        Text("变速为固定入口，未分配的模组显示在「其他」；分组顺序就是侧栏顺序。")
        value.groups.forEachIndexed { index, group ->
            OutlinedTextField(group.name, { text ->
                value = value.copy(groups = value.groups.toMutableList().apply { set(index, group.copy(name = text.take(30))) })
            }, enabled = !busy, label = { Text("分组 ${index + 1}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy && index > 0, onClick = {
                    value = value.copy(groups = value.groups.toMutableList().apply { add(index - 1, removeAt(index)) })
                }) { Text("上移") }
                TextButton(enabled = !busy && index < value.groups.lastIndex, onClick = {
                    value = value.copy(groups = value.groups.toMutableList().apply { add(index + 1, removeAt(index)) })
                }) { Text("下移") }
                TextButton(enabled = !busy, onClick = { value = value.copy(groups = value.groups.filterIndexed { i, _ -> i != index }) }) { Text("删除分组") }
            }
        }
        OutlinedTextField(newName, { newName = it.take(30) }, enabled = !busy, label = { Text("新分组名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = !busy && value.groups.size < 8 && newName.isNotBlank(), onClick = {
            value = value.copy(groups = value.groups + OverlayGroup(newName.trim())); newName = ""
        }) { Text("添加分组（最多 8 个）") }
        val available = (mods + value.groups.flatMap { group -> group.mods.map { it to it } }).distinctBy { it.first }.sortedBy { it.second }
        if (available.isEmpty()) Text("连接加载器后点「重新读取」，即可为已安装模组分组。")
        available.forEach { (id, name) ->
            val assigned = value.groups.indexOfFirst { id in it.mods }
            Column {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(id, style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(enabled = !busy, onClick = { menu = id }) { Text(value.groups.getOrNull(assigned)?.name ?: "其他") }
                    DropdownMenu(expanded = menu == id, onDismissRequest = { menu = null }) {
                        (listOf(-1) + value.groups.indices).forEach { index ->
                            DropdownMenuItem(text = { Text(value.groups.getOrNull(index)?.name ?: "其他") }, onClick = {
                                value = value.copy(groups = value.groups.mapIndexed { i, group ->
                                    group.copy(mods = group.mods.filterNot { it == id } + if (i == index) listOf(id) else emptyList())
                                }); menu = null
                            })
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = ::save) { Text("保存并同步") }
            OutlinedButton(enabled = !busy, onClick = { scope.launch { load() } }) { Text("重新读取") }
        }
        if (notice.isNotEmpty()) Text(notice)
    }
}
