package com.hiynet.aspatoys.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.BuildConfig
import com.hiynet.aspatoys.R
import com.hiynet.aspatoys.data.market.MarketClient
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.data.settings.StorageAccess
import com.hiynet.aspatoys.data.settings.ThemeMode
import com.hiynet.aspatoys.ui.components.Pill
import com.hiynet.aspatoys.ui.components.Snackbars
import com.hiynet.aspatoys.ui.theme.ToysMotion
import com.hiynet.aspatoys.ui.theme.ToysPageHeading
import com.hiynet.aspatoys.ui.theme.ToysSection
import com.hiynet.aspatoys.ui.theme.rememberThemeMode
import com.hiynet.aspatoys.ui.theme.toysPressFeedback

/** 设置分区定义。 */
enum class SettingsSection(
    val title: String,
    val subtitle: String,
    val iconRes: Int,
) {
    APPEARANCE("外观", "主题模式与显示效果", R.drawable.ic_settings_theme),
    OVERLAY("悬浮窗", "分组与正方形窗口大小", R.drawable.ic_nav_mods),
    BACKGROUND("背景", "自定义应用背景图", R.drawable.ic_settings_wallpaper),
    MARKET("市场", "模组市场地址与免责声明", R.drawable.ic_nav_market),
    LANGUAGE("语言", "界面与市场展示语言", R.drawable.ic_settings_language),
    STORAGE("目录与存储", "加载器目录与下载存储位置", R.drawable.ic_settings_storage),
    UPDATES("软件更新", "检查、下载和安装软件更新", R.drawable.ic_settings_info),
    ABOUT("关于", "版本信息、作者与联系方式", R.drawable.ic_settings_info),
}

/**
 * 重做后的设置页：
 * - 默认展示分区列表（分类导航）；
 * - 点击具体分区进入该分区的详细设置；
 * - 支持返回按钮及系统返回键。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChanged: () -> Unit,
    onReviewConsent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    val selectedTheme by rememberThemeMode(settings)
    val context = LocalContext.current
    var accessGranted by remember { mutableStateOf(StorageAccess.hasAllFilesAccess(context)) }

    AnimatedContent(
        targetState = currentSection,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(ToysMotion.Enter)) { it / 5 } + fadeIn(tween(ToysMotion.Enter)))
                    .togetherWith(slideOutHorizontally(tween(ToysMotion.Exit)) { -it / 6 } + fadeOut(tween(ToysMotion.Fast)))
            } else {
                (slideInHorizontally(tween(ToysMotion.Enter)) { -it / 6 } + fadeIn(tween(ToysMotion.Enter)))
                    .togetherWith(slideOutHorizontally(tween(ToysMotion.Exit)) { it / 5 } + fadeOut(tween(ToysMotion.Fast)))
            }
        },
        label = "settings-sections",
        modifier = modifier.fillMaxSize(),
    ) { section ->
        if (section == null) {
            SettingsCategoryList(
                settings = settings,
                selectedTheme = selectedTheme,
                accessGranted = accessGranted,
                onSelectSection = { currentSection = it },
            )
        } else {
            BackHandler { currentSection = null }
            SettingsDetailView(
                section = section,
                settings = settings,
                selectedTheme = selectedTheme,
                accessGranted = accessGranted,
                onAccessChanged = { accessGranted = it },
                onChanged = onChanged,
                onReviewConsent = onReviewConsent,
                onBack = { currentSection = null },
            )
        }
    }
}

/** 分区列表页。 */
@Composable
private fun SettingsCategoryList(
    settings: AppSettings,
    selectedTheme: ThemeMode,
    accessGranted: Boolean,
    onSelectSection: (SettingsSection) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ToysPageHeading("设置", "选择分类以调整相关配置与选项。")

        SettingsSection.entries.forEach { section ->
            val interactions = remember { MutableInteractionSource() }
            val summary = getSectionSummary(section, settings, selectedTheme, accessGranted)

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .toysPressFeedback(interactions)
                    .clickable(
                        interactionSource = interactions,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        onClick = { onSelectSection(section) },
                    ),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(section.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

/** 分区摘要说明。 */
private fun getSectionSummary(
    section: SettingsSection,
    settings: AppSettings,
    selectedTheme: ThemeMode,
    accessGranted: Boolean,
): String = when (section) {
    SettingsSection.APPEARANCE -> when (selectedTheme) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "明亮模式"
        ThemeMode.DARK -> "暗黑模式"
    }
    SettingsSection.OVERLAY -> "自定义侧栏分组与显示大小"
    SettingsSection.BACKGROUND -> if (settings.backgroundImage.isEmpty()) "默认背景" else "自选背景"
    SettingsSection.MARKET -> {
        val host = settings.marketBaseUrl.removePrefix("https://").removePrefix("http://")
        val consent = if (settings.marketConsentGranted) "已同意" else "未同意"
        "$host · $consent"
    }
    SettingsSection.LANGUAGE -> when (settings.languageOverride) {
        "zh-Hans" -> "简体中文"
        "zh-Hant" -> "繁體中文"
        "en" -> "English"
        "ja" -> "日本語"
        else -> "跟随系统"
    }
    SettingsSection.STORAGE -> {
        val perm = if (accessGranted) "已授权" else "未授权"
        "${settings.loaderRoot.path} · $perm"
    }
    SettingsSection.UPDATES -> "自动检查与下载更新"
    SettingsSection.ABOUT -> "v${BuildConfig.VERSION_NAME} · 联系方式与致谢"
}

/** 详细设置视图。 */
@Composable
private fun SettingsDetailView(
    section: SettingsSection,
    settings: AppSettings,
    selectedTheme: ThemeMode,
    accessGranted: Boolean,
    onAccessChanged: (Boolean) -> Unit,
    onChanged: () -> Unit,
    onReviewConsent: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val openUrl: (String) -> Unit = { url ->
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Snackbars.show("无法打开浏览器，已复制链接到剪贴板")
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("URL", url))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 顶部返回栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val backInteractions = remember { MutableInteractionSource() }
            Surface(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .toysPressFeedback(backInteractions)
                    .clickable(
                        interactionSource = backInteractions,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        onClick = onBack,
                    ),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "返回",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = section.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 分区内容
        when (section) {
            SettingsSection.APPEARANCE -> {
                ToysSection("主题模式") {
                    Text(
                        "主题切换立即生效，并在下次打开时保留。已安装的游戏内悬浮窗会在 Shizuku / Root 连接时同步；跟随系统可在游戏内自动切换。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.fillMaxWidth().selectableGroup().padding(vertical = 4.dp)) {
                        listOf(
                            ThemeMode.SYSTEM to "跟随系统",
                            ThemeMode.LIGHT to "明亮",
                            ThemeMode.DARK to "暗黑",
                        ).forEach { (mode, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .selectable(
                                        selected = selectedTheme == mode,
                                        role = Role.RadioButton,
                                        onClick = { if (selectedTheme != mode) settings.themeMode = mode },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selectedTheme == mode, onClick = null)
                                Text(
                                    label,
                                    modifier = Modifier.padding(start = 12.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }
            }

            SettingsSection.OVERLAY -> { OverlaySettings(settings) }

            SettingsSection.BACKGROUND -> {
                ToysSection("应用背景") {
                    BackgroundSettings(settings)
                }
            }

            SettingsSection.MARKET -> {
                var baseUrlDraft by remember(settings.marketBaseUrl) { mutableStateOf(settings.marketBaseUrl) }
                var notice by remember { mutableStateOf<String?>(null) }

                ToysSection("市场服务器") {
                    OutlinedTextField(
                        value = baseUrlDraft,
                        onValueChange = { baseUrlDraft = it },
                        label = { Text("市场地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
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
                }

                ToysSection("免责声明") {
                    Text(
                        "访问市场需同意免责声明。撤回后下次进入市场将重新提示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "当前状态：${if (settings.marketConsentGranted) "已同意（版本 ${settings.marketConsentVersion}）" else "未同意"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
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

                if (notice != null) {
                    Text(notice!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            SettingsSection.LANGUAGE -> {
                ToysSection("展示语言") {
                    Text(
                        "市场会按所选语言返回模组名称与说明；「跟随系统」由设备当前语言决定。",
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
            }

            SettingsSection.STORAGE -> {
                var loaderRootDraft by remember(settings.loaderRootPath) { mutableStateOf(settings.loaderRootPath) }
                var downloadsDraft by remember(settings.downloadsPath) { mutableStateOf(settings.downloadsPath) }
                var notice by remember { mutableStateOf<String?>(null) }

                ToysSection("存储权限") {
                    Text(
                        "读写 /sdcard 需要「所有文件访问权限」；若未授权，市场仍可下载，但无法直接操作已安装模组。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "存储访问状态：${if (accessGranted) "已授权" else "未授权（无法访问 /sdcard）"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(onClick = { StorageAccess.openAllFilesAccessSettings(context) }) {
                            Text("去授权")
                        }
                        OutlinedButton(onClick = {
                            val granted = StorageAccess.hasAllFilesAccess(context)
                            onAccessChanged(granted)
                            notice = if (granted) "已获取所有文件访问权限。" else "仍未检测到授权。"
                        }) {
                            Text("重新检测")
                        }
                    }
                }

                ToysSection("加载器根目录") {
                    Text(
                        "安卓版 CesiumLoader 根目录默认为 /sdcard/CesiumLoader；模组在 mods/ 下，配置在 configs/，日志在 logs/。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = loaderRootDraft,
                        onValueChange = { loaderRootDraft = it },
                        label = { Text("根目录路径") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilledTonalButton(onClick = {
                            settings.loaderRootPath = loaderRootDraft
                            loaderRootDraft = settings.loaderRootPath
                            notice = "加载器根目录已保存。"
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
                }

                ToysSection("下载目录") {
                    Text(
                        "市场下载的模组包与 ZIP 检查文件均保存在此目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = downloadsDraft,
                        onValueChange = { downloadsDraft = it },
                        label = { Text("下载路径") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilledTonalButton(onClick = {
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
                }

                if (notice != null) {
                    Text(notice!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            SettingsSection.UPDATES -> { UpdateSettings() }

            SettingsSection.ABOUT -> {
                ToysSection("应用信息") {
                    Text(
                        "Astral Party Toys for Android",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Astral Party（吉星派对）安卓端模组管理与加载器工具，支持 Shizuku / Root 加载器部署、" +
                            "模组市场下载与管理、配置热重载以及游戏内半屏悬浮窗。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ToysSection("开发者联系方式") {
                    Text(
                        "点击即可在浏览器或对应客户端打开个人主页：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    ContributorCard(
                        name = "TenkinMori",
                        role = "开发者",
                        githubUrl = "https://github.com/TenkinMori",
                        bilibiliUrl = "https://space.bilibili.com/3546911206280055",
                        onOpenUrl = openUrl,
                    )

                    ContributorCard(
                        name = "囚魚玖",
                        role = "开发者",
                        githubUrl = "https://github.com/Aperfish",
                        bilibiliUrl = "https://space.bilibili.com/354301784",
                        onOpenUrl = openUrl,
                    )
                }

                ToysSection("相关项目仓库") {
                    Text(
                        "点击即可在浏览器中访问对应开源仓库：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    RepositoryItem(
                        title = "安卓版 Toys 仓库",
                        description = "安卓端模组管理、加载器部署与游戏内悬浮窗",
                        url = "https://github.com/higashitaniyume/AstralParty.Toys.Android",
                        onOpenUrl = openUrl,
                    )

                    RepositoryItem(
                        title = "桌面版 Toys 仓库",
                        description = "Windows 桌面端多功能工具箱与模组管理",
                        url = "https://github.com/higashitaniyume/AstralParty.Toys",
                        onOpenUrl = openUrl,
                    )

                    RepositoryItem(
                        title = "CesiumLoader 加载器",
                        description = "原生注入补丁、引导器与模组 SDK 核心",
                        url = "https://github.com/higashitaniyume/CesiumLoader",
                        onOpenUrl = openUrl,
                    )
                }

                ToysSection("开源许可") {
                    Text(
                        "本项目采用 GNU Affero General Public License v3.0 (AGPL-3.0) 协议开源。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

/** 开发者联系卡片。 */
@Composable
private fun ContributorCard(
    name: String,
    role: String,
    githubUrl: String,
    bilibiliUrl: String,
    onOpenUrl: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Pill(
                    text = role,
                    container = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    content = MaterialTheme.colorScheme.primary,
                )
            }

            // GitHub
            val githubInteractions = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .toysPressFeedback(githubInteractions)
                    .clickable(
                        interactionSource = githubInteractions,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        onClick = { onOpenUrl(githubUrl) },
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("GitHub", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        githubUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = "打开",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Bilibili
            val biliInteractions = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .toysPressFeedback(biliInteractions)
                    .clickable(
                        interactionSource = biliInteractions,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        onClick = { onOpenUrl(bilibiliUrl) },
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("哔哩哔哩 (Bilibili)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        bilibiliUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = "打开",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 开源项目仓库列表项。 */
@Composable
private fun RepositoryItem(
    title: String,
    description: String,
    url: String,
    onOpenUrl: (String) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .toysPressFeedback(interactions)
            .clickable(
                interactionSource = interactions,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = { onOpenUrl(url) },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = "访问仓库",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
