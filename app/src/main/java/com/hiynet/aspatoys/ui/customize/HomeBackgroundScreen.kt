package com.hiynet.aspatoys.ui.customize

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.data.background.HomeBackgroundRepository
import com.hiynet.aspatoys.ui.components.ConfirmDialog
import com.hiynet.aspatoys.ui.components.Operations
import com.hiynet.aspatoys.ui.format.Formats
import com.hiynet.aspatoys.ui.theme.ToysPageHeading
import com.hiynet.aspatoys.ui.theme.toysPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HomeBackgroundScreen(
    controller: HomeBackgroundController,
    loaderVersion: Int,
    onOpenMarketMod: (String) -> Unit,
    onOpenLoader: () -> Unit,
    tabSelector: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(loaderVersion) { controller.enter() }
    var restoreConfirm by rememberSaveable { mutableStateOf(false) }
    var pendingPickerKind by rememberSaveable { mutableStateOf<String?>(null) }
    var legacyPickerKind by rememberSaveable { mutableStateOf<String?>(null) }

    val imageFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.setImage(uri)
    }
    val videoFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.setVideo(uri)
    }
    val imageGalleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) controller.setImage(uri)
    }
    val videoGalleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) controller.setVideo(uri)
    }
    val legacyGalleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        val kind = legacyPickerKind
        legacyPickerKind = null
        if (uri != null) {
            if (kind == "video") controller.setVideo(uri) else controller.setImage(uri)
        }
    }

    fun launchGallery(kind: String) {
        try {
            if (kind == "video") {
                videoGalleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            } else {
                imageGalleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        } catch (_: ActivityNotFoundException) {
            legacyPickerKind = kind
            val intent = Intent(Intent.ACTION_PICK).apply {
                type = if (kind == "video") "video/*" else "image/*"
            }
            legacyGalleryPicker.launch(intent)
        }
    }

    val status = controller.status
    val scheme = MaterialTheme.colorScheme

    Column(modifier.fillMaxSize().testTag("homebg.screen")) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { ToysPageHeading("自定义", "替换游戏主页的背景图片或视频。") }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (tabSelector != null) tabSelector()
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { controller.refresh() }, enabled = !controller.busy) { Text("刷新") }
                }
            }

            if (!status.loaderReady) {
                item {
                    Column(Modifier.fillMaxWidth().toysPanel().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("加载器通道未就绪", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            status.unavailableReason.ifBlank { "请先安装 CesiumLoader 并授予 Shizuku / Root 权限。" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Button(onClick = onOpenLoader) { Text("前往加载器") }
                    }
                }
            } else if (!status.modInstalled) {
                item {
                    Column(Modifier.fillMaxWidth().toysPanel().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("需要安装「自定义主页背景」模组", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "替换游戏主页图片或视频依赖市场中的 HomeBackgroundMod。安装后再回到这里即可一键导入背景视频或图片。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { onOpenMarketMod(HomeBackgroundRepository.ModId) },
                            modifier = Modifier.testTag("homebg.install_from_market"),
                        ) {
                            Text("前往市场安装")
                        }
                    }
                }
            } else {
                if (!status.modEnabled) {
                    item {
                        Column(Modifier.fillMaxWidth().toysPanel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("模组当前已停用", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "模组目前位于 disabled_mods，游戏不会加载自定义背景。启用或导入新背景时会自动移回启用目录。",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                            Button(onClick = controller::enableMod, enabled = !controller.busy) { Text("一键启用模组") }
                        }
                    }
                }

                if (status.needsVideoUpgrade) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().toysPanel().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "当前模组版本（${status.installedVersion.ifBlank { "未知" }}）视频解码较卡",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.error,
                            )
                            Text(
                                "旧版安卓视频背景在主线程做 CPU 软转码，可能导致卡顿掉帧。建议更新到 ${HomeBackgroundRepository.MinSmoothVideoVersion}+ 版本（后台硬解 + GPU 着色器直出）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = { onOpenMarketMod(HomeBackgroundRepository.ModId) }) {
                                Text("前往市场更新")
                            }
                        }
                    }
                }

                item {
                    StatusCard(status = status)
                }

                item {
                    Column(Modifier.fillMaxWidth().toysPanel().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("导入背景", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { pendingPickerKind = "image" },
                                enabled = !controller.busy,
                                modifier = Modifier.weight(1f).testTag("homebg.pick_image"),
                            ) {
                                Text("选择图片背景")
                            }
                            Button(
                                onClick = { pendingPickerKind = "video" },
                                enabled = !controller.busy,
                                modifier = Modifier.weight(1f).testTag("homebg.pick_video"),
                            ) {
                                Text("选择视频背景")
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("视频转码规格：", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            FilterChip(
                                selected = controller.videoMaxHeight == 720,
                                onClick = { controller.selectVideoResolution(720) },
                                enabled = !controller.busy,
                                label = { Text("720p 流畅推荐") },
                            )
                            FilterChip(
                                selected = controller.videoMaxHeight == 1080,
                                onClick = { controller.selectVideoResolution(1080) },
                                enabled = !controller.busy,
                                label = { Text("1080p 高清") },
                            )
                        }
                        Text(
                            "工具箱会自动将视频压成无音轨、保留原帧率（最高 60fps）的 H.264 MP4 再写入加载器；图片会自动转为 8-bit PNG 并清除旧视频。视频替换需完全重启游戏生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    Column(Modifier.fillMaxWidth().toysPanel().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("隐藏官方主题动画", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "停用游戏自带主页 CRI 动态背景，避免挡住自定义背景并节省电量。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = status.hideThemeVideo,
                                onCheckedChange = { controller.setHideThemeVideo(it) },
                                enabled = !controller.busy,
                            )
                        }
                        OutlinedButton(
                            onClick = { restoreConfirm = true },
                            enabled = !controller.busy && status.modEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("恢复游戏默认背景")
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }

        if (controller.busy && !Operations.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        controller.notice?.let { message ->
            Row(
                Modifier.fillMaxWidth().background(scheme.surfaceContainerLow).padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    Modifier.weight(1f).padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (controller.failed) scheme.error else scheme.onSurface,
                )
                TextButton(onClick = controller::clearNotice) { Text("关闭") }
            }
        }
    }

    pendingPickerKind?.let { kind ->
        val isVideo = kind == "video"
        AlertDialog(
            onDismissRequest = { pendingPickerKind = null },
            title = { Text(if (isVideo) "选择视频背景" else "选择图片背景") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (isVideo) "请选择要从哪里挑选视频文件。" else "请选择要从哪里挑选图片文件。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            pendingPickerKind = null
                            launchGallery(kind)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("homebg.source.gallery"),
                    ) {
                        Text("从相册选择")
                    }
                    OutlinedButton(
                        onClick = {
                            pendingPickerKind = null
                            if (isVideo) videoFilePicker.launch(arrayOf("video/*"))
                            else imageFilePicker.launch(arrayOf("image/*"))
                        },
                        modifier = Modifier.fillMaxWidth().testTag("homebg.source.file"),
                    ) {
                        Text("从文件选择")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingPickerKind = null }) { Text("取消") }
            },
        )
    }

    if (restoreConfirm) {
        ConfirmDialog(
            title = "恢复游戏默认主页背景？",
            lines = listOf(
                "会将 HomeBackgroundMod 移至停用目录。",
                "你导入的图片和视频文件仍会保留在目录中，随时可以重新启用。",
                "完全重启游戏后恢复官方背景。",
            ),
            confirmLabel = "恢复默认",
            onConfirm = { restoreConfirm = false; controller.restoreDefault() },
            onDismiss = { restoreConfirm = false },
        )
    }
}

@Composable
private fun StatusCard(status: HomeBackgroundRepository.Status) {
    val scheme = MaterialTheme.colorScheme
    val modeLabel = when (status.mode) {
        "video" -> "视频背景"
        "image" -> "图片背景"
        "none" -> "已启用（暂无背景文件）"
        else -> "未生效（已停用）"
    }
    Column(Modifier.fillMaxWidth().toysPanel().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("当前状态：$modeLabel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "模组版本：v${status.installedVersion.ifBlank { "未知" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        when (status.mode) {
            "video" -> {
                Text(
                    "视频文件：${status.videoFile}（${Formats.bytes(status.videoSize)}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            "image" -> {
                Text(
                    "图片文件：${status.imageFile}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                PreviewImage(status.imagePreviewFile)
            }
        }
    }
}

@Composable
private fun PreviewImage(file: File?) {
    val modified = file?.lastModified()
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file?.path, modified) {
        value = withContext(Dispatchers.IO) {
            file?.takeIf(File::isFile)?.let {
                runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    val img = bitmap ?: return
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Image(
            bitmap = img,
            contentDescription = "当前主页背景预览",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
