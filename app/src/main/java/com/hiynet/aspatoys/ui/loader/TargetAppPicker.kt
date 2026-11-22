package com.hiynet.aspatoys.ui.loader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.data.apps.InstalledAppRepository
import com.hiynet.aspatoys.data.apps.TargetApp
import com.hiynet.aspatoys.data.apps.TargetApps
import com.hiynet.cesiumloader.core.AndroidPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 选择目标游戏。
 *
 * 列表来自系统里**全部已安装应用**（`QUERY_ALL_PACKAGES`），默认视图只做排序（"推荐"），
 * 找不到就切「全部应用」，再不行还能从文件夹选或者手动写包名 —— 渠道服的包名我们事先并不知道，
 * 任何一层都不该把用户堵死。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetAppPicker(
    visible: Boolean,
    currentPackage: String,
    repository: InstalledAppRepository,
    onSelect: (String) -> Unit,
    onPickFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    var manual by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("选择目标游戏", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "加载器会装到所选应用的私有目录。同一时间只管理一个游戏，随时可以回来切换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TargetAppList(
                repository = repository,
                currentPackage = currentPackage,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth().weight(1f),
                footer = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPickFolder) { Text("从文件夹选择") }
                        TextButton(onClick = { manual = true }) { Text("手动输入包名") }
                    }
                },
            )
        }
    }
    if (manual) {
        ManualPackageDialog(
            initial = currentPackage,
            onConfirm = { manual = false; onSelect(it) },
            onDismiss = { manual = false },
        )
    }
}

/**
 * 应用列表本体（弹层和首次引导共用）。[modifier] 必须有确定高度（`weight` / `fillMaxHeight`），
 * 里面是一个 `LazyColumn`。
 */
@Composable
fun TargetAppList(
    repository: InstalledAppRepository,
    currentPackage: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    var apps by remember { mutableStateOf(repository.snapshot()) }
    var loading by remember { mutableStateOf(apps == null) }
    var query by remember { mutableStateOf("") }
    // 默认就是全量：渠道服的包名事先并不知道，"推荐"只能用来缩小范围，不能当默认视图。
    var filter by remember { mutableStateOf(TargetApps.Filter.All) }

    LaunchedEffect(Unit) {
        // 有缓存就先画出来，再后台对齐一次；没有就转圈等第一次枚举。
        val fresh = withContext(Dispatchers.IO) { runCatching { repository.refresh() }.getOrNull() }
        if (fresh != null) apps = fresh
        loading = false
    }

    val visible = remember(apps, filter, query) { TargetApps.visible(apps.orEmpty(), filter, query) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索应用名或包名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter == TargetApps.Filter.Recommended,
                onClick = { filter = TargetApps.Filter.Recommended },
                label = { Text("推荐") },
            )
            FilterChip(
                selected = filter == TargetApps.Filter.All,
                onClick = { filter = TargetApps.Filter.All },
                label = { Text("全部应用（${apps?.size ?: 0}）") },
            )
        }
        when {
            loading -> Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("正在读取已安装应用…", style = MaterialTheme.typography.bodySmall)
            }

            visible.isEmpty() -> Text(
                "没有匹配的应用。切到「全部应用」，或者从文件夹选择 / 手动输入包名。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible, key = { it.packageName }) { app ->
                TargetAppRow(
                    app = app,
                    repository = repository,
                    selected = app.packageName == currentPackage,
                    onSelect = onSelect,
                )
            }
        }
        footer?.invoke()
    }
}

@Composable
private fun TargetAppRow(
    app: TargetApp,
    repository: InstalledAppRepository,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val icon = rememberAppIcon(repository, app.packageName)
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onSelect(app.packageName) },
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (selected) scheme.primary.copy(alpha = 0.55f) else scheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = scheme.surfaceContainerHighest,
                modifier = Modifier.size(38.dp),
            ) {
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = null, modifier = Modifier.fillMaxSize().padding(5.dp))
                } else {
                    Box(Modifier.fillMaxSize())
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.displayLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (selected) {
                        Text(
                            "当前",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 最多四个徽标，窄屏一行放不下；用 FlowRow 自己换行，别顶出卡片外。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (app.isDefault) TargetBadge("默认")
                    if (!app.installed) TargetBadge("未安装", warn = true)
                    if (app.hasLoader) TargetBadge("已装加载器")
                    if (!app.knownChannel && app.installed) TargetBadge("未收录渠道", warn = true)
                }
            }
            app.versionName.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.width(56.dp),
                )
            }
        }
    }
}

@Composable
private fun TargetBadge(text: String, warn: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (warn) scheme.errorContainer else scheme.surfaceContainerHighest,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (warn) scheme.onErrorContainer else scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** 列表里没有（比如还没装出包名、或者被系统的可见性规则挡住）时的最后一条路。 */
@Composable
private fun ManualPackageDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(if (AndroidPaths.isValidPackageName(initial)) initial else "") }
    val trimmed = value.trim()
    val valid = AndroidPaths.isValidPackageName(trimmed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动输入包名") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("包名") },
                    singleLine = true,
                    placeholder = { Text("com.feimo.astralparty") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (trimmed.isEmpty()) "例如 com.feimo.astralparty 或某个渠道服的包名。"
                    else if (valid) "将把加载器装到 $trimmed。"
                    else "包名要像 com.example.game 这样，不能是本应用自己。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(trimmed) }, enabled = valid) { Text("使用这个包名") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun rememberAppIcon(repository: InstalledAppRepository, packageName: String): ImageBitmap? =
    remember(packageName) { runCatching { repository.icon(packageName)?.toImageBitmap() }.getOrNull() }

/** Drawable → Compose 位图；图标一律是自适应图标，按实际尺寸画一遍就行。 */
private fun Drawable.toImageBitmap(): ImageBitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap.asImageBitmap()
    val width = intrinsicWidth.takeIf { it > 0 } ?: DefaultIconSize
    val height = intrinsicHeight.takeIf { it > 0 } ?: DefaultIconSize
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}

private const val DefaultIconSize = 96
