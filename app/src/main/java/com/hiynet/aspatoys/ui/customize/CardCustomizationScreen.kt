package com.hiynet.aspatoys.ui.customize

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.data.cards.CardPackageStore
import com.hiynet.aspatoys.ui.components.ConfirmDialog
import com.hiynet.aspatoys.ui.components.Operations
import com.hiynet.aspatoys.ui.theme.ToysPageHeading
import com.hiynet.aspatoys.ui.theme.toysPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CardCustomizationScreen(controller: CardCustomizationController, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { controller.enter() }
    var create by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var delete by rememberSaveable { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var replacementKey by rememberSaveable { mutableStateOf<String?>(null) }
    var replacementProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var addCard by rememberSaveable { mutableStateOf(false) }
    var restore by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreDefault by rememberSaveable { mutableStateOf(false) }
    var binding by rememberSaveable { mutableStateOf("") }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) controller.importZip(it) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = exportId
        if (uri != null && id != null) controller.export(id, uri)
        exportId = null
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = replacementProfile; val key = replacementKey
        if (uri != null && id != null && key != null) controller.replace(id, key, uri)
        replacementKey = null; replacementProfile = null
    }
    fun replace(id: String, key: String) {
        replacementProfile = id; replacementKey = key
        imagePicker.launch(arrayOf("image/*"))
    }
    val editing = controller.editing
    BackHandler(enabled = editing != null) { controller.closeEditor() }
    Column(modifier.fillMaxSize().testTag("customize.screen")) {
        if (editing == null) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { ToysPageHeading("自定义", "导入 Win 版卡牌方案，或为卡牌换一张图。") }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = true, onClick = {}, label = { Text("卡牌") })
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = controller::refresh, enabled = !controller.busy) { Text("刷新") }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { importPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !controller.busy,
                            modifier = Modifier.weight(1f).testTag("cards.import")) { Text("导入方案 ZIP") }
                        OutlinedButton(onClick = { name = ""; create = true }, enabled = !controller.busy,
                            modifier = Modifier.weight(1f).testTag("cards.create")) { Text("新建方案") }
                    }
                }
                item {
                    Text("支持 Win 版导出的图片方案和动态卡面序列。应用方案后请完全重启游戏。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                controller.connectionProblem?.let { problem -> item {
                    Text(problem, Modifier.fillMaxWidth().toysPanel().padding(14.dp), style = MaterialTheme.typography.bodySmall)
                } }
                item {
                    // 会改游戏里的卡面，先确认。
                    DefaultProfile(selected = controller.selected == CardPackageStore.GameDefault, enabled = !controller.busy && controller.connectionProblem == null) {
                        restoreDefault = true
                    }
                }
                items(controller.profiles.chunked(2), key = { pair -> pair.first().id }) { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { profile ->
                            ProfileTile(profile, controller.cover(profile.id), controller.selected == profile.id, controller.pending(profile.id), !controller.busy,
                                Modifier.weight(1f), onOpen = { controller.edit(profile.id) },
                                onApply = { controller.apply(profile.id) },
                                onExport = { exportId = profile.id; exportPicker.launch("${profile.id}.zip") },
                                onDelete = { delete = profile.id })
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (controller.profiles.isEmpty() && !controller.busy) item {
                    Column(Modifier.fillMaxWidth().toysPanel().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("还没有自定义卡面", style = MaterialTheme.typography.titleMedium)
                        Text("导入已有方案，或新建方案后为卡牌选择图片。未替换的卡面继续使用游戏默认图片。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        } else {
            Editor(controller, editing, Modifier.weight(1f),
                onReplace = { replace(editing, it) },
                onRestore = { restore = it },
                onAdd = { binding = ""; addCard = true },
                onExport = { exportId = editing; exportPicker.launch("$editing.zip") })
        }
        // 走进度面板的操作（导入、应用、导出、删除）由面板 / 胶囊显示进度，这里不再重复一条。
        if (controller.busy && !Operations.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("cards.progress"))
            Text(controller.progress, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
        }
        controller.notice?.let { message ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f).padding(vertical = 8.dp).testTag("cards.notice"), style = MaterialTheme.typography.bodySmall,
                    color = if (controller.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = controller::clearNotice) { Text("关闭") }
            }
        }
    }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("新建卡牌方案") }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("方案名称") }, singleLine = true, modifier = Modifier.testTag("cards.profile.name"))
    }, confirmButton = { TextButton(onClick = { create = false; controller.create(name) }, enabled = name.isNotBlank()) { Text("创建") } }, dismissButton = { TextButton(onClick = { create = false }) { Text("取消") } })
    delete?.let { id ->
        val inUse = controller.selected == id
        ConfirmDialog(
            title = "删除卡牌方案「${controller.profileName(id)}」？",
            lines = listOf(
                "删除这个方案在应用和游戏里的副本。",
                "其他方案和游戏原图不受影响。",
                "删除后找不回来。想留一份的话，先用「更多 → 导出 ZIP」备份。",
            ),
            footnote = if (inUse) "这个方案正在使用，要先「恢复默认」切回游戏默认卡面，才能删除。" else null,
            confirmLabel = "删除",
            danger = true,
            onConfirm = { delete = null; controller.remove(id) },
            onDismiss = { delete = null },
        )
    }
    if (restore != null && editing != null) ConfirmDialog(
        title = "恢复这张卡的原图？",
        lines = listOf(
            "移除这个方案对这张卡的替换图片。",
            "其他卡不受影响；应用到游戏后，这张卡显示游戏原图。",
            "替换用的图片会从方案里删掉，想再用要重新选择。",
        ),
        confirmLabel = "恢复原图",
        danger = true,
        onConfirm = { val key = restore!!; restore = null; controller.restoreCard(editing, key) },
        onDismiss = { restore = null },
    )
    if (restoreDefault) ConfirmDialog(
        title = "恢复游戏默认卡面？",
        lines = listOf(
            "停用外部卡面，游戏改用内置原图。",
            "你的卡牌方案都保留，随时可以重新应用。",
            "完成后完全重启游戏生效。",
        ),
        confirmLabel = "恢复默认",
        onConfirm = { restoreDefault = false; controller.apply(CardPackageStore.GameDefault) },
        onDismiss = { restoreDefault = false },
    )
    if (addCard && editing != null) AlertDialog(onDismissRequest = { addCard = false }, title = { Text("添加卡面") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("填写 Win 版使用的卡牌名称、数字 ID 或素材名，例如 UT_HandCard_10001。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(binding, onValueChange = { binding = it }, label = { Text("卡牌名称 / ID / 素材名") }, singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { addCard = false; replace(editing, binding.trim()) }, enabled = CardPackageStore.validKey(binding.trim())) { Text("选择图片") } }, dismissButton = { TextButton(onClick = { addCard = false }) { Text("取消") } })
}

@Composable
private fun DefaultProfile(selected: Boolean, enabled: Boolean, onApply: () -> Unit) {
    Row(Modifier.fillMaxWidth().toysPanel().border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("游戏默认", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (selected) "已选择 · 使用游戏内置卡面" else "停用外部卡面，恢复游戏原图", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onApply, enabled = enabled, modifier = Modifier.testTag("cards.default")) { Text("恢复默认") }
    }
}

@Composable
private fun ProfileTile(profile: CardPackageStore.Profile, cover: File?, selected: Boolean, pending: Boolean, enabled: Boolean, modifier: Modifier,
                        onOpen: () -> Unit, onApply: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier.toysPanel().border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))) {
        Box(Modifier.fillMaxWidth().height(120.dp).clickable(enabled = enabled, onClick = onOpen), contentAlignment = Alignment.Center) {
            CardPicture(cover, Modifier.fillMaxSize(), profile.name.take(1))
            if (selected || pending) Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(if (pending) "待应用" else "已选择", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${profile.cards} 张卡面" + if (profile.author.isNotBlank()) " · ${profile.author}" else "", style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onApply, enabled = enabled, modifier = Modifier.weight(1f).testTag("cards.apply.${profile.id}")) { Text("应用", maxLines = 1) }
                Box {
                    TextButton(onClick = { menu = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.width(44.dp)) { Text("更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("编辑卡面") }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text("导出 ZIP") }, onClick = { menu = false; onExport() })
                        DropdownMenuItem(text = { Text("删除方案") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}

@Composable
private fun Editor(controller: CardCustomizationController, id: String, modifier: Modifier, onReplace: (String) -> Unit, onRestore: (String) -> Unit, onAdd: () -> Unit, onExport: () -> Unit) {
    var search by rememberSaveable(id) { mutableStateOf("") }
    var category by rememberSaveable(id) { mutableStateOf("全部") }
    val profile = controller.profiles.firstOrNull { it.id == id }
    val categories = listOf("全部") + controller.editorCards.map { it.category }.distinct()
    val cards = controller.editorCards.filter { (category == "全部" || it.category == category) && (it.name.contains(search, true) || it.key.contains(search, true)) }
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = controller::closeEditor) { Text("‹ 方案列表") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onExport, enabled = !controller.busy) { Text("导出") }
        }
        Text(profile?.name ?: id, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("点卡牌换图；未替换的卡面使用游戏原图。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.apply(id) }, enabled = !controller.busy, modifier = Modifier.weight(1f)) { Text("应用到游戏") }
            OutlinedButton(onClick = onAdd, enabled = !controller.busy) { Text("添加卡面") }
        }
        OutlinedTextField(search, onValueChange = { search = it }, label = { Text("搜索卡牌名称 / ID") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("cards.search"))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            categories.forEach { item -> FilterChip(selected = category == item, onClick = { category = item }, label = { Text(item) }) }
        }
        if (cards.isEmpty() && !controller.busy) Text("没有匹配的卡面，可使用「添加卡面」。", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium)
        LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), modifier = Modifier.weight(1f).fillMaxWidth().testTag("cards.editor.grid"),
            contentPadding = PaddingValues(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            gridItems(cards, key = { it.key }) { card ->
                Column(Modifier.toysPanel().clickable(enabled = !controller.busy) { onReplace(card.binding) }.padding(8.dp)) {
                    CardPicture(card.file, Modifier.fillMaxWidth().height(115.dp), if (card.file == null) "原图" else "")
                    Text(card.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    if (card.file != null) TextButton(onClick = { onRestore(card.binding) }, enabled = !controller.busy, contentPadding = PaddingValues(0.dp)) { Text("恢复原图", style = MaterialTheme.typography.labelMedium) }
                    Text(if (card.animated) "动态卡面" else if (card.file != null) "已替换" else card.key, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CardPicture(file: File?, modifier: Modifier, fallback: String) {
    val modified = file?.lastModified()
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file?.path, modified) {
        value = withContext(Dispatchers.IO) {
            file?.takeIf(File::isFile)?.let { runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it.path, bounds)
                val sample = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 500).coerceAtLeast(1) }
                BitmapFactory.decodeFile(it.path, sample)?.asImageBitmap()
            }.getOrNull() }
        }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, contentDescription = "卡面预览", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Text(fallback, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
