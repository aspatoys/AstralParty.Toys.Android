package com.hiynet.aspatoys.data.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import com.hiynet.aspatoys.BuildConfig
import com.hiynet.aspatoys.core.HttpCancellation
import com.hiynet.aspatoys.data.loader.ShizukuLoaderFs
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.core.parseJsonObjectOrNull
import com.hiynet.aspatoys.core.string
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest

/**
 * 让界面跟随加载器导入 / 更新的过程（例如画进度面板）。不传时只更新 [Updates.loaderStatus]，和以前一样。
 * 回调可能在后台线程上。
 */
interface LoaderProgressListener {
    /** 换到下一步，或者底层打了一行进度。 */
    fun step(text: String)
    /** 下载字节进度；total 未知时为 0。 */
    fun download(received: Long, total: Long) {}
    /** 结束：成功或失败，附上给用户看的说明。 */
    fun finished(success: Boolean, text: String)
}

/** Process-wide jobs survive activity recreation; install prompts are only launched in the foreground. */
class Updates private constructor(context: Context) {
    companion object {
        @Volatile private var instance: Updates? = null
        fun get(context: Context): Updates = instance ?: synchronized(this) { instance ?: Updates(context.applicationContext).also { instance = it } }
        const val CERT_SHA256 = ApkPolicy.CERT_SHA256
    }
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val github = GitHubUpdates({ mirrorType to customMirror }, BuildConfig.VERSION_NAME)
    private val fs = ShizukuLoaderFs()
    private val apkFile = File(app.cacheDir, "updates/apks/update.apk")
    private val zipFile = File(app.cacheDir, "updates/loader.zip")
    private var appCancel: HttpCancellation? = null
    private var loaderCancel: HttpCancellation? = null
    private var foreground = false
    private var checkedThisProcess = false
    private var pendingPermission = false
    private var promptedVersion: String? = null
    private var installingApp = false

    var autoCheck by mutableStateOf(prefs.getBoolean("autoCheck", true)); private set
    var autoDownload by mutableStateOf(prefs.getBoolean("autoDownload", true)); private set
    var autoInstall by mutableStateOf(prefs.getBoolean("autoInstall", true)); private set
    var mirrorType by mutableStateOf(prefs.getString("mirror", "gh-proxy") ?: "gh-proxy"); private set
    var customMirror by mutableStateOf(prefs.getString("customMirror", "") ?: ""); private set
    fun preference(key: String, value: Boolean) {
        when (key) { "autoCheck" -> autoCheck = value; "autoDownload" -> autoDownload = value; "autoInstall" -> autoInstall = value }
        prefs.edit().putBoolean(key, value).apply()
    }
    fun mirror(type: String, custom: String) {
        GitHubUpdates.mirrorUrl(GitHubUpdates.LOADER_API, type, custom)
        mirrorType = type; customMirror = custom.trim()
        prefs.edit().putString("mirror", type).putString("customMirror", customMirror).apply()
    }
    var appRelease by mutableStateOf<UpdateRelease?>(null); private set
    var appBusy by mutableStateOf(false); private set
    var appStatus by mutableStateOf("当前版本 ${BuildConfig.VERSION_NAME}"); private set
    var ready by mutableStateOf(false); private set
    var loaderRelease by mutableStateOf<UpdateRelease?>(null); private set
    var loaderBusy by mutableStateOf(false); private set
    var loaderStatus by mutableStateOf("尚未检查 GitHub 加载器更新"); private set
    var installedLoader by mutableStateOf(""); private set
    fun foreground(active: Boolean) {
        foreground = active
        if (!active) return
        if (pendingPermission && app.packageManager.canRequestPackageInstalls()) { pendingPermission = false; installApp(); return }
        if (!checkedThisProcess && autoCheck) { checkedThisProcess = true; checkApp(false) }
        else if (ready && autoInstall && promptedVersion != appRelease?.version && !pendingPermission) installApp()
    }
    fun checkApp(manual: Boolean = true) {
        if (appBusy) return
        appBusy = true; appStatus = "正在检查软件更新"; val cancel = HttpCancellation(); appCancel = cancel
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { github.check(false, cancel) }
                if (appRelease?.sha256 != release.sha256) ready = false
                appRelease = release
                if (!GitHubUpdates.newer(release.version, BuildConfig.VERSION_NAME)) { ready = false; appStatus = "已是最新版本（GitHub ${release.version}）" }
                else {
                    appStatus = "发现新版本 ${release.version}"
                    if (!manual && autoDownload) downloadAppNow(release, cancel)
                }
            } catch (error: Exception) { appStatus = error.message ?: "检查失败" }
            finally { appBusy = false; appCancel = null }
        }
    }
    fun downloadApp() {
        val release = appRelease ?: return
        if (appBusy || !GitHubUpdates.newer(release.version, BuildConfig.VERSION_NAME)) return
        appBusy = true; val cancel = HttpCancellation(); appCancel = cancel
        scope.launch {
            try { downloadAppNow(release, cancel) }
            catch (error: Exception) { ready = false; appStatus = error.message ?: "下载失败" }
            finally { appBusy = false; appCancel = null }
        }
    }
    private suspend fun downloadAppNow(release: UpdateRelease, cancel: HttpCancellation) {
        ready = false
        withContext(Dispatchers.IO) {
            github.download(release, apkFile, cancel) { count, total -> appStatus = "下载软件更新 ${if (total > 0) "${count * 100 / total}%" else "${count / 1024} KB"}" }
            validateApk(apkFile, release)
        }
        ready = true; appStatus = "${release.version} 已下载并通过签名校验，可安装"
        if (autoInstall && foreground) installApp()
    }
    @Suppress("DEPRECATION")
    private fun validateApk(file: File, release: UpdateRelease) {
        check(GitHubUpdates.hash(file).equals(release.sha256, true)) { "APK 校验值已变化，请重新下载。" }
        val pm = app.packageManager
        val candidate = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("下载文件不是有效 APK。")
        val current = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }?.toSet().orEmpty()
        fun metadata(info: android.content.pm.PackageInfo) = ApkPolicy.Metadata(info.packageName, info.longVersionCode,
            info.versionName.orEmpty(), info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE, signers(info))
        ApkPolicy.verify(metadata(candidate), metadata(current), release.version, android.os.Build.VERSION.SDK_INT)
    }
    fun installApp() {
        val release = appRelease ?: return
        if (!ready || installingApp) return
        installingApp = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { validateApk(apkFile, release) }
                if (!foreground) { appStatus = "更新已就绪，回到应用后安装。"; return@launch }
                if (!app.packageManager.canRequestPackageInstalls()) {
                    pendingPermission = true
                    appStatus = "请允许本应用安装更新，授权后返回继续。"
                    app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return@launch
                }
                pendingPermission = false; promptedVersion = release.version
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", apkFile)
                app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                appStatus = "已打开系统安装界面；请确认安装。取消后可再次点安装。"
            } catch (error: Exception) { appStatus = "安装更新失败：${error.message}" }
            finally { installingApp = false }
        }
    }
    fun cancelApp() { appCancel?.cancel() }

    fun refreshInstalled() {
        scope.launch {
            installedLoader = withContext(Dispatchers.IO) {
                val root = AppSettings(app).loaderRootPath
                val obj = fs.readText("$root/installed.json")?.let(::parseJsonObjectOrNull)
                if (obj == null) "" else obj.string("loaderVersion").ifBlank { obj.string("sdkVersion") }
            }
        }
    }
    fun checkLoader() {
        if (loaderBusy) return
        loaderBusy = true; loaderStatus = "正在检查 GitHub 加载器"; val cancel = HttpCancellation(); loaderCancel = cancel
        refreshInstalled()
        scope.launch {
            try {
                loaderRelease = withContext(Dispatchers.IO) { github.check(true, cancel) }
                loaderStatus = "GitHub 最新 ${loaderRelease!!.version}"
            } catch (error: Exception) { loaderStatus = error.message ?: "检查加载器失败" }
            finally { loaderBusy = false; loaderCancel = null }
        }
    }
    /** 返回 false 表示没有开始（没检查过版本，或者已有加载器操作在进行），此时不会回调 [listener]。 */
    fun updateLoader(listener: LoaderProgressListener? = null, onUpdated: () -> Unit): Boolean {
        val release = loaderRelease ?: return false
        if (loaderBusy) return false
        val root = AppSettings(app).loaderRootPath // Capture once; settings changes never redirect a running update.
        loaderBusy = true; val cancel = HttpCancellation(); loaderCancel = cancel
        fun step(text: String) { loaderStatus = text; listener?.step(text) }
        scope.launch {
            val stage = File(app.cacheDir, "updates/loader-stage-${java.util.UUID.randomUUID()}")
            try {
                val result = withContext(Dispatchers.IO) {
                    check(fs.isAvailable()) { "请先在加载器页连接 Shizuku / Root。" }
                    listener?.step("正在下载加载器 ${release.version}…")
                    github.download(release, zipFile, cancel) { count, total ->
                        loaderStatus = "下载加载器 ${if (total > 0) "${count * 100 / total}%" else "${count / 1024} KB"}"
                        listener?.download(count, total)
                    }
                    step("正在校验并解压发布包…")
                    val installer = ManagedLoaderUpdate(fs)
                    val pkg = installer.prepare(zipFile, release.version, stage)
                    cancel.throwIfCancelled()
                    withContext(NonCancellable) { installer.install(root, pkg) { step(it) } }
                }
                loaderStatus = buildString {
                    append("加载器 ${release.version} 已更新；请完全重启游戏。安卓变速与相机已保留。")
                    // Conflicts stay entirely local; the user decides which copy survives.
                    if (result.skippedLocalMods.isNotEmpty())
                        append(" 以下模组在启用和停用目录中同时存在，已保留本地文件并跳过更新：${result.skippedLocalMods.joinToString("、")}。")
                }
                listener?.finished(true, loaderStatus)
                refreshInstalled(); onUpdated()
            } catch (error: Exception) {
                loaderStatus = "加载器更新失败：${error.message}"
                listener?.finished(false, loaderStatus)
            }
            finally { withContext(Dispatchers.IO) { stage.deleteRecursively() }; loaderBusy = false; loaderCancel = null }
        }
        return true
    }
    /** 返回 false 表示没有开始（已有加载器操作在进行），此时不会回调 [listener]。 */
    fun importLoader(uri: Uri, listener: LoaderProgressListener? = null, onUpdated: () -> Unit): Boolean {
        if (loaderBusy) return false
        val root = AppSettings(app).loaderRootPath // Capture once; settings changes never redirect a running import.
        loaderBusy = true
        fun step(text: String) { loaderStatus = text; listener?.step(text) }
        step("正在读取本地发布包…")
        scope.launch {
            val stage = File(app.cacheDir, "updates/loader-stage-${java.util.UUID.randomUUID()}")
            try {
                val (pkg, result) = withContext(Dispatchers.IO) {
                    check(fs.isAvailable()) { "请先在加载器页连接 Shizuku / Root。" }
                    stage.mkdirs()
                    val localZip = File(stage, "imported-loader.zip")
                    val input = app.contentResolver.openInputStream(uri) ?: error("无法读取所选发布包。")
                    input.use { src ->
                        localZip.outputStream().use { dst ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            val limit = 256L * 1024 * 1024
                            while (true) {
                                val read = src.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= limit) { "发布包文件过大（上限 256 MB）。" }
                                dst.write(buffer, 0, read)
                            }
                        }
                    }
                    step("正在校验发布包清单和 SHA-256…")
                    val installer = ManagedLoaderUpdate(fs)
                    val pkg = installer.prepare(localZip, expectedVersion = null, staging = File(stage, "unpacked"))
                    val result = withContext(NonCancellable) {
                        installer.install(root, pkg, source = "local-import") { step(it) }
                    }
                    pkg to result
                }
                loaderStatus = buildString {
                    append("已导入发布包 ${pkg.version}（${pkg.mods.size} 个模组）；请完全重启游戏。安卓变速与相机已保留。")
                    if (result.skippedLocalMods.isNotEmpty())
                        append(" 以下模组在启用和停用目录中同时存在，已保留本地文件并跳过更新：${result.skippedLocalMods.joinToString("、")}。")
                }
                listener?.finished(true, loaderStatus)
                refreshInstalled(); onUpdated()
            } catch (error: Exception) {
                loaderStatus = "导入发布包失败：${error.message}"
                listener?.finished(false, loaderStatus)
            } finally {
                withContext(Dispatchers.IO) { stage.deleteRecursively() }
                loaderBusy = false
            }
        }
        return true
    }
    fun cancelLoader() { loaderCancel?.cancel() }
}
