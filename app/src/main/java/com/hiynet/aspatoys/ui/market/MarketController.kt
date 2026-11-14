package com.hiynet.aspatoys.ui.market

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiynet.aspatoys.core.HttpCancellation
import com.hiynet.aspatoys.core.HttpCancelledException
import com.hiynet.aspatoys.data.AppContainer
import com.hiynet.aspatoys.data.market.DownloadProgress
import com.hiynet.aspatoys.data.market.MarketException
import com.hiynet.aspatoys.data.market.MarketListItem
import com.hiynet.aspatoys.data.market.MarketModDetail
import com.hiynet.aspatoys.data.market.MarketModSummary
import com.hiynet.aspatoys.data.market.MarketModVersion
import com.hiynet.aspatoys.data.market.PackageCheckReport
import com.hiynet.aspatoys.ui.components.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 市场页面状态：目录、详情、下载进度与检查报告。 */
class MarketController(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val settings = container.settings
    private val client = container.client
    private val mainHandler = Handler(Looper.getMainLooper())

    var consentGranted by mutableStateOf(settings.marketConsentGranted)
        private set

    /** 首次进市场时要先看免责说明；「不同意」只关闭本次，不写任何设置。 */
    var showConsent by mutableStateOf(!settings.marketConsentGranted)
        private set

    var baseUrl by mutableStateOf(settings.marketBaseUrl)
        private set

    var query by mutableStateOf("")
        private set

    var sort by mutableStateOf(SortDownloads)
        private set

    var tag by mutableStateOf("")
        private set

    var items by mutableStateOf<List<MarketListItem>>(emptyList())
        private set

    var tags by mutableStateOf<List<String>>(emptyList())
        private set

    var total by mutableStateOf(0)
        private set

    var loading by mutableStateOf(false)
        private set

    var loadingMore by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** 模组 ID → 已缓存的封面文件路径。 */
    var covers by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    var detail by mutableStateOf<MarketModDetail?>(null)
        private set

    var detailLoading by mutableStateOf(false)
        private set

    var detailError by mutableStateOf<String?>(null)
        private set

    var download by mutableStateOf<DownloadState?>(null)
        private set

    var report by mutableStateOf<PackageCheckReport?>(null)
        private set

    /** 一次性提示（取消、保存失败等），界面显示成 banner 后可调用 [clearMessage]。 */
    var message by mutableStateOf<String?>(null)
        private set

    private var loadedPage = 0
    private var cancellation: HttpCancellation? = null

    /** 当前页码；翻页由 [goToPage] 负责替换列表，不再只追加。 */
    var page by mutableStateOf(1)
        private set

    val pageCount: Int get() = if (total <= 0) 1 else (total + PageSize - 1) / PageSize

    val hasMore: Boolean get() = items.size < total

    // ============================== 目录 ==============================

    fun refresh() {
        if (loading) return
        if (!requireConsent()) return
        loading = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.list(baseUrl, settings.language(), query, tag, sort, 1, PageSize)
                }
                val installed = withContext(Dispatchers.IO) { installedIndex() }
                items = result.items.map { summary -> toListItem(summary, installed) }
                tags = result.tags
                total = result.total
                loadedPage = 1
                page = 1
                loadCovers(result.items)
            } catch (cancelled: HttpCancelledException) {
                message = "已取消。"
            } catch (failure: MarketException) {
                error = failure.message
            } catch (failure: Exception) {
                error = "读取模组目录失败：${failure.message}"
            } finally {
                loading = false
            }
        }
    }

    fun loadMore() {
        if (loading || loadingMore || !hasMore) return
        loadingMore = true
        scope.launch {
            try {
                val next = loadedPage + 1
                val page = withContext(Dispatchers.IO) {
                    client.list(baseUrl, settings.language(), query, tag, sort, next, PageSize)
                }
                val installed = withContext(Dispatchers.IO) { installedIndex() }
                val existing = items.map { it.summary.id }.toSet()
                val appended = page.items.filter { it.id !in existing }.map { toListItem(it, installed) }
                items = items + appended
                total = page.total
                loadedPage = next
                loadCovers(page.items)
            } catch (failure: MarketException) {
                error = failure.message
            } catch (failure: Exception) {
                error = "加载更多失败：${failure.message}"
            } finally {
                loadingMore = false
            }
        }
    }

    fun goToPage(target: Int) {
        if (loading || loadingMore) return
        if (!requireConsent()) return
        val next = target.coerceIn(1, pageCount)
        if (next == page) return
        loading = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.list(baseUrl, settings.language(), query, tag, sort, next, PageSize)
                }
                val installed = withContext(Dispatchers.IO) { installedIndex() }
                items = result.items.map { summary -> toListItem(summary, installed) }
                tags = result.tags
                total = result.total
                loadedPage = next
                page = next
                loadCovers(result.items)
            } catch (failure: MarketException) {
                error = failure.message
            } catch (failure: Exception) {
                error = "翻页失败：${failure.message}"
            } finally {
                loading = false
            }
        }
    }

    fun updateQuery(value: String) {
        query = value
    }

    fun updateTag(value: String) {
        tag = value
        refresh()
    }

    fun updateSort(value: String) {
        if (sort == value) return
        sort = value
        refresh()
    }

    fun submitSearch() = refresh()

    fun clearSearch() {
        if (query.isEmpty()) return
        query = ""
        refresh()
    }

    // ============================== 同意 ==============================

    fun acceptConsent() {
        settings.marketConsentVersion = com.hiynet.aspatoys.data.settings.AppSettings.CurrentConsentVersion
        consentGranted = true
        showConsent = false
        refresh()
    }

    fun dismissConsent() {
        showConsent = false
        message = "未同意免责声明，本次不会连接市场。"
    }

    fun reviewConsent() {
        showConsent = true
    }

    /** 设置页改了市场地址 / 语言 / 同意状态之后调用：重建状态并按需重新拉目录。 */
    fun reloadSettings() {
        val newBaseUrl = settings.marketBaseUrl
        val baseUrlChanged = newBaseUrl != baseUrl
        baseUrl = newBaseUrl
        consentGranted = settings.marketConsentGranted
        if (!consentGranted) {
            showConsent = true
            return
        }
        if (baseUrlChanged) {
            items = emptyList()
            total = 0
            loadedPage = 0
            covers = emptyMap()
        }
        refresh()
    }

    private fun requireConsent(): Boolean {
        if (consentGranted) return true
        showConsent = true
        return false
    }

    // ============================== 详情 ==============================

    fun openDetail(id: String) {
        if (!requireConsent()) return
        detailLoading = true
        detailError = null
        detail = null
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    client.detail(baseUrl, id, settings.language())
                }
                detail = loaded
                ensureCover(loaded.id, loaded.coverUrl)
            } catch (failure: MarketException) {
                detailError = failure.message
            } catch (failure: Exception) {
                detailError = "读取模组详情失败：${failure.message}"
            } finally {
                detailLoading = false
            }
        }
    }

    fun closeDetail() {
        detail = null
        detailError = null
    }

    /** 详情页里选中的版本；为空时按市场登记的 latestVersion 取。 */
    fun versionOf(detail: MarketModDetail, version: String?): MarketModVersion? =
        version?.let { wanted -> detail.versions.firstOrNull { it.version.equals(wanted, ignoreCase = true) } }
            ?: detail.versions.firstOrNull { it.version.equals(detail.latestVersion, ignoreCase = true) }
            ?: detail.versions.firstOrNull()

    // ============================== 下载 ==============================

    fun downloadVersion(version: MarketModVersion) {
        val target = detail ?: return
        if (download != null) return
        if (!requireConsent()) return

        val handle = HttpCancellation()
        cancellation = handle
        report = null
        lastProgressBucket = -1
        download = DownloadState(
            modId = target.id,
            modName = target.name.ifBlank { target.id },
            version = version.version,
            phase = DownloadState.Phase.PREPARE,
            received = 0,
            total = null,
            percent = null,
        )

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    container.packageService().downloadAndCheck(
                        baseUrl = baseUrl,
                        detail = target,
                        version = version,
                        onProgress = ::onProgress,
                        onPhase = ::onPhase,
                        cancellation = handle,
                    )
                }
                if (!result.success) {
                    // 检查没过就停在这里：报告会说明是哈希、结构还是 SDK 门槛的问题。
                    report = result
                    message = "检查没通过，已停在报告里，没有写入加载器。"
                    return@launch
                }
                installIntoLoader(result)
            } catch (cancelled: HttpCancelledException) {
                message = "已取消下载。"
            } catch (failure: MarketException) {
                message = failure.message
            } catch (failure: Exception) {
                message = "下载失败：${failure.message}"
            } finally {
                download = null
                cancellation = null
            }
        }
    }

    /** 下载并检查通过之后，直接写进加载器（`mods/<ModId>/` 或 `disabled_mods/`）。 */
    private suspend fun installIntoLoader(result: PackageCheckReport) {
        mainHandler.post { download = download?.copy(phase = DownloadState.Phase.INSTALL) }
        try {
            val installed = withContext(Dispatchers.IO) {
                container.loader.install(java.io.File(result.filePath))
            }
            val where = if (installed.disabled) "disabled_mods" else "mods"
            val text = "已安装 ${result.modName} v${result.registeredVersion}：${installed.files} 个文件 → $where"
            message = if (installed.removed > 0) "$text（清理 ${installed.removed} 个旧文件）" else text
            Toasts.show(container.context, "${result.modName} 已装入加载器")
            report = null
            refreshInstalledBadges()
        } catch (failure: Exception) {
            // 已经下好了，别让它白下：把报告留给用户，并在报告里说清为什么没装进去。
            report = result
            val text = "下载完成，但写入加载器失败：${failure.message ?: failure::class.java.simpleName}"
            message = text
            Toasts.show(container.context, text)
        }
    }

    /** 报告里那个包已经落盘了：再试一次写进加载器（失败原因修好后不用重新下载）。 */
    fun installReport() {
        val target = report ?: return
        if (download != null || !target.success) return
        lastProgressBucket = -1
        download = DownloadState(
            modId = target.modId,
            modName = target.modName,
            version = target.registeredVersion,
            phase = DownloadState.Phase.INSTALL,
            received = 0,
            total = null,
            percent = null,
        )
        scope.launch {
            try {
                installIntoLoader(target)
            } finally {
                download = null
            }
        }
    }

    /** 装完之后重算目录里每个模组的「已装」角标。 */
    private suspend fun refreshInstalledBadges() {
        val installed = withContext(Dispatchers.IO) { container.loader.installedIndex() }
        items = items.map { item ->
            val entry = installed[item.summary.id.lowercase()]
            item.copy(installedVersion = entry?.first ?: "", enabled = entry?.second ?: true)
        }
    }

    fun cancelDownload() {
        cancellation?.cancel()
    }

    fun dismissReport() {
        report = null
    }

    fun clearMessage() {
        message = null
    }

    fun clearError() {
        error = null
    }

    private var lastProgressBucket = -1

    private fun onProgress(progress: DownloadProgress) {
        // 每 128 KB 都回推太吵：百分比（或 256 KB 桶）变了才更新一次界面。
        val bucket = progress.percent ?: ((progress.receivedBytes / (256 * 1024)).toInt())
        if (bucket == lastProgressBucket) return
        lastProgressBucket = bucket
        mainHandler.post {
            download = download?.copy(
                phase = DownloadState.Phase.DOWNLOAD,
                received = progress.receivedBytes,
                total = progress.totalBytes,
                percent = progress.percent,
            )
        }
    }

    private fun onPhase(phase: String) {
        val mapped = when (phase) {
            "verify" -> DownloadState.Phase.VERIFY
            "inspect" -> DownloadState.Phase.INSPECT
            else -> return
        }
        mainHandler.post { download = download?.copy(phase = mapped) }
    }

    // ============================== 封面 ==============================

    private fun loadCovers(summaries: List<MarketModSummary>) {
        val withCover = summaries.filter { !it.coverUrl.isNullOrBlank() }
        if (withCover.isEmpty()) return

        val known = covers.toMutableMap()
        val pending = mutableListOf<MarketModSummary>()
        for (summary in withCover) {
            val cached = container.coverStore.cached(summary.id, summary.coverUrl)
            if (cached != null) known[summary.id] = cached.path else pending += summary
        }
        covers = known
        if (pending.isEmpty()) return

        scope.launch {
            val gate = Semaphore(6)
            val results = pending.map { summary ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val file = container.coverStore.ensure(client, baseUrl, summary.id, summary.coverUrl)
                        summary.id to file?.path
                    }
                }
            }.awaitAll()
            val updated = covers.toMutableMap()
            var changed = false
            for ((id, path) in results) {
                if (path != null) {
                    updated[id] = path
                    changed = true
                }
            }
            if (changed) covers = updated
        }
    }

    private fun ensureCover(modId: String, coverUrl: String?) {
        if (coverUrl.isNullOrBlank()) return
        if (covers[modId] != null) return
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                container.coverStore.ensure(client, baseUrl, modId, coverUrl)
            }
            if (file != null) covers = covers + (modId to file.path)
        }
    }

    // ============================== 已装状态 ==============================

    private fun installedIndex(): Map<String, Pair<String, Boolean>> {
        return try {
            // 走同一份加载器通道：市场看到的「已装」必须与模组页一致。
            container.loader.installedIndex()
        } catch (_: Exception) {
            // 没读到时按「未安装」显示即可
            emptyMap()
        }
    }

    private fun toListItem(
        summary: MarketModSummary,
        installed: Map<String, Pair<String, Boolean>>,
    ): MarketListItem {
        val entry = installed[summary.id.lowercase()]
        return MarketListItem(
            summary = summary,
            installedVersion = entry?.first ?: "",
            enabled = entry?.second ?: true,
        )
    }

    companion object {
        const val PageSize = 24
        const val SortDownloads = "downloads"
        const val SortUpdated = "updated"
        const val SortName = "name"
    }
}
