package com.hiynet.aspatoys.ui.mods

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiynet.aspatoys.data.AppContainer
import com.hiynet.aspatoys.data.market.PackageCheckReport
import com.hiynet.aspatoys.data.mods.ConfigField
import com.hiynet.aspatoys.data.mods.ModConfigText
import com.hiynet.aspatoys.data.mods.ModEntry
import com.hiynet.aspatoys.data.mods.ModIssue
import com.hiynet.aspatoys.data.mods.ModPackageException
import com.hiynet.aspatoys.data.settings.StorageAccess
import com.hiynet.aspatoys.installer.ShizukuShell
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

/** 模组配置编辑器的状态。 */
data class ConfigEditorState(
    val entry: ModEntry,
    val modId: String,
    val title: String,
    val path: String,
    val loading: Boolean = true,
    /** 原始文本：保存时原样写回，注释与顺序都保留。 */
    val text: String = "",
    val fields: List<ConfigField> = emptyList(),
    val uneditable: List<String> = emptyList(),
    /** 文本编辑模式（表单改不了的东西在这里改）。 */
    val rawMode: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
    /** 文本被改过、还没保存。 */
    val dirty: Boolean = false,
) {
    val editable: Boolean get() = fields.isNotEmpty() || rawMode
}

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

    /** 扫描序号；只有最新一次的结果才会写进界面。 */
    private var scanTicket = 0

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
     *
     * 每次扫描领一个号，只有**最新一次**的结果才会落地：一次扫描要跑几十条 shell 命令，
     * 连点两下开关时旧的那次会晚到，直接写进去就会把刚翻好的开关顶回去 —— 这正是
     * 「点了开关又自己弹回来」的来源。
     */
    private suspend fun scanNow() {
        val ticket = ++scanTicket
        allFilesAccess = StorageAccess.hasAllFilesAccess(context)
        val loaderRoot = container.settings.loaderRoot
        loaderRootAccess = withContext(Dispatchers.IO) { StorageAccess.describe(loaderRoot) }

        val snapshot = withContext(Dispatchers.IO) { repo.scan() }
        if (ticket != scanTicket) return

        installed = snapshot.entries
        installedSdkVersion = snapshot.sdkVersion
        issues = snapshot.issues
        scanMode = if (snapshot.viaShizuku) ShizukuShell.modeName() else "直读（无提权）"
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

    // ============================== 模组配置 ==============================

    /** 正在编辑配置的模组；null = 没开面板。 */
    var configEditor by mutableStateOf<ConfigEditorState?>(null)
        private set

    /** 正在删除的模组；非 null 时界面禁用删除按钮。 */
    var deletingModId by mutableStateOf<String?>(null)
        private set

    /** 打开某个模组的配置面板。 */
    fun openConfig(entry: ModEntry) {
        val path = repo.configPath(entry.name)
        configEditor = ConfigEditorState(
            entry = entry,
            modId = entry.name,
            title = entry.shownName,
            path = path,
            loading = true,
        )
        scope.launch {
            val text = withContext(Dispatchers.IO) { repo.readConfig(entry.name) }
            val current = configEditor ?: return@launch
            if (current.modId != entry.name) return@launch
            if (text == null) {
                configEditor = current.copy(
                    loading = false,
                    error = "还没有配置文件。这个模组可能第一次运行游戏时才写出默认值；" +
                        "改不了就先启动一次游戏。",
                )
                return@launch
            }
            val parsed = withContext(Dispatchers.Default) {
                ModConfigText.parse(text, settings.language())
            }
            configEditor = current.copy(
                loading = false,
                text = text,
                fields = parsed?.fields.orEmpty(),
                uneditable = parsed?.uneditable.orEmpty(),
                error = if (parsed == null) "配置不是合法 JSON，只能整段改文本。" else null,
                rawMode = parsed == null,
            )
        }
    }

    fun closeConfig() {
        configEditor = null
    }

    /** 删除当前配置面板对应的模组；成功后关闭面板并重扫。 */
    fun deleteMod(entry: ModEntry) {
        if (deletingModId != null) return
        deletingModId = entry.name
        scope.launch {
            try {
                val logs = mutableListOf<String>()
                withContext(Dispatchers.IO) {
                    repo.deleteMod(entry) { line -> logs += line }
                }
                configEditor = null
                scanNow()
                message = "已删除 ${entry.shownName}"
            } catch (failure: Throwable) {
                message = "删除失败：" + (failure.message ?: failure.toString())
            } finally {
                deletingModId = null
            }
        }
    }

    /** 文本编辑模式下的整段文本改动。 */
    fun updateConfigText(text: String) {
        val current = configEditor ?: return
        val parsed = ModConfigText.parse(text, settings.language())
        configEditor = current.copy(
            text = text,
            fields = parsed?.fields.orEmpty(),
            uneditable = parsed?.uneditable.orEmpty(),
            dirty = true,
            error = if (parsed == null) "现在不是合法 JSON，保存会被拒绝。" else null,
        )
    }

    fun setConfigRawMode(raw: Boolean) {
        val current = configEditor ?: return
        configEditor = current.copy(rawMode = raw)
    }

    /**
     * 表单里改一个字段：就地替换文本里的那个值，其余（注释、空行、键顺序）原样不动。
     */
    fun updateConfigField(field: ConfigField, newToken: String) {
        val current = configEditor ?: return
        if (newToken == field.token) return
        val patched = ModConfigText.patchValue(current.text, field.key, newToken)
        if (patched == null) {
            configEditor = current.copy(error = "改不动 ${field.key}：在文本里找不到它的值，请用文本编辑。")
            return
        }
        val parsed = ModConfigText.parse(patched, settings.language())
        configEditor = current.copy(
            text = patched,
            fields = parsed?.fields.orEmpty(),
            uneditable = parsed?.uneditable.orEmpty(),
            dirty = true,
            error = null,
        )
    }

    /** 保存到 `configs/<ModId>.json`；加载器会热重载（下一次轮询就生效）。 */
    fun saveConfig() {
        val current = configEditor ?: return
        if (current.saving) return
        if (ModConfigText.parse(current.text, settings.language()) == null) {
            configEditor = current.copy(error = "不是合法 JSON，没保存。")
            return
        }
        configEditor = current.copy(saving = true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repo.writeConfig(current.modId, current.text) }
                configEditor = configEditor?.copy(saving = false, dirty = false, error = null)
                Toasts.show(context, "${current.title} 配置已保存")
            } catch (failure: Exception) {
                val text = "保存失败：${failure.message ?: failure::class.java.simpleName}"
                configEditor = configEditor?.copy(saving = false, error = text)
                Toasts.show(context, text)
            }
        }
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
