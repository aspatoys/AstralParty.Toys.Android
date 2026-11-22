package com.hiynet.aspatoys.installer

import android.content.Context
import android.content.res.Configuration
import com.hiynet.aspatoys.data.loader.BuiltinModDeployer
import com.hiynet.aspatoys.data.loader.BuiltinOverlay
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.data.loader.ShizukuLoaderFs
import com.hiynet.aspatoys.data.loader.LoaderFs
import com.hiynet.aspatoys.ui.theme.OverlayPalette
import com.hiynet.cesiumloader.core.AndroidPaths
import com.hiynet.cesiumloader.core.unity.TextAssetPatch
import com.hiynet.cesiumloader.core.unity.UnityFs
import com.hiynet.cesiumloader.core.unity.UnityFsWriter
import java.io.File
import java.security.MessageDigest

/**
 * 加载器安装器：把引导调用写进游戏的热更缓存包，并把 SDK / 引导 DLL 放到加载器目录。
 *
 * 全部写盘都经 [ShizukuShell]（`shell` 身份）完成，因为目标在游戏的私有目录里。
 *
 * 已知旧版使用随包校验过的产物。其它版本在本机编辑当前缓存内的程序集。
 * 恢复记录按缓存路径和修改前后的 SHA-256 保存。
 */
class LoaderInstaller(
    private val context: Context,
    private val gamePackage: String = GAME_PACKAGE,
    private val bundleIo: BundleIo = ShizukuBundleIo(File(workspace(context, gamePackage), "transport"), gamePackage),
    private val originalSource: () -> ByteArray = { context.assets.open(ORIGINAL_ASSET).use { it.readBytes() } },
    private val candidatePaths: () -> List<String> = { discoverBundlePaths(gamePackage) },
    private val serviceAvailable: () -> Boolean = { ShizukuShell.isServiceAlive() },
    private val permissionGranted: () -> Boolean = { ShizukuShell.hasPermission() },
    private val loaderFs: LoaderFs = ShizukuLoaderFs(),
    private val editAssembly: (ByteArray) -> ByteArray = OfflineAssemblyPatcher(context, gamePackage)::patch,
    private val checkNativeLoader: () -> Unit = { rejectNativeLoader(gamePackage) },
) {

    companion object {
        const val GAME_PACKAGE = "com.feimo.astralparty"
        const val BUNDLE_KEY = "4da1f23c112208b0f8df2a1327b4e4ce"
        const val ASSEMBLY = "AstralParty.Runtime.dll"

        /** 当前游戏热更版本的原包指纹（未注入过）。 */
        const val ORIGINAL_SHA256 = "77da946bf7d331351a93ff16707aab426f534b7577a771f21267478b7bc70c87"
        const val PATCHED_SHA256 = "37fc6d8576529a501c9a3aa0bef25ad70ad13881c0b811f9d245d434eac57991"
        const val ORIGINAL_ASSET = "cesium/recovery/original.bundle"

        /** 随包注入产物的指纹，防止 assets 被换掉。 */
        const val INJECTED_SHA256 = "b5ed46ddef35842d1695476ca6edfc2849f8938f22f02ddb05c608dd91ab4b2c"

        const val SDK_ASSET = "cesium/sdk/CesiumLoader.SDK.dll"
        const val BOOTSTRAP_ASSET = "cesium/bootstrap/CesiumLoader.Android.Bootstrap.dll"
        const val MODS_ASSET_DIR = "cesium/mods"
        const val INJECTED_ASSET = "cesium/AstralParty.Runtime.injected.dll"

        /** `syncLoaderAssets` 从发行版 zip 写出来的加载器版本。 */
        const val LOADER_VERSION_ASSET = "cesium/loader.json"

        /** 读不到 loader.json 时用的基线版本。 */
        const val FALLBACK_LOADER_VERSION = "3.8.7"

        val LOADER_ROOT = "/sdcard/Android/data/$GAME_PACKAGE/files/CesiumLoader"

        /** 与 [com.hiynet.cesiumloader.core.CesiumLayout] 保持一致：SDK 在 sdk/，引导在 bootstrap/。 */
        val BOOTSTRAP_REMOTE = "$LOADER_ROOT/bootstrap/CesiumLoader.Android.Bootstrap.dll"

        private fun workspace(context: Context, gamePackage: String): File {
            require(AndroidPaths.isGamePackage(gamePackage))
            val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "loader-installer")
            return if (gamePackage == GAME_PACKAGE) base else File(base.parentFile, "loader-installer-channels/$gamePackage")
        }

        fun installedGamePackages(exec: (String) -> String = { ShizukuShell.exec(it) }): List<String> {
            val output = runCatching { exec("pm list packages --user current") }
                .getOrNull()?.takeIf { it.isNotBlank() }
                ?: exec("pm list packages")
            return parseInstalledGamePackages(output)
        }

        internal fun parseInstalledGamePackages(pmOutput: String): List<String> =
            pmOutput.lineSequence()
                .map { it.removePrefix("package:").trim() }
                .filter(AndroidPaths::isGamePackage)
                .distinct()
                .sorted()
                .toList()

        /**
         * Pick an installed channel package when the user has not explicitly chosen one yet:
         * - if only one game package is installed, select it;
         * - if the default package is not installed at all, select the first installed channel package.
         */
        fun autoSelectPackage(installed: List<String>, current: String, selected: String?): String? {
            if (selected != null || installed.isEmpty()) return null
            val single = installed.singleOrNull()
            if (single != null) return single.takeIf { it != current }
            return if (current in installed) null else installed.first()
        }

        private fun discoverBundlePaths(gamePackage: String): List<String> {
            val base = File(AndroidPaths.bundleCacheDir(gamePackage), BUNDLE_KEY).invariantSeparatorsPath
            val pattern = ShizukuShell.quote(base) + "/*/__data"
            // Unity leaves old version folders behind; prefer the newest cache instead of lexical order.
            return ShizukuShell.exec("ls -t $pattern 2>/dev/null; true")
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }

        private fun rejectNativeLoader(gamePackage: String) {
            val quoted = ShizukuShell.quote(gamePackage)
            val rawPaths = runCatching { ShizukuShell.exec("pm path --user current $quoted") }
                .getOrNull()?.takeIf { it.contains("package:") }
                ?: ShizukuShell.exec("pm path $quoted")
            val paths = rawPaths.lineSequence()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .toList()
            require(paths.isNotEmpty()) { "当前用户空间没有安装所选游戏：$gamePackage" }
            for (path in paths) {
                require(!ApkZipScanner.containsNativeLoader(path)) { "检测到原生加载器，拒绝叠加安装。请先恢复官方客户端。" }
            }
        }
    }

    data class Status(
        val shizukuAlive: Boolean = false,
        val shizukuGranted: Boolean = false,
        val bundlePath: String? = null,
        val bundleSha256: String? = null,
        val injected: Boolean = false,
        val supportedVersion: Boolean = false,
        val hasBackup: Boolean = false,
        val loaderPayload: Boolean = false,
        val note: String = "",
        val cacheState: String = "未检测",
        /** 加载器文件属主有问题时的说明（游戏会读不到，表现为「装了但没模组」）。 */
        val ownershipWarning: String? = null,
    )

    private val isChannel = gamePackage != GAME_PACKAGE
    private val loaderRoot = AndroidPaths.gameLoaderDir(gamePackage).invariantSeparatorsPath
    private val bootstrapPath = "$loaderRoot/bootstrap/CesiumLoader.Android.Bootstrap.dll"
    private val owner = if (isChannel) LoaderOwnership(gamePackage) else LoaderOwnership.default
    private val workDir: File get() = workspace(context, gamePackage).apply { mkdirs() }

    private val originalPayload by lazy { originalSource() }
    private val channelCompatibility by lazy {
        ChannelRuntimeCompatibility.bundled(context, editAssembly, OfflineAssemblyPatcher(context, gamePackage)::patchReference)
    }
    private val transaction by lazy {
        val editor = CurrentCacheEditor { input, log ->
            val force = AppSettings(context).forceChannelCompatibility
            if (isChannel || force) channelCompatibility.patch(input, force, log)
            else editAssembly(input)
        }
        LoaderBundleTransaction(workDir, bundleIo, { originalPayload }, ::patchBundle, ::deployPayload,
            ORIGINAL_SHA256, PATCHED_SHA256, editor::patch, useLegacyProfile = !isChannel)
    }

    // ---------------------------------------------------------------- 状态

    fun status(): Status {
        owner.reset()
        val rootSelected = ShizukuShell.selectedMode() == com.hiynet.aspatoys.data.settings.AppSettings.PrivilegeRoot
        if (!serviceAvailable()) {
            return Status(
                note = if (rootSelected) {
                    "Root 不可用：${RootShell.lastError ?: "没有找到 su"}"
                } else {
                    "Shizuku 没有运行：先启动 Shizuku 服务"
                },
            )
        }
        if (!permissionGranted()) {
            return Status(
                shizukuAlive = true,
                note = if (rootSelected) "Root 不可用：${RootShell.lastError ?: "没有拿到 su 权限"}" else "还没授权 Shizuku",
            )
        }

        val resolution = runCatching { resolveBundle() }
        val path = resolution.getOrNull()
            ?: return Status(shizukuAlive = true, shizukuGranted = true,
                note = resolution.exceptionOrNull()?.message ?: "找不到游戏缓存包：先启动一次游戏")
        val sha = runCatching { bundleIo.hash(path) }.getOrNull()
        val state = transaction.classify(sha, path)

        return Status(
            shizukuAlive = true,
            shizukuGranted = true,
            bundlePath = path,
            bundleSha256 = sha,
            injected = state == LoaderBundleTransaction.State.INSTALLED,
            supportedVersion = state != LoaderBundleTransaction.State.UNKNOWN,
            hasBackup = transaction.backupAvailable(sha, path),
            loaderPayload = runCatching { hasPayload() }.getOrDefault(false),
            note = when {
                sha == null -> "读不到缓存包指纹"
                state == LoaderBundleTransaction.State.ORIGINAL -> "原版缓存包，可以安装"
                state == LoaderBundleTransaction.State.INSTALLED -> "已识别当前缓存的加载器入口，可更新或还原"
                else -> "未知缓存版本，安装时将在本机检查并编辑当前程序集"
            },
            cacheState = when (state) {
                LoaderBundleTransaction.State.ORIGINAL -> "原版"
                LoaderBundleTransaction.State.INSTALLED -> "已安装"
                LoaderBundleTransaction.State.UNKNOWN -> "未知版本"
            },
            ownershipWarning = runCatching { hasPayload() }.getOrDefault(false).let { ready ->
                if (!ready) null
                else owner.verify(listOf(path, loaderRoot, "$loaderRoot/logs", "$loaderRoot/configs", bootstrapPath, "$loaderRoot/sdk/CesiumLoader.SDK.dll"))
            },
        )
    }

    // ---------------------------------------------------------------- 安装

    /** 安装：备份原包 → 注入程序集 → 重封装容器 → 部署载荷 → 写回。返回安装后的状态。 */
    fun install(log: (String) -> Unit): Status {
        require(permissionGranted()) { "先给 Shizuku / Root 授权" }
        owner.reset()
        if (isChannel) checkNativeLoader()
        transaction.install(resolveBundle(), log)
        return status()
    }

    /** 还原：把备份的原包写回去。损坏的旧备份不会被当作原版；内置恢复资源也必须通过指纹校验。 */
    fun restore(log: (String) -> Unit): Status {
        require(permissionGranted()) { "先给 Shizuku / Root 授权" }
        transaction.restore(resolveBundle(), log)
        return status()
    }

    /** Keep all payload/configuration files while switching only the verified game entry point. */
    fun setEnabled(enabled: Boolean, log: (String) -> Unit): Status {
        require(permissionGranted()) { "先给 Shizuku / Root 授权" }
        owner.reset()
        val path = resolveBundle()
        if (enabled) {
            if (isChannel) checkNativeLoader()
            check(hasPayload()) { "请先安装内置加载器" }
            // Enabling must not deploy the bundled SDK over an online update.
            transaction.enable(path, log)
        } else {
            transaction.restore(path, log)
        }
        log(if (enabled) "已启用加载器；请重新启动游戏" else "已禁用加载器；模组、配置和预设全部保留")
        return status()
    }

    /** Remove payload and backups only after the game bundle is restored and verified. */
    fun uninstall(log: (String) -> Unit): Status {
        require(permissionGranted()) { "先给 Shizuku / Root 授权" }
        owner.reset()
        val path = resolveBundle()
        LoaderRemoval.remove(loaderFs, loaderRoot,
            restoreOriginal = {
                val restored = transaction.restore(path, log)
                check(bundleIo.hash(path) == restored) { "原包校验失败，未删除加载器文件" }
                for (other in validatedBundlePaths().filter { it != path }) {
                    if (transaction.classify(bundleIo.hash(other), other) == LoaderBundleTransaction.State.INSTALLED) {
                        transaction.restore(other, log)
                    }
                }
            },
            cleanupLocal = {
                check(workDir.deleteRecursively()) { "游戏加载器已删除，但安装缓存清理失败" }
                val updateCache = File(context.cacheDir, "updates")
                check(File(updateCache, "loader.zip").let { !it.exists() || it.delete() }) { "加载器下载包清理失败" }
                updateCache.listFiles()?.filter { it.name.startsWith("loader-stage-") }?.forEach {
                    check(it.deleteRecursively()) { "加载器临时文件清理失败" }
                }
            }, gamePackage = gamePackage)
        log("加载器、模组、配置、预设和安装备份已删除，游戏原包已恢复")
        return status()
    }

    // ---------------------------------------------------------------- 内部

    /** Update the in-game overlay without rewriting the game's resource bundle. */
    fun installOverlay(log: (String) -> Unit): Status {
        installOverlayAt(loaderRoot, log)
        return status()
    }

    private fun installOverlayAt(root: String, log: (String) -> Unit) {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        BuiltinOverlay(context, loaderFs).install(root, AppSettings(context).themeMode, dark, log) {
            OverlayPalette.tokens(context)
        }
    }

    private fun patchBundle(original: ByteArray, log: (String) -> Unit): ByteArray {
        val injected = context.assets.open(INJECTED_ASSET).use { it.readBytes() }
        val injectedSha = sha256(injected)
        require(injectedSha == INJECTED_SHA256) { "随包注入产物指纹不对：$injectedSha" }

        val container = UnityFs.parse(original)
        require(container.nodes.size == 1) { "缓存包里应当只有一个节点，实际 ${container.nodes.size}" }
        val node = container.nodes.first()
        val serialized = container.readNode(node)

        val slot = TextAssetPatch.findSlot(serialized, TextAssetPatch.readDataOffset(serialized), ASSEMBLY)
        val patchedSerialized = TextAssetPatch.replace(serialized, ASSEMBLY, injected)
        log("程序集替换完成：${injected.size} B（槽位容量 ${slot.capacity} B）")

        return UnityFsWriter.writeSingleNode(
            versionPlayer = container.versionPlayer,
            versionEngine = container.versionEngine,
            payload = patchedSerialized,
            originalHash = container.blocksInfoHash,
            nodePath = node.path,
            nodeFlags = node.flags,
            containerVersion = container.version,
        )
    }

    private fun deployPayload(log: (String) -> Unit) {
        ShizukuShell.exec(
            "mkdir -p " + listOf("mods", "configs", "logs", "data", "disabled_mods", "skins", "sdk", "bootstrap")
                .joinToString(" ") { ShizukuShell.quote("$loaderRoot/$it") },
        )
        // Repair old trees before deploying; later writes also verify each new inode.
        adoptOwnership(log)

        for ((asset, relative) in listOf(
            SDK_ASSET to "sdk/CesiumLoader.SDK.dll",
            BOOTSTRAP_ASSET to "bootstrap/CesiumLoader.Android.Bootstrap.dll",
        )) {
            pushAsset(asset, relative)
            log("$relative  ${File(workDir, relative).length()} B")
        }

        deployBuiltInMods(log)
        installOverlayAt(loaderRoot, log)
        writeInstalledIndex(log)
        adoptOwnership(log)
    }

    /**
     * 把整个加载器目录的属主交回游戏。
     *
     * 这一步不是「优化」而是**必需**：加载器目录是 `drwxr-s--- u0_a331 ext_data_rw`，
     * 组没有写权限，而游戏进程的 groups 里根本没有 1078，所以属主不是游戏的
     * 文件，游戏一律读不到。注入进 `AstralParty.Runtime.dll` 的 `<Module>.cctor`
     * 会去 `File.ReadAllBytes(bootstrap)`，读失败被 try/catch 吞掉 —— 结果就是
     * 「装好了、游戏能开、一个模组都不加载、日志也没有」。
     *
     * 顺带修掉旧版本（1.1.2/1.1.3）留下的 root 属主文件：它们是用
     * `临时文件 + mv -f` 写出来的，属主变成了执行者。
     */
    private fun adoptOwnership(log: (String) -> Unit) {
        owner.reset()
        owner.requireDirectories(loaderRoot)
        owner.requireOwned(loaderRoot, recursive = true)
        log(if (owner.isSupported()) "加载器目录属主已校验（${owner.ids()?.spec}）"
            else "Shizuku 目录访问已校验；文件写入逐项核对 SHA-256，游戏加载结果需重启后确认")
    }

    /**
     * 随包加载器的版本号。
     *
     * 由 `syncLoaderAssets` 从发行版 zip 的清单里取出来写到 `assets/cesium/loader.json`，
     * 所以换加载器版本只需要换 zip，不必改这里。读不到就退回当前基线版本。
     */
    private fun loaderVersion(): String {
        return try {
            val text = context.assets.open(LOADER_VERSION_ASSET).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() } ?: FALLBACK_LOADER_VERSION
        } catch (_: Exception) {
            FALLBACK_LOADER_VERSION
        }
    }

    /** 记下这次的装载状态：加载器版本、SDK 版本、内置模组清单，供模组页与 SDK 门槛检查读取。 */
    private fun writeInstalledIndex(log: (String) -> Unit) {
        val modIds = context.assets.list(MODS_ASSET_DIR).orEmpty()
            .filterNot { it == "LanguageUnlockMod" }
            .sorted()
        val version = loaderVersion()
        val payload = buildString {
            appendLine("{")
            appendLine("  \"layout\": 2,")
            appendLine("  \"loaderPackage\": \"com.hiynet.cesiumloader.core\",")
            appendLine("  \"sdkVersion\": \"$version\",")
            appendLine("  \"bootstrap\": \"bootstrap/CesiumLoader.Android.Bootstrap.dll\",")
            appendLine("  \"sdk\": \"sdk/CesiumLoader.SDK.dll\",")
            appendLine("  \"installedAt\": \"${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(java.util.Date())}\",")
            append("  \"builtInMods\": [")
            append(modIds.joinToString(", ") { "\"$it\"" })
            appendLine("]")
            appendLine("}")
        }
        loaderFs.writeText("$loaderRoot/installed.json", payload)
        log("installed.json 已写入（${modIds.size} 个内置模组）")
    }
    /**
     * 内置模组投放：位置由 [BuiltinModDeployer]（内部走 [com.hiynet.aspatoys.data.loader.ModStateStore]）决定，
     * 停用过的继续保持停用，绝不再往 `mods/` 新造一份副本。
     */
    private fun deployBuiltInMods(log: (String) -> Unit) {
        BuiltinModDeployer(
            fs = loaderFs,
            listAssets = { dir -> context.assets.list(dir).orEmpty().toList() },
            readAsset = { asset -> context.assets.open(asset).use { it.readBytes() } },
        ).deploy(loaderRoot, log = log)
    }

    private fun pushAsset(asset: String, relative: String) {
        val local = File(workDir, relative)
        local.parentFile?.mkdirs()
        context.assets.open(asset).use { input -> local.outputStream().use { input.copyTo(it) } }
        val remote = "$loaderRoot/$relative"
        loaderFs.makeDirectories(remote.substringBeforeLast('/'))
        loaderFs.copyIn(local, remote)
    }

    private fun hasPayload(): Boolean {
        return runCatching { bundleIo.read("$loaderRoot/sdk/CesiumLoader.SDK.dll").isNotEmpty() }.getOrDefault(false)
    }

    private fun resolveBundle(): String = validatedBundlePaths().first()

    private fun validatedBundlePaths(): List<String> {
        val base = File(AndroidPaths.bundleCacheDir(gamePackage), BUNDLE_KEY).invariantSeparatorsPath
        val valid = Regex(Regex.escape(base) + "/[a-f0-9]{32}/__data")
        val paths = candidatePaths().distinct()
        require(paths.isNotEmpty()) { "在设备上找不到游戏缓存包（先启动一次游戏）" }
        require(paths.all { valid.matches(it) }) { "发现异常缓存路径，未修改任何资源" }
        // Paths are ordered by mtime. Never select an older supported cache over a newer unknown one.
        return paths
    }

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
