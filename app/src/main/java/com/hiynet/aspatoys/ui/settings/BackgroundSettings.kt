package com.hiynet.aspatoys.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.data.settings.BackgroundImages
import com.hiynet.aspatoys.ui.theme.rememberBackgroundImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun BackgroundSettings(settings: AppSettings) {
    val context = LocalContext.current
    val images = remember(context, settings) { BackgroundImages(context, settings) }
    val selected by rememberBackgroundImage(settings)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    images.importImage(uri)
                    failed = false
                    notice = "背景已更换，重新打开应用后仍会保留。"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed = true
                    notice = error.message ?: "图片无法读取，请选择其他图片。"
                } finally {
                    busy = false
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (selected.isEmpty()) "当前使用默认背景" else "当前使用自选背景",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("选择图片作为应用背景，保留原有主题色与背景模糊效果。支持最大 30 MB 的图片。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = { picker.launch(arrayOf("image/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("appearance.background.pick"),
        ) { Text(if (busy) "正在保存背景…" else "更换背景") }
        OutlinedButton(
            onClick = {
                busy = true
                scope.launch {
                    try {
                        images.reset()
                        failed = false
                        notice = "已恢复默认背景。"
                    } finally { busy = false }
                }
            },
            enabled = !busy && selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("appearance.background.reset"),
        ) { Text("恢复默认背景") }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}
