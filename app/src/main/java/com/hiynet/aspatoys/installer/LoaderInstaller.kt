package com.hiynet.aspatoys.installer

import android.content.Context
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
 * [ORIGINAL_SHA256] 才允许安装，换版本必须重新生成。
 */
class LoaderInstaller(private val context: Context) {

    companion object {
        const val GAME_PACKAGE = "com.feimo.astralparty"
        const val BUNDLE_KEY = "4da1f23c112208b0f8df2a1327b4e4ce"
        const val ASSEMBLY = "AstralParty.Runtime.dll"

        /** 当前游戏热更版本的原包指纹（未注入过）。 */
        const val ORIGINAL_SHA256 = "77da946bf7d331351a93ff16707aab426f534b7577a771f21267478b7bc70c87"

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
    )

    private val workDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "loader-installer").apply { mkdirs() }

    private val backupFile: File get() = File(workDir, "__data.orig")

    // ---------------------------------------------------------------- 状态

    fun status(): Status {
        val rootSelected = ShizukuShell.selectedMode() == com.hiynet.aspatoys.data.settings.AppSettings.PrivilegeRoot
        if (!ShizukuShell.isServiceAlive()) {
            return Status(
                note = if (rootSelected) {
                    "Root 不可用：${RootShell.lastError ?: "没有找到 su"}"
                } else {
                    "Shizuku 没有运行：先启动 Shizuku 服务"
                },
            )
        }
        if (!ShizukuShell.hasPermission()) {
            return Status(
                shizukuAlive = true,
                note = if (rootSelected) "Root 不可用：${RootShell.lastError ?: "没有拿到 su 权限"}" else "还没授权 Shizuku",
            )
        }

        val path = runCatching { resolveBundle() }.getOrNull()
            ?: return Status(shizukuAlive = true, shizukuGranted = true, note = "找不到游戏缓存包：先启动一次游戏")
        val sha = runCatching { sha256Remote(path) }.getOrNull()

        return Status(
            shizukuAlive = true,
            shizukuGranted = true,
            bundlePath = path,
            bundleSha256 = sha,
            injected = sha != null && sha != ORIGINAL_SHA256,
            supportedVersion = sha == ORIGINAL_SHA256,
            hasBackup = backupFile.isFile,
            loaderPayload = runCatching { hasPayload() }.getOrDefault(false),
            note = when {
                sha == null -> "读不到缓存包指纹"
                sha == ORIGINAL_SHA256 -> "原版缓存包，可以安装"
                else -> "缓存包已不是原版（可能已装过或游戏更新），可先还原"
            },
        )
    }

    // ---------------------------------------------------------------- 安装

    /** 安装：备份原包 → 注入程序集 → 重封装容器 → 部署载荷 → 写回。返回安装后的状态。 */
    fun install(log: (String) -> Unit): Status {
        require(ShizukuShell.hasPermission()) { "先给 Shizuku 授权" }
        val remote = resolveBundle()

        log("缓存包：$remote")
        log("拉取原包")
        val original = File(workDir, "__data.orig")
        ShizukuShell.exec("cat " + ShizukuShell.quote(remote) + " > " + ShizukuShell.quote(original.absolutePath))

        val originalSha = sha256(original)
        log("原包 ${original.length()} B  sha256=$originalSha")
        require(originalSha == ORIGINAL_SHA256) {
            "缓存包版本不支持（期望 $ORIGINAL_SHA256，实际 $originalSha）：游戏更新过就必须重新生成注入产物"
        }

        log("注入程序集 ${ASSEMBLY}")
        val patched = patchBundle(original.readBytes(), log)

        val patchedFile = File(workDir, "__data.patched")
        patchedFile.writeBytes(patched)
        val patchedSha = sha256(patchedFile)
        log("新包 ${patched.size} B  sha256=$patchedSha")

        log("部署加载器载荷")
        deployPayload(log)

        log("停掉游戏进程")
        runCatching { ShizukuShell.exec("am force-stop $GAME_PACKAGE") }

        log("写回缓存包")
        ShizukuShell.exec("cat " + ShizukuShell.quote(patchedFile.absolutePath) + " > " + ShizukuShell.quote(remote))

        val remoteSha = sha256Remote(remote)
        require(remoteSha == patchedSha) { "写回校验失败：设备上是 $remoteSha" }
        log("写回校验通过")
        return status()
    }

    /** 还原：把备份的原包写回去。 */
    fun restore(log: (String) -> Unit): Status {
        require(ShizukuShell.hasPermission()) { "先给 Shizuku 授权" }
        require(backupFile.isFile) { "没有备份，无法还原" }
        val backupSha = sha256(backupFile)
        require(backupSha == ORIGINAL_SHA256) { "备份文件指纹不对（$backupSha），拒绝写回" }

        val remote = resolveBundle()
        log("停掉游戏进程")
        runCatching { ShizukuShell.exec("am force-stop $GAME_PACKAGE") }
        log("写回原包 ${backupFile.length()} B")
        ShizukuShell.exec("cat " + ShizukuShell.quote(backupFile.absolutePath) + " > " + ShizukuShell.quote(remote))

        val remoteSha = sha256Remote(remote)
        require(remoteSha == ORIGINAL_SHA256) { "还原校验失败：设备上是 $remoteSha" }
        log("还原校验通过")
        return status()
    }

    // ---------------------------------------------------------------- 内部

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
        writeInstalledIndex(log)
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
        val local = File(workDir, "installed.json")
        local.writeText(payload, Charsets.UTF_8)
        ShizukuShell.exec("cat " + ShizukuShell.quote(local.absolutePath) + " > " + ShizukuShell.quote("$LOADER_ROOT/installed.json"))
        log("installed.json 已写入（${modIds.size} 个内置模组）")
    }

    /** 发行版的内置模组：`cesium/mods/<ModId>/<ModId>.dll` + sidecar，按一模组一目录投放。 */
    private fun deployBuiltInMods(log: (String) -> Unit) {
        val modIds = context.assets.list(MODS_ASSET_DIR).orEmpty()
            .filterNot { it == "LanguageUnlockMod" }
            .sorted()
        var files = 0
        for (modId in modIds) {
            val names = context.assets.list("$MODS_ASSET_DIR/$modId").orEmpty().sorted()
            if (names.isEmpty()) continue
            ShizukuShell.exec("mkdir -p " + ShizukuShell.quote("$LOADER_ROOT/mods/$modId"))
            for (name in names) {
                pushAsset("$MODS_ASSET_DIR/$modId/$name", "mods/$modId/$name")
                files++
            }
        }
        log("内置模组 ${modIds.size} 个 / $files 个文件")
    }

    private fun pushAsset(asset: String, relative: String) {
        val local = File(workDir, relative)
        local.parentFile?.mkdirs()
        context.assets.open(asset).use { input -> local.outputStream().use { input.copyTo(it) } }
        ShizukuShell.exec(
            "cat " + ShizukuShell.quote(local.absolutePath) + " > " + ShizukuShell.quote("$LOADER_ROOT/$relative"),
        )
    }

    private fun hasPayload(): Boolean {
        val out = ShizukuShell.exec("ls " + ShizukuShell.quote("$LOADER_ROOT/sdk/CesiumLoader.SDK.dll") + " 2>/dev/null || true")
        return out.isNotBlank()
    }

    private fun resolveBundle(): String {
        val pattern = "/sdcard/Android/data/$GAME_PACKAGE/files/com.unity.addressables/AssetBundles/$BUNDLE_KEY/*/__data"
        val out = ShizukuShell.exec("ls $pattern 2>/dev/null | head -n 1")
        val path = out.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        require(!path.isNullOrEmpty()) { "在设备上找不到游戏的缓存包（先启动一次游戏）" }
        return path
    }

    private fun sha256Remote(path: String): String {
        val out = ShizukuShell.exec("sha256sum " + ShizukuShell.quote(path))
        return out.trim().substringBefore(' ').lowercase()
    }

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
