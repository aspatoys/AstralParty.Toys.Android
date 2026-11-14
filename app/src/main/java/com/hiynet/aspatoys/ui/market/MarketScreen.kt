package com.hiynet.aspatoys.ui.market

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.data.market.MarketListItem
import com.hiynet.aspatoys.data.market.MarketModDetail
import com.hiynet.aspatoys.data.market.MarketModVersion
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.ui.components.CoverThumb
import com.hiynet.aspatoys.ui.components.MessageBanner
import com.hiynet.aspatoys.ui.components.PackageReportContent
import com.hiynet.aspatoys.ui.components.Pill
import com.hiynet.aspatoys.ui.components.SkeletonDetail
import com.hiynet.aspatoys.ui.components.SkeletonList
import com.hiynet.aspatoys.ui.format.Formats

/**
 * 市场页：搜索 / 排序 / 标签筛选 → 目录 → 详情 → 下载并安装。
 *
 * 下载边下边算 SHA-256，检查通过就直接经 Shizuku 写进加载器；检查没过则停在报告里，
 * 把「为什么没装」说清楚（哈希不符、包结构不对、SDK 门槛、降级）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(
    controller: MarketController,
    modifier: Modifier = Modifier,
) {
    var showReport by remember { mutableStateOf(false) }
    LaunchedEffect(controller.report) { showReport = controller.report != null }

    // 首次进入市场页：已同意就先拉一份目录，不必再点刷新。
    LaunchedEffect(Unit) {
        if (controller.consentGranted && controller.items.isEmpty() && !controller.loading) controller.refresh()
    }

    Column(modifier = modifier.fillMaxSize()) {
        MarketSearchBar(controller)
        MarketFilters(controller)

        if (controller.message != null) {
            MessageBanner(
                text = controller.message!!,
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = controller::clearMessage) { Text("知道了") }
            }
        }

        if (controller.error != null && controller.items.isEmpty()) {
            MessageBanner(
                text = controller.error!!,
                modifier = Modifier.padding(12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = controller::refresh) { Text("重试") }
                OutlinedButton(onClick = controller::clearError) { Text("关闭") }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                !controller.consentGranted -> ConsentGateBox(onReview = controller::reviewConsent)
                // 首屏用骨架屏：先画出列表版式，比一句「正在读取」更像马上就有内容。
                controller.loading && controller.items.isEmpty() -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { SkeletonList(count = 5, market = true) }
                }

                controller.items.isEmpty() && controller.error == null ->
                    EmptyBox("市场里没有匹配的模组。")

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(controller.items, key = { it.summary.id }) { item ->
                        MarketRow(
                            item = item,
                            coverPath = controller.covers[item.summary.id],
                            onOpen = { controller.openDetail(item.summary.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    if (controller.hasMore) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                if (controller.loadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                } else {
                                    OutlinedButton(onClick = controller::loadMore) { Text("加载更多") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (controller.showConsent) {
        MarketConsentDialog(
            baseUrl = controller.baseUrl,
            onAccept = controller::acceptConsent,
            onDismiss = controller::dismissConsent,
        )
    }

    val detail = controller.detail
    if (detail != null || controller.detailLoading || controller.detailError != null) {
        ModalBottomSheet(
            onDismissRequest = controller::closeDetail,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            MarketDetailSheet(controller, detail)
        }
    }

    controller.download?.let { state ->
        AlertDialog(
            onDismissRequest = { /* 下载中不允许点外部关闭 */ },
            title = { Text(state.modName) },
            text = {
                Column {
                    Text("${state.phaseText}  v${state.version}")
                    Spacer(Modifier.height(12.dp))
                    val fraction = state.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        Formats.progressText(state.received, state.total, state.percent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                if (state.cancellable) {
                    TextButton(onClick = controller::cancelDownload) { Text("取消") }
                }
            },
        )
    }

    val report = controller.report
    if (showReport && report != null) {
        ModalBottomSheet(
            onDismissRequest = {
                showReport = false
                controller.dismissReport()
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            ) {
                PackageReportContent(report)
                if (report.success) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { controller.installReport() },
                        enabled = controller.download == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("写入加载器") }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        showReport = false
                        controller.dismissReport()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("完成") }
            }
        }
    }
}

@Composable
private fun MarketSearchBar(controller: MarketController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = controller.query,
            onValueChange = controller::updateQuery,
            singleLine = true,
            placeholder = { Text("搜索模组名称或作者") },
            modifier = Modifier.weight(1f),
        )
        Button(onClick = controller::submitSearch) { Text("搜索") }
    }
}

@Composable
private fun MarketFilters(controller: MarketController) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortChip("下载量", MarketController.SortDownloads, controller)
            SortChip("最近更新", MarketController.SortUpdated, controller)
            SortChip("名称", MarketController.SortName, controller)
        }
        if (controller.tags.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = controller.tag.isEmpty(),
                    onClick = { controller.updateTag("") },
                    label = { Text("全部标签") },
                )
                controller.tags.forEach { tag ->
                    FilterChip(
                        selected = controller.tag == tag,
                        onClick = { controller.updateTag(tag) },
                        label = { Text(tag) },
                    )
                }
            }
        }
        if (controller.items.isNotEmpty()) {
            Text(
                "共 ${controller.total} 个模组",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SortChip(label: String, value: String, controller: MarketController) {
    FilterChip(
        selected = controller.sort == value,
        onClick = { controller.updateSort(value) },
        label = { Text(label) },
    )
}

@Composable
private fun MarketRow(
    item: MarketListItem,
    coverPath: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = item.summary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverThumb(
            path = coverPath,
            modifier = Modifier.size(64.dp),
            fallback = summary.name,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary.name.ifBlank { summary.id },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.installed) {
                    Spacer(Modifier.width(6.dp))
                    Pill(
                        if (item.updatable) "可更新 v${summary.latestVersion}" else if (!item.enabled) "已停用" else "已装",
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    if (summary.author.isNotBlank()) append(summary.author).append(" · ")
                    append("v").append(summary.latestVersion.ifBlank { "?" })
                    append(" · ").append(Formats.count(summary.downloads)).append(" 次下载")
                    if (summary.sizeBytes > 0) append(" · ").append(Formats.bytes(summary.sizeBytes))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    summary.summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (summary.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    summary.tags.take(4).forEach { tag -> Pill(tag) }
                }
            }
        }
    }
}

@Composable
private fun MarketDetailSheet(controller: MarketController, detail: MarketModDetail?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        val loading = controller.detailLoading
        val error = controller.detailError

        if (loading && detail == null) {
            SkeletonDetail()
        } else if (error != null) {
            MessageBanner(error)
        } else if (detail != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CoverThumb(
                    path = controller.covers[detail.id],
                    modifier = Modifier.size(84.dp),
                    fallback = detail.name,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        detail.name.ifBlank { detail.id },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            if (detail.author.isNotBlank()) append(detail.author)
                            append(" · v").append(detail.latestVersion.ifBlank { "?" })
                            append(" · ").append(Formats.count(detail.downloads)).append(" 次下载")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (detail.tags.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            detail.tags.take(6).forEach { Pill(it) }
                        }
                    }
                }
            }

            if (detail.summary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(detail.summary, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("版本", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (detail.versions.isEmpty()) {
                    Text(
                        "市场没有登记版本信息，无法下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                detail.versions.forEach { version ->
                    VersionRow(
                        version = version,
                        isLatest = version.version.equals(detail.latestVersion, ignoreCase = true),
                        downloading = controller.download != null,
                        onDownload = { controller.downloadVersion(version) },
                    )
                }
            }

            val body = detail.description.ifBlank { detail.readme }
            if (body.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("说明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(body, style = MaterialTheme.typography.bodySmall)
            }

            if (detail.repositoryUrl.isNotBlank() || detail.license.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                if (detail.repositoryUrl.isNotBlank()) {
                    Text(
                        "仓库：${detail.repositoryUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (detail.license.isNotBlank()) {
                    Text(
                        "许可证：${detail.license}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionRow(
    version: MarketModVersion,
    isLatest: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("v${version.version}", style = MaterialTheme.typography.titleSmall)
                if (isLatest) {
                    Spacer(Modifier.width(6.dp))
                    Pill("最新", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    if (version.sizeBytes > 0) append(Formats.bytes(version.sizeBytes)).append(" · ")
                    append("SDK ").append(version.sdkVersion.ifBlank { "未声明" })
                    if (!version.hosted) append(" · 作者直链")
                    if (version.publishedAt.isNotBlank()) append(" · ").append(Formats.dateTime(version.publishedAt))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (version.changelog.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    version.changelog,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onDownload, enabled = !downloading) { Text(if (isLatest) "下载并安装" else "安装此版本") }
    }
}

@Composable
private fun MarketConsentDialog(
    baseUrl: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("【免责声明 / 风险提示】进入市场前请阅读并确认") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "下载即表示你自愿承担由此产生的一切风险",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "市场里的模组不是 AstralParty.Toys / CesiumLoader 官方提供的，全部由第三方作者独立开发和托管。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "· 模组的内容、行为、更新和兼容性均由原作者负责。Toys 只维护 CesiumLoader 官方内置 Mod；" +
                        "市场里的其余 Mod 不在维护范围，不提供任何支持。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "· 安装第三方模组可能导致游戏崩溃、存档损坏、账号异常、封禁、版本不兼容等一切损失，" +
                        "Toys 与加载器作者不承担任何责任。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "· 下载的模组包仅做 SHA-256 完整性校验，不代表安全、合法或可用。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "当前市场提供地址：$baseUrl",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "默认市场链接由 CesiumLoader 与 AstralParty.Toys 官方提供；若你已在设置中改为自定义地址，" +
                        "该地址由其运营者负责。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept) { Text("我已知晓并自愿承担风险") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("不同意，退出市场") }
        },
    )
}

@Composable
private fun LoadingBox(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyBox(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 没有同意免责声明时市场是关闭的：不给任何联网机会，只提供重新阅读说明的入口。 */
@Composable
private fun ConsentGateBox(onReview: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "未同意免责声明，市场已关闭",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "未同意时 App 不会连接市场服务。如需使用，请重新查看并同意说明。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onReview) { Text("查看说明") }
    }
}

/** 市场语言设置项用到的标签，供设置页展示。 */
fun languageLabel(tag: String): String = when (tag) {
    "zh-Hans" -> "简体中文"
    "zh-Hant" -> "繁體中文"
    "en" -> "English"
    "ja" -> "日本語"
    else -> AppSettings.resolveLanguage(tag)
}
