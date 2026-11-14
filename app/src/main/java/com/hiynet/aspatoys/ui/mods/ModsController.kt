package com.hiynet.aspatoys.ui.mods

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiynet.aspatoys.data.AppContainer
import com.hiynet.aspatoys.data.market.PackageCheckReport
import com.hiynet.aspatoys.data.mods.InstalledMods
import com.hiynet.aspatoys.data.mods.ModEntry
import com.hiynet.aspatoys.data.mods.ModIssue
import com.hiynet.aspatoys.data.mods.ModPackageException
import com.hiynet.aspatoys.data.settings.StorageAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * 模组页状态：扫工作区里的已装模组、列出下载的模组包、对单个包做检查。
 *
 * 工作区路径来自设置（[AppContainer.settings]），改完设置调用 [reloadWorkspace] 即可生效。
 */
class ModsController(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    private val settings = container.settings

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

    /** 是否已有「所有文件访问权限」——没有就读不到 `/sdcard/CesiumLoader`。 */
    var allFilesAccess by mutableStateOf(StorageAccess.hasAllFilesAccess(context))
        private set

    /** 加载器根目录的可读/可写状态。 */
    var loaderRootAccess by mutableStateOf<StorageAccess.Access?>(null)
        private set

    /** 第一次进入时自动扫一次，避免用户看到空列表以为没有模组。 */
    var initialized by mutableStateOf(false)
        private set

    fun refresh() {
        if (scanning) return
        scanning = true
        scope.launch {
            try {
                allFilesAccess = StorageAccess.hasAllFilesAccess(context)
                loaderRootAccess = withContext(Dispatchers.IO) {
                    StorageAccess.describe(container.settings.loaderRoot)
                }
                val snapshot = withContext(Dispatchers.IO) {
                    val layout = container.layout()

                    // 加载器在游戏的私有目录里：即使拿到「所有文件访问权限」，
                    // Android 11+ 也不允许列别的应用的 Android/data —— canRead() 可能是 true
                    // 但 listFiles() 是空的，所以有 Shizuku 就一律走 Shizuku。
                    val remote = com.hiynet.aspatoys.data.loader.ShizukuLoaderFs()
                    val usable = remote.isAvailable() || com.hiynet.aspatoys.installer.ShizukuShell.awaitBinder()
                    android.util.Log.i("CesiumMods", "scan root=${layout.loaderRoot} shizuku=$usable")

                    val entries = if (usable) {
                        InstalledMods.scanRemote(layout, remote)
                    } else {
                        InstalledMods.scan(layout)
                    }
                    android.util.Log.i("CesiumMods", "scan result=${entries.size}")

                    val sdk = layout.installedSdkVersion { path ->
                        if (usable) remote.readText(path) else null
                    }
                    Triple(entries, sdk, InstalledMods.diagnose(entries, sdk))
                }
                installed = snapshot.first
                installedSdkVersion = snapshot.second
                issues = snapshot.third
                packages = withContext(Dispatchers.IO) { listPackages() }
                initialized = true
            } catch (failure: Exception) {
                message = "读取模组目录失败：${failure.message}"
            } finally {
                scanning = false
            }
        }
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
