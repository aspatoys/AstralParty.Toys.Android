package com.hiynet.aspatoys.ui.mods

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiynet.aspatoys.R
import com.hiynet.aspatoys.data.mods.ConfigField
import com.hiynet.aspatoys.data.mods.ConfigKind
import com.hiynet.aspatoys.data.mods.ModConfigText
import com.hiynet.aspatoys.data.mods.ModEntry
import com.hiynet.aspatoys.ui.components.ConfirmDialog
import com.hiynet.aspatoys.ui.components.MessageBanner
import com.hiynet.aspatoys.ui.components.Pill
import com.hiynet.aspatoys.ui.components.SkeletonDetail

/**
 * 模组配置独立页面：表单改值 + 文本兜底。
 *
 * 保存时直接写 `configs/<ModId>.json` 并原样保留注释与键顺序；
 * 加载器后台线程会重读配置，支持热重载的模组保存后立刻生效。
 */
@Composable
fun ModConfigScreen(
    controller: ModsController,
    state: ConfigEditorState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onDismiss() }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 顶部导航栏：返回按钮 + 模组标题 + 路径
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (state.dirty) {
                Pill(
                    text = "未保存",
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        if (state.loading) {
            SkeletonDetail()
            return@Column
        }

        state.error?.let {
            MessageBanner(
                text = it,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        // 切换模式：表单 / 原始文本
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = !state.rawMode,
                onClick = { controller.setConfigRawMode(false) },
                label = { Text("表单模式") },
                enabled = state.fields.isNotEmpty(),
            )
            FilterChip(
                selected = state.rawMode,
                onClick = { controller.setConfigRawMode(true) },
                label = { Text("原始 JSON") },
            )
        }

        // 详细内容区域
        if (state.rawMode) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "JSON 源码编辑",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "直接编辑 configs/${state.modId}.json，保存前必须是合法的 JSON 对象。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = controller::updateConfigText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 280.dp, max = 500.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        shape = RoundedCornerShape(12.dp),
                        label = { Text("configs/${state.modId}.json") },
                    )
                }
            }
        } else {
            if (state.fields.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "该模组暂无可直接编辑的标量配置项，请切换至「原始 JSON」进行编辑。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                state.fields.forEach { field ->
                    ConfigFieldCard(
                        field = field,
                        onChange = { token -> controller.updateConfigField(field, token) },
                    )
                }
            }

            if (state.uneditable.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                ) {
                    Text(
                        "以下配置项包含复杂结构，需切换至「原始 JSON」修改：\n" + state.uneditable.joinToString("、"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        // 提示说明
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "保存后加载器会重新读这份配置，支持热重载的模组立即生效；其余模组下一局或下次启动生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 底部操作按钮栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = controller::saveConfig,
                enabled = state.dirty && !state.saving,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (state.saving) "保存中…" else "保存并生效")
            }
            OutlinedButton(
                onClick = { controller.openConfig(placeholderEntry(state)) },
                enabled = !state.saving,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("重新读取")
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { showDeleteConfirm = true },
                enabled = !state.saving && controller.deletingModId == null,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("删除模组")
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "删除模组「${state.title}」？",
            lines = listOf(
                "删除这个模组的文件。",
                "保留 configs/${state.modId}.json，以后重新安装会沿用这份配置。",
                "删除后不能撤销。只是暂时不想用的话，关掉模组开关就行。",
            ),
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                showDeleteConfirm = false
                controller.deleteMod(state.entry)
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Deprecated("Use ModConfigScreen instead", ReplaceWith("ModConfigScreen(controller, state, onDismiss)"))
@Composable
fun ModConfigSheet(
    controller: ModsController,
    state: ConfigEditorState,
    onDismiss: () -> Unit,
) = ModConfigScreen(controller = controller, state = state, onDismiss = onDismiss)

/** 重新读取用：只需要 Id 与显示名，走一遍 [ModsController.openConfig] 的加载流程。 */
private fun placeholderEntry(state: ConfigEditorState) = ModEntry(
    name = state.modId,
    fileName = "${state.modId}.dll",
    directoryName = state.modId,
    sizeBytes = 0,
    modifiedEpochMs = 0,
    displayName = state.title,
)

@Composable
private fun ConfigFieldCard(field: ConfigField, onChange: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = field.key,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                when (field.kind) {
                    ConfigKind.BOOLEAN -> Switch(
                        checked = field.token.equals("true", ignoreCase = true),
                        onCheckedChange = { checked -> onChange(if (checked) "true" else "false") },
                    )

                    ConfigKind.NUMBER, ConfigKind.TEXT -> {
                        // 草稿只跟字段名绑定。模型值会随输入实时变化，拿它当 remember 的 key
                        // 会让每敲一个字都重建草稿（光标跳到末尾、输入被打断）。
                        var draft by remember(field.key) { mutableStateOf(field.display) }
                        // 只有外部改动（重新读取、切换模组）才回灌；自己敲进去的值读回来与草稿一致，
                        // 不会覆盖正在输入的内容。
                        LaunchedEffect(field.key, field.display) {
                            if (field.display != draft) draft = field.display
                        }
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { typed ->
                                draft = typed
                                if (field.kind == ConfigKind.NUMBER) {
                                    if (typed.trim().toDoubleOrNull() != null) onChange(typed.trim())
                                } else {
                                    onChange(ModConfigText.quote(typed))
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.width(140.dp),
                            shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (field.kind == ConfigKind.NUMBER) KeyboardType.Number else KeyboardType.Text,
                            ),
                        )
                    }

                    ConfigKind.OTHER -> Text(
                        text = field.token,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (field.description.isNotBlank()) {
                Text(
                    text = field.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
