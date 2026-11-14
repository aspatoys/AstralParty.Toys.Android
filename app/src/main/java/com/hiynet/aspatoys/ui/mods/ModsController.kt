package com.hiynet.aspatoys.ui.mods

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiynet.aspatoys.data.AppContainer
import com.hiynet.aspatoys.data.market.PackageCheckReport
import com.hiynet.aspatoys.data.mods.ModEntry
import com.hiynet.aspatoys.data.mods.ModIssue
import com.hiynet.aspatoys.data.mods.ModPackageException
import com.hiynet.aspatoys.data.settings.StorageAccess
import com.hiynet.aspatoys.ui.components.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 下载目录里的一个模组包。 */
data class DownloadedPackage(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val modifiedEpochMs: Long,
)

/**
 * 模组页状态：已装模组、下载目录里的模组包、单个包的检查与安装、单个模组的启停。
 *
 * 读写加载器全部经 [com.hiynet.aspatoys.data.loader.LoaderRepo]（设置改了就跟着变），
 * 页面自己不再判断「走 Shizuku 还是直读」。
 */
class ModsController(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    private val settings = container.settings
    private val repo = container.loader

    var loaderRootPath by mutableStateOf(settings.loaderRootPath)
        private set

    var downloadsPath by mutableStateOf(settings.downloadsDir.path)
        private set

    var installedSdkVersion by mutableStateOf<String?>(null)
        private set

    var installed by mutableStateOf<List<ModEntry>>(emptyList())
        private set

    var issues by mutableStateOf<List<ModIssue>>(emptyList())
        private set

    var packages by mutableStateOf<List<DownloadedPackage>>(emptyList())
        private set

    /** 正在做一次完整扫描（首屏或点「刷新」）。 */
    var scanning by mutableStateOf(false)
        private set

    var checking by mutableStateOf(false)
        private set

    var checkingName by mutableStateOf("")
        private set

    var report by mutableStateOf<PackageCheckReport?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    /** 是否已有「所有文件访问权限」——没有时市场下载仍可用，但读不了加载器目录。 */
    var allFilesAccess by mutableStateOf(StorageAccess.hasAllFilesAccess(context))
        private set

    /** 加载器根目录的可读/可写状态。 */
    var loaderRootAccess by mutableStateOf<StorageAccess.Access?>(null)
        private set

    /** 这次扫描用的读取方式：`Shizuku` / `直读`。界面上直接显示，避免靠日志猜。 */
    var scanMode by mutableStateOf("")
        private set

    /** 扫描失败原因（含 Shizuku 命令失败的具体输出）。 */
    var scanError by mutableStateOf<String?>(null)
        private set

    /** 正在切换启用状态的模组名；只锁那一行的开关。 */
    var busyModId by mutableStateOf<String?>(null)
        private set

    /** 刚检查过的包：报告里可以直接装进加载器。 */
    var checkedPackage by mutableStateOf<File?>(null)
        private set

    var installing by mutableStateOf(false)
        private set

    /** 同一时间只跑一次扫描，避免两次结果互相覆盖。 */
    private var refreshJob: Job? = null

    /** 上一次扫描对应的数据版本；市场装完模组后版本会变，再进这一页需要重扫。 */
    private var scannedVersion = -1

    /** 进入页面时调用：只有数据变了或从没扫过才真的去扫。 */
    fun onEnter() {
        if (scannedVersion != repo.version) refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        scanning = true
        refreshJob = scope.launch {
            try {
                scanNow()
            } catch (failure: Exception) {
                scanError = "读取模组目录失败：${failure.message ?: failure::class.java.simpleName}"
                message = scanError
            } finally {
                scanning = false
            }
        }
    }

    /**
     * 扫一遍并把结果写进状态。启停之后也用这个对齐（此时不显示整页「刷新中」）。
     */
    private suspend fun scanNow() {
        allFilesAccess = StorageAccess.hasAllFilesAccess(context)
        val loaderRoot = container.settings.loaderRoot
        loaderRootAccess = withContext(Dispatchers.IO) { StorageAccess.describe(loaderRoot) }

        val snapshot = withContext(Dispatchers.IO) { repo.scan() }
        installed = snapshot.entries
        installedSdkVersion = snapshot.sdkVersion
        issues = snapshot.issues
        scanMode = if (snapshot.viaShizuku) "Shizuku" else "直读（没拿到 Shizuku）"
        scanError = snapshot.error
        packages = withContext(Dispatchers.IO) { listPackages() }
        // 版本在扫描途中可能又变了；以扫完那一刻为准，避免多扫一次。
        scannedVersion = repo.version
    }

    /** 只重新检测存储权限与目录可读性（从系统设置授权回来后点一下）。 */
    fun refreshAccess() {
        scope.launch {
            allFilesAccess = StorageAccess.hasAllFilesAccess(context)
            loaderRootAccess = withContext(Dispatchers.IO) {
                StorageAccess.describe(container.settings.loaderRoot)
            }
            if (allFilesAccess) refresh()
        }
    }

    /** 跳到系统的「所有文件访问权限」页面。 */
    fun requestStorageAccess() {
        StorageAccess.openAllFilesAccessSettings(context)
    }

    fun reloadWorkspace() {
        loaderRootPath = settings.loaderRootPath
        downloadsPath = settings.downloadsDir.path
        repo.invalidate()
        refresh()
    }

    /** 检查下载目录里的一个模组包。 */
    fun checkPackage(file: File) {
        if (checking) return
        checking = true
        checkingName = file.name
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { container.packageService().checkLocal(file) }
                report = result
                checkedPackage = file
            } catch (failure: Exception) {
                message = "检查失败：${failure.message}"
            } finally {
                checking = false
                checkingName = ""
            }
        }
    }

    /**
     * 检查用户从文件管理器选中的 ZIP：先复制到缓存目录（限定 64 MB），再按本地包处理。
     * 用 SAF 的 content:// 而不是要求「所有文件访问权限」，避免为了检查一个包去申请全盘权限。
     */
    fun checkContentUri(uri: Uri) {
        if (checking) return
        checking = true
        checkingName = "选中的压缩包"
        scope.launch {
            try {
                val copied = withContext(Dispatchers.IO) { copyToCache(uri) }
                val result = withContext(Dispatchers.IO) { container.packageService().checkLocal(copied) }
                report = result
                checkedPackage = copied
            } catch (failure: ModPackageException) {
                message = failure.message
            } catch (failure: Exception) {
                message = "读取选中的文件失败：${failure.message}"
            } finally {
                checking = false
                checkingName = ""
            }
        }
    }

    fun deletePackage(file: File) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) { file.delete() }
            if (!deleted) {
                message = "删除失败：${file.name}"
                return@launch
            }
            packages = withContext(Dispatchers.IO) { listPackages() }
        }
    }

    fun dismissReport() {
        report = null
    }

    /** 把检查过的包解到加载器：`mods/<ModId>/`，并清掉上次清单里多的文件。 */
    fun installCheckedPackage() {
        val file = checkedPackage ?: run { message = "先检查一个模组包，再安装"; return }
        if (installing) return
        installing = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repo.install(file) }
                val where = if (result.disabled) "disabled_mods" else "mods"
                Toasts.show(context, "${result.modId} 已装入 $where")
                message = buildString {
                    append("已安装 ${result.modId} 到 $where：${result.files} 个文件")
                    if (result.removed > 0) append("，清理 ${result.removed} 个旧文件")
                }
                checkedPackage = null
                report = null
                scanNow()
            } catch (failure: Exception) {
                val text = "安装到加载器失败：${failure.message ?: failure::class.java.simpleName}"
                message = text
                Toasts.show(context, text)
            } finally {
                installing = false
            }
        }
    }

    /**
     * 启用/停用。
     *
     * 先把开关就地翻过去（点一下立刻跟手），再真的搬目录，最后后台对齐一次；
     * 失败就翻回去并提示原因，绝不让界面停在一个假的开关位置上。
     */
    fun setEnabled(entry: ModEntry, enabled: Boolean) {
        if (entry.enabled == enabled) return
        if (busyModId == entry.name) return
        busyModId = entry.name

        val before = installed
        installed = installed.map {
            if (it.name == entry.name) it.copy(enabled = enabled, disabledFolder = !enabled) else it
        }

        scope.launch {
            try {
                withContext(Dispatchers.IO) { repo.setEnabled(entry, enabled) }
                Toasts.show(
                    context,
                    if (enabled) "${entry.shownName} 已启用" else "${entry.shownName} 已停用",
                )
                // 搬完再扫一次：以磁盘为准，顺便把别的模组的变化一起收进来。
                scanNow()
            } catch (failure: Exception) {
                installed = before
                val text = failure.message ?: failure::class.java.simpleName
                message = "切换状态失败：$text"
                Toasts.show(context, "切换失败：$text")
            } finally {
                busyModId = null
            }
        }
    }

    fun clearMessage() {
        message = null
    }

    private fun listPackages(): List<DownloadedPackage> {
        val dir = settings.downloadsDir
        val files = dir.listFiles { file -> file.isFile && file.extension.equals("zip", ignoreCase = true) }
            ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { file ->
            DownloadedPackage(
                file = file,
                name = file.name,
                sizeBytes = file.length(),
                modifiedEpochMs = file.lastModified(),
            )
        }
    }

    private fun copyToCache(uri: Uri): File {
        val name = displayName(uri) ?: "picked-${System.currentTimeMillis()}.zip"
        val safeName = name.map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_' || char == '.') char else '_'
        }.joinToString("")
        val targetDir = File(context.cacheDir, "picked").apply { mkdirs() }
        val target = File(targetDir, safeName.ifBlank { "picked.zip" })
        val resolver = context.contentResolver
        val input = resolver.openInputStream(uri) ?: throw ModPackageException("无法打开选中的文件。")
        input.use { source ->
            target.outputStream().use { sink ->
                val buffer = ByteArray(128 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_PICKED_BYTES) {
                        target.delete()
                        throw ModPackageException(
                            "选中的压缩包超过 ${MAX_PICKED_BYTES / 1024 / 1024} MB，已中止。",
                        )
                    }
                    sink.write(buffer, 0, read)
                }
            }
        }
        return target
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        /** 从文件管理器选包时的复制上限，与模组包本身的上限一致。 */
        const val MAX_PICKED_BYTES = 64L * 1024 * 1024
    }
}
