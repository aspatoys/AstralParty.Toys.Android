package com.hiynet.aspatoys.ui.mods

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiynet.aspatoys.data.mods.ConfigField
import com.hiynet.aspatoys.data.mods.ConfigKind
import com.hiynet.aspatoys.data.mods.ModConfigText
import com.hiynet.aspatoys.ui.components.MessageBanner
import com.hiynet.aspatoys.ui.components.SkeletonDetail

/**
 * 模组配置面板：表单改值 + 文本兜底。
 *
 * 保存就是写 `configs/<ModId>.json` 并原样保留注释与键顺序；加载器每次轮询会重读配置，
 * 所以大多数模组保存后立刻生效，不用重开游戏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModConfigSheet(
    controller: ModsController,
    state: ConfigEditorState,
    onDismiss: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                state.path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.loading) {
                SkeletonDetail()
                return@Column
            }

            state.error?.let { MessageBanner(text = it) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !state.rawMode,
                    onClick = { controller.setConfigRawMode(false) },
                    label = { Text("表单") },
                    enabled = state.fields.isNotEmpty(),
                )
                FilterChip(
                    selected = state.rawMode,
                    onClick = { controller.setConfigRawMode(true) },
                    label = { Text("文本") },
                )
            }

            if (state.rawMode) {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = controller::updateConfigText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 260.dp, max = 460.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    label = { Text("configs/${state.modId}.json") },
                )
            } else {
                state.fields.forEach { field ->
                    ConfigFieldRow(field = field, onChange = { token -> controller.updateConfigField(field, token) })
                }
                if (state.uneditable.isNotEmpty()) {
                    Text(
                        "这些项只能在文本里改：" + state.uneditable.joinToString("、"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "保存后加载器会重新读这份配置，支持热重载的模组立即生效；其余模组下一局或下次启动生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = controller::saveConfig,
                    enabled = state.dirty && !state.saving,
                ) { Text(if (state.saving) "保存中…" else "保存并生效") }
                OutlinedButton(
                    onClick = { controller.openConfig(placeholderEntry(state)) },
                    enabled = !state.saving,
                ) { Text("重新读取") }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    enabled = !state.saving && controller.deletingModId == null,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("删除模组") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除模组？") },
            text = {
                Text(
                    "将删除「${state.title}」的模组文件，但保留 configs/${state.modId}.json。\n" +
                        "这个操作不能撤销。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        controller.deleteMod(state.entry)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 重新读取用：只需要 Id 与显示名，走一遍 [ModsController.openConfig] 的加载流程。 */
private fun placeholderEntry(state: ConfigEditorState) = com.hiynet.aspatoys.data.mods.ModEntry(
    name = state.modId,
    fileName = "${state.modId}.dll",
    directoryName = state.modId,
    sizeBytes = 0,
    modifiedEpochMs = 0,
    displayName = state.title,
)

@Composable
private fun ConfigFieldRow(field: ConfigField, onChange: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                field.key,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            when (field.kind) {
                ConfigKind.BOOLEAN -> Switch(
                    checked = field.token.equals("true", ignoreCase = true),
                    onCheckedChange = { checked -> onChange(if (checked) "true" else "false") },
                )

                ConfigKind.NUMBER, ConfigKind.TEXT -> {
                    var draft by remember(field.key) { mutableStateOf(field.display) }
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
                        modifier = Modifier.width(150.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (field.kind == ConfigKind.NUMBER) KeyboardType.Number else KeyboardType.Text,
                        ),
                    )
                }

                ConfigKind.OTHER -> Text(
                    field.token,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (field.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                field.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
