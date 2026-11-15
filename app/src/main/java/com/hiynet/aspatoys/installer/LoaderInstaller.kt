package com.hiynet.aspatoys.installer

import android.content.Context
import android.content.res.Configuration
import com.hiynet.aspatoys.data.loader.BuiltinOverlay
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.data.loader.ShizukuLoaderFs
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
 * 注入产物的来源：`assets/cesium/AstralParty.Runtime.injected.dll`。往程序集里写 IL 需要
 * Mono.Cecil，手机上不做，所以这一份随包带、并且**锁死游戏版本**：只有原包 SHA-256 等于
 * [ORIGINAL_SHA256] 才执行注入；[PATCHED_SHA256] 是同一版本的已安装产物，只更新载荷。
 * 其它游戏版本必须重新生成适配；还原优先验证本地备份，损坏时使用随包的已校验原包。
 */
class LoaderInstaller(
    private val context: Context,
    private val bundleIo: BundleIo = ShizukuBundleIo(File(context.getExternalFilesDir(null) ?: context.filesDir, "loader-installer/transport")),
    private val originalSource: () -> ByteArray = { context.assets.open(ORIGINAL_ASSET).use { it.readBytes() } },
    private val candidatePaths: () -> List<String> = { discoverBundlePaths() },
    private val serviceAvailable: () -> Boolean = { ShizukuShell.isServiceAlive() },
    private val permissionGranted: () -> Boolean = { ShizukuShell.hasPermission() },
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
        const val FALLBACK_LOADER_VERSION = "3.8.0"

        val LOADER_ROOT = "/sdcard/Android/data/$GAME_PACKAGE/files/CesiumLoader"

        /** 与 [com.hiynet.cesiumloader.core.CesiumLayout] 保持一致：SDK 在 sdk/，引导在 bootstrap/。 */
        val BOOTSTRAP_REMOTE = "$LOADER_ROOT/bootstrap/CesiumLoader.Android.Bootstrap.dll"

        private fun discoverBundlePaths(): List<String> {
            val pattern = "/sdcard/Android/data/$GAME_PACKAGE/files/com.unity.addressables/AssetBundles/$BUNDLE_KEY/*/__data"
            // Unity leaves old version folders behind; prefer the newest cache instead of lexical order.
            return ShizukuShell.exec("ls -t $pattern 2>/dev/null; true")
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
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

    private val workDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "loader-installer").apply { mkdirs() }

    private val originalPayload by lazy { originalSource() }
    private val transaction by lazy {
        LoaderBundleTransaction(workDir, bundleIo, { originalPayload }, ::patchBundle, ::deployPayload,
            ORIGINAL_SHA256, PATCHED_SHA256)
    }

    // ---------------------------------------------------------------- 状态

    fun status(): Status {
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
        val state = transaction.classify(sha)

        return Status(
            shizukuAlive = true,
            shizukuGranted = true,
            bundlePath = path,
            bundleSha256 = sha,
            injected = state == LoaderBundleTransaction.State.INSTALLED,
            supportedVersion = state != LoaderBundleTransaction.State.UNKNOWN,
            hasBackup = transaction.backupAvailable(sha),
            loaderPayload = runCatching { hasPayload() }.getOrDefault(false),
            note = when {
                sha == null -> "读不到缓存包指纹"
                sha == ORIGINAL_SHA256 -> "原版缓存包，可以安装"
                sha == PATCHED_SHA256 -> "已识别本版本加载器，可重复更新或校验后还原"
                else -> "未知缓存版本，未判定为已注入；请勿使用旧原包覆盖更新后的游戏"
            },
            cacheState = when (state) {
                LoaderBundleTransaction.State.ORIGINAL -> "原版"
                LoaderBundleTransaction.State.INSTALLED -> "已安装"
                LoaderBundleTransaction.State.UNKNOWN -> "未知版本"
            },
            ownershipWarning = runCatching { hasPayload() }.getOrDefault(false).let { ready ->
                if (!ready) null
                else LoaderOwnership.default.verify(listOf(BOOTSTRAP_REMOTE, "$LOADER_ROOT/sdk/CesiumLoader.SDK.dll"))
            },
        )
    }

    // ---------------------------------------------------------------- 安装

    /** 安装：备份原包 → 注入程序集 → 重封装容器 → 部署载荷 → 写回。返回安装后的状态。 */
    fun install(log: (String) -> Unit): Status {
        require(permissionGranted()) { "先给 Shizuku / Root 授权" }
        transaction.install(resolveBundle(), log)
        return status()
    }

    /** 还原：把备份的原包写回去。损坏的旧备份不会被当作原版；内置恢复资源也必须通过指纹校验。 */
    fun restore(log: (String) -> Unit): Status {
        require(permissionGranted()) { "先给 Shizuku / Root 授权" }
        transaction.restore(resolveBundle(), log)
        return status()
    }

    // ---------------------------------------------------------------- 内部

    /** Update the in-game overlay without rewriting the game's resource bundle. */
    fun installOverlay(log: (String) -> Unit): Status {
        val settings = AppSettings(context)
        installOverlayAt(settings.loaderRootPath, log)
        return status()
    }

    private fun installOverlayAt(root: String, log: (String) -> Unit) {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        BuiltinOverlay(context).install(root, AppSettings(context).themeMode, dark, log)
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
                .joinToString(" ") { ShizukuShell.quote("$LOADER_ROOT/$it") },
        )

        for ((asset, relative) in listOf(
            SDK_ASSET to "sdk/CesiumLoader.SDK.dll",
            BOOTSTRAP_ASSET to "bootstrap/CesiumLoader.Android.Bootstrap.dll",
        )) {
            pushAsset(asset, relative)
            log("$relative  ${File(workDir, relative).length()} B")
        }

        deployBuiltInMods(log)
        installOverlayAt(LOADER_ROOT, log)
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
        val owner = LoaderOwnership.default
        if (!owner.isSupported()) {
            log(
                "提示：当前提权通道 uid=${owner.channelUid() ?: "?"}，不能修改文件属主；" +
                    "若加载器没生效，请用 root 或以 root 启动的 Shizuku",
            )
            return
        }
        owner.reset()
        val adopted = owner.adoptTree(LOADER_ROOT)
        if (!adopted) {
            log("警告：加载器目录属主修正失败（${owner.lastError}）")
            return
        }
        val problem = owner.verify(listOf(BOOTSTRAP_REMOTE, "$LOADER_ROOT/sdk/CesiumLoader.SDK.dll"))
        log(problem?.let { "警告：$it" } ?: "加载器目录属主已交给游戏（${owner.ids()?.spec}）")
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
        ShizukuLoaderFs().writeText("$LOADER_ROOT/installed.json", payload)
        log("installed.json 已写入（${modIds.size} 个内置模组）")
    }

    /** 发行版的内置模组：按一模组一目录投放，允许 `data/` 等子目录。 */
    private fun deployBuiltInMods(log: (String) -> Unit) {
        val modIds = context.assets.list(MODS_ASSET_DIR).orEmpty()
            .filterNot { it == "LanguageUnlockMod" }
            .filterNot { it == BuiltinOverlay.ID }
            .filterNot { it == BuiltinOverlay.SPEED_ID }
            .filterNot { it == BuiltinOverlay.CAMERA_ID }
            .sorted()
        var files = 0

        fun deployDir(assetDir: String, relativeDir: String) {
            for (name in context.assets.list(assetDir).orEmpty().sorted()) {
                val asset = "$assetDir/$name"
                val relative = "$relativeDir/$name"
                if (context.assets.list(asset).orEmpty().isNotEmpty()) {
                    deployDir(asset, relative)
                } else {
                    pushAsset(asset, relative)
                    files++
                }
            }
        }

        for (modId in modIds) {
            ShizukuShell.exec("mkdir -p " + ShizukuShell.quote("$LOADER_ROOT/mods/$modId"))
            val names = context.assets.list("$MODS_ASSET_DIR/$modId").orEmpty()
            if (names.isEmpty()) continue
            deployDir("$MODS_ASSET_DIR/$modId", "mods/$modId")
        }
        log("内置模组 ${modIds.size} 个 / $files 个文件")
    }

    private fun pushAsset(asset: String, relative: String) {
        val local = File(workDir, relative)
        local.parentFile?.mkdirs()
        context.assets.open(asset).use { input -> local.outputStream().use { input.copyTo(it) } }
        val fs = ShizukuLoaderFs()
        val remote = "$LOADER_ROOT/$relative"
        fs.makeDirectories(remote.substringBeforeLast('/'))
        fs.copyIn(local, remote)
    }

    private fun hasPayload(): Boolean {
        return runCatching { bundleIo.read("$LOADER_ROOT/sdk/CesiumLoader.SDK.dll").isNotEmpty() }.getOrDefault(false)
    }

    private fun resolveBundle(): String {
        val valid = Regex("/sdcard/Android/data/" + Regex.escape(GAME_PACKAGE) +
            "/files/com\\.unity\\.addressables/AssetBundles/" + BUNDLE_KEY + "/[a-f0-9]{32}/__data")
        val paths = candidatePaths().distinct()
        require(paths.isNotEmpty()) { "在设备上找不到游戏缓存包（先启动一次游戏）" }
        require(paths.all { valid.matches(it) }) { "发现异常缓存路径，未修改任何资源" }
        // Paths are ordered by mtime. Never select an older supported cache over a newer unknown one.
        return paths.first()
    }

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
