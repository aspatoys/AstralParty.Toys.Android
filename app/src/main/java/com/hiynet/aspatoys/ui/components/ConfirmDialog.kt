package com.hiynet.aspatoys.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 确认弹窗，规则和 Win 版 `V2UI.confirmDelete` 一致：
 *
 * - [lines] 逐条写清：会删 / 改什么、什么会保留、能不能找回（或者更轻的替代做法）。
 * - [danger] 为 true 时确认按钮是红色，并且打开 0.5 秒后才能点，防止连点误触。
 * - 「取消」永远在，点外面、按返回都等于取消。
 */
@Composable
fun ConfirmDialog(
    title: String,
    lines: List<String>,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    footnote: String? = null,
) {
    var armed by remember { mutableStateOf(!danger) }
    LaunchedEffect(danger) {
        if (danger) {
            delay(ArmDelayMillis)
            armed = true
        }
    }
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.filter { it.isNotBlank() }.forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("·", style = MaterialTheme.typography.bodyMedium)
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                footnote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = armed,
                colors = if (danger) {
                    ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 危险按钮打开后多久才能点（Win 版 `armDanger` 也是 500ms）。 */
private const val ArmDelayMillis = 500L
