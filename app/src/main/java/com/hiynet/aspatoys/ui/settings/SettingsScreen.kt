package com.hiynet.aspatoys.ui.settings

import androidx.compose.foundation.layout.FlowRow
import com.hiynet.aspatoys.ui.theme.ToysPageHeading
import com.hiynet.aspatoys.ui.theme.ToysSection
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.BuildConfig
import com.hiynet.aspatoys.data.market.MarketClient
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.data.settings.StorageAccess
import com.hiynet.aspatoys.data.settings.ThemeMode
import com.hiynet.aspatoys.ui.theme.rememberThemeMode

/**
 * 设置页：市场地址与免责声明、界面/市场语言、工作区与下载目录。
 *
 * 任何改动都立即写进 [AppSettings]，并通过 [onChanged] 让上层重建控制器状态
 * （市场地址变了要重新拉目录，工作区变了要重新扫描模组）。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChanged: () -> Unit,
    onReviewConsent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var baseUrlDraft by remember(settings.marketBaseUrl) { mutableStateOf(settings.marketBaseUrl) }
    var loaderRootDraft by remember(settings.loaderRootPath) { mutableStateOf(settings.loaderRootPath) }
    var downloadsDraft by remember(settings.downloadsPath) { mutableStateOf(settings.downloadsPath) }
    var notice by remember { mutableStateOf<String?>(null) }
    val selectedTheme by rememberThemeMode(settings)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ToysPageHeading("设置", "调整外观、市场与存储位置。")
        ToysSection("外观") {
            Text(
                "主题切换立即生效，并在下次打开时保留。已安装的游戏内悬浮窗会在 Shizuku / Root 连接时同步；跟随系统可在游戏内自动切换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.fillMaxWidth().selectableGroup().padding(vertical = 4.dp)) {
                listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "明亮", ThemeMode.DARK to "暗黑").forEach { (mode, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .selectable(
                                selected = selectedTheme == mode,
                                role = Role.RadioButton,
                                onClick = { if (selectedTheme != mode) settings.themeMode = mode },
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedTheme == mode, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
        }
        }

        ToysSection("背景") { BackgroundSettings(settings) }

        ToysSection("市场") {
            OutlinedTextField(
                value = baseUrlDraft,
                onValueChange = { baseUrlDraft = it },
                label = { Text("市场地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = {
                    settings.marketBaseUrl = baseUrlDraft
                    baseUrlDraft = settings.marketBaseUrl
                    notice = "市场地址已保存。"
                    onChanged()
                }) { Text("保存") }
                OutlinedButton(onClick = {
                    baseUrlDraft = MarketClient.DefaultBaseUrl
                    settings.marketBaseUrl = MarketClient.DefaultBaseUrl
                    notice = "已恢复默认地址。"
                    onChanged()
                }) { Text("恢复默认") }
        }
        Text(
            "默认 $DefaultHint",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))
        Text(
            "免责声明：${if (settings.marketConsentGranted) "已同意" else "未同意"}",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onReviewConsent) { Text("查看说明") }
            if (settings.marketConsentGranted) {
                OutlinedButton(onClick = {
                    settings.marketConsentVersion = 0
                    notice = "已撤回同意，下次进入市场会重新询问。"
                    onChanged()
                }) { Text("撤回同意") }
            }
        }

        }

        ToysSection("语言") {
            Text(
                "市场会按这个语言返回模组名称与说明；「跟随系统」由系统语言决定。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LanguageChip(settings, "", "跟随系统", onChanged)
                LanguageChip(settings, "zh-Hans", "简体中文", onChanged)
                LanguageChip(settings, "zh-Hant", "繁體中文", onChanged)
                LanguageChip(settings, "en", "English", onChanged)
                LanguageChip(settings, "ja", "日本語", onChanged)
        }

        }

        ToysSection("目录") {
            Text(
                "安卓版 CesiumLoader 的根目录是 /sdcard/CesiumLoader：市场下载、随包内置与更新替换的模组" +
                    "都装到它的 mods/<ModId>/ 下。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val context = LocalContext.current
            var accessGranted by remember { mutableStateOf(StorageAccess.hasAllFilesAccess(context)) }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "存储访问：${if (accessGranted) "已授权" else "未授权（读不到 /sdcard）"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { StorageAccess.openAllFilesAccessSettings(context) }) {
                        Text("去授权")
                    }
                    OutlinedButton(onClick = { accessGranted = StorageAccess.hasAllFilesAccess(context) }) {
                        Text("重新检测")
                    }
                }
        }

        OutlinedTextField(
            value = loaderRootDraft,
            onValueChange = { loaderRootDraft = it },
            label = { Text("加载器根目录") },
            supportingText = { Text("默认 /sdcard/CesiumLoader；模组在它的 mods/ 下，配置在 configs/，日志在 logs/") },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = {
                settings.loaderRootPath = loaderRootDraft
                loaderRootDraft = settings.loaderRootPath
                notice = "加载器根目录已切换。"
                onChanged()
            }) { Text("保存") }
            OutlinedButton(onClick = {
                settings.loaderRootPath = ""
                loaderRootDraft = settings.loaderRootPath
                notice = "已恢复默认工作区。"
                onChanged()
            }) { Text("恢复默认") }
        }
        Text(
            settings.loaderRoot.absolutePath,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = downloadsDraft,
            onValueChange = { downloadsDraft = it },
            label = { Text("下载目录") },
            supportingText = { Text("市场下载的模组包与「选择 ZIP 检查」的临时文件都放在这里") },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = {
                settings.downloadsPath = downloadsDraft
                downloadsDraft = settings.downloadsPath
                notice = "下载目录已保存。"
                onChanged()
            }) { Text("保存") }
            OutlinedButton(onClick = {
                settings.downloadsPath = ""
                downloadsDraft = settings.downloadsPath
                notice = "已恢复默认下载目录。"
                onChanged()
            }) { Text("恢复默认") }
        }
        Text(
            settings.downloadsDir.absolutePath,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (notice != null) {
            Text(notice!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        }

        ToysSection("关于") {
            Text(
                "Astral Party Toys for Android ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "模组管理与市场移植自桌面版 AstralParty.Toys：市场目录、详情、模组包下载（SHA-256 校验）" +
                    "与安装前预检已就位；导入游戏 mods 目录待模组位置确定后接入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LanguageChip(
    settings: AppSettings,
    value: String,
    label: String,
    onChanged: () -> Unit,
) {
    FilterChip(
        selected = settings.languageOverride == value,
        onClick = {
            settings.languageOverride = value
            onChanged()
        },
        label = { Text(label) },
    )
}

private const val DefaultHint = MarketClient.DefaultBaseUrl
