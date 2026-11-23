package com.hiynet.aspatoys.data.loader

import com.hiynet.aspatoys.data.mods.ModLayout
import com.hiynet.aspatoys.data.mods.ModPackageException
import com.hiynet.aspatoys.data.mods.ModSidecar
import java.io.File
import java.util.zip.ZipFile

/**
 * 把模组包装进加载器目录。
 *
 * 加载器根在游戏的私有目录里，写盘一律经 [LoaderFs]（Shizuku `shell` 身份）。
 * 打包格式与发行版一致：`<ModId>/<ModId>.dll` + `<ModId>/<ModId>.json`，允许附带资源文件
 * （含子目录）；也兼容平铺的 `<ModId>.dll`，以及包里多套一层 `mods/` 的写法。
 *
 * 启停用目录表达：`mods/` = 启用，`disabled_mods/` = 停用。判断状态一律看目录，
 * 不去改 sidecar 里的 `enabled`（那是打包期默认值，安卓引导不读它）。
 */
class ModInstaller(
    private val fs: LoaderFs,
    /**
     * 解包中转目录。
     *
     * 中转文件只由本进程读（字节经子进程标准输入推给 shell），所以这里要求的是「本应用能写」；
     * 之所以还建议放外部目录，是为了出问题时可从电脑上直接看到包内容。
     */
    private val stagingRoot: File = File(System.getProperty("java.io.tmpdir") ?: "."),
) {

    /** [disabled] 表示这次被写进了 `disabled_mods/`（包里的 sidecar 声明默认停用）。 */
    data class Result(
        val modId: String,
        val files: Int,
        val removed: Int,
        val disabled: Boolean = false,
    )

    /** 启停结果；[changed] 为 false 表示本来就在目标状态（重复点击不会报错）。 */
    data class ToggleResult(
        val modId: String,
        val enabled: Boolean,
        val changed: Boolean,
    )

    /**
     * 解包并投放。先按上一次的清单清掉旧文件，再写新文件，最后更新清单，
     * 这样模组升级时不会残留已经被删掉的资源。
     */
    fun install(zip: File, layout: ModLayout, log: (String) -> Unit): Result {
        ZipFile(zip).use { archive ->
            val entries = archive.entries().asSequence().filter { !it.isDirectory }.toList()
            val names = entries.associateWith { it.name.replace('\\', '/') }

            // 选主 DLL：平铺包只有一个；一目录的包里可能有多个 DLL（主 DLL 最大）。
            val dllEntry = entries
                .filter { names.getValue(it).endsWith(".dll", ignoreCase = true) }
                .maxByOrNull { it.size }
                ?: throw ModPackageException("压缩包里没有 .dll，不是模组包。")

            val dllName = names.getValue(dllEntry)
            val segments = dllName.split('/')
            val modId = segments[segments.size - 1].removeSuffix(".dll").removeSuffix(".DLL")
            if (modId.isBlank()) throw ModPackageException("无法从 $dllName 推断模组 ID。")

            // 模组目录在包内的前缀；平铺包为空。
            val prefix = segments.dropLast(1).joinToString("/")
            log("模组：$modId（${if (prefix.isEmpty()) "平铺" else prefix}，DLL ${dllEntry.size} B）")

            // 统一走状态整理：原地升级沿用原目录（停用的保持停用），重复副本/空目录/旧平铺文件一并收拾，
            // 只留一份能被扫描的主体，避免「重装又造一份副本」。
            val placement = stateStore.place(
                layout = layout,
                modId = modId,
                folderName = modId,
                defaultDisabled = resolveSidecarDisabled(modId, archive, entries, names, prefix, log),
                migrateFlatToNested = true,
                log = log,
            )
            val disabled = placement.disabled
            val modRoot = placement.modDir
            fs.makeDirectories(modRoot)

            val written = mutableSetOf<String>()
            val staging = File(stagingRoot, "cesium-mod-$modId").apply {
                deleteRecursively()
                mkdirs()
            }
            if (!staging.isDirectory) {
                throw ModPackageException("无法创建中转目录 ${staging.absolutePath}，安装中止。")
            }
            var files = 0
            try {
                for (zipEntry in entries) {
                    val name = names.getValue(zipEntry)
                    if (prefix.isNotEmpty() && !name.startsWith("$prefix/")) continue
                    val relative = if (prefix.isEmpty()) name else name.removePrefix("$prefix/")
                    if (relative.isBlank()) continue
                    if (relative.endsWith("/")) continue
                    if (relative.endsWith(".pdb", ignoreCase = true)) continue
                    if (!isSafeRelative(relative)) {
                        log("跳过可疑条目：$relative")
                        continue
                    }

                    if (relative.substringAfterLast('/').lowercase() in setOf("config.json", "mod.cfg") && fs.exists("$modRoot/$relative")) {
                        written += relative
                        continue
                    }

                    val local = File(staging, relative.replace('/', File.separatorChar))
                    local.parentFile?.mkdirs()
                    archive.getInputStream(zipEntry).use { input ->
                        local.outputStream().use { output -> input.copyTo(output) }
                    }

                    val remote = "$modRoot/$relative"
                    val parent = remote.substringBeforeLast('/')
                    if (parent.length > placement.rootPath.length) fs.makeDirectories(parent)
                    fs.copyIn(local, remote)
                    written += relative
                    files++
                }

                val removed = pruneStale(layout, modId, written, log)
                writeManifest(layout, modId, disabled, written)
                log("写入 $files 个文件到 ${if (disabled) "disabled_mods" else "mods"}/$modId，清理 $removed 个旧文件")
                return Result(modId, files, removed, disabled)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    /**
     * 启用/停用：在两个目录之间搬整个模组目录（平铺旧布局则搬 DLL 与 sidecar）。
     *
     * 搬完会回读一次目录，确认真的落到了目标位置 —— `mv` 的退出码为 0 不代表结果正确
     * （比如目标已存在时 `mv` 会把目录塞进去），所以状态以回读为准。
     *
     * 重复副本、空目录、旧平铺 DLL 的整理与备份全部交给 [ModStateStore]。
     *
     * @throws ModPackageException 找不到模组、目录名非法、或搬移后回读不一致。
     */
    fun setEnabled(layout: ModLayout, modId: String, folderName: String?, enabled: Boolean, log: (String) -> Unit): ToggleResult =
        stateStore.setEnabled(layout, modId, folderName, enabled, log)

    /** 启停/安置的统一实现：整理重复目录、空目录、旧平铺文件，只留一份可扫描主体。 */
    private val stateStore = ModStateStore(fs)

    /**
     * 删除一个已安装模组：目录布局删整个 `mods|disabled_mods/<folder>/`，
     * 平铺旧布局删 `<modId>.dll` 和同名 sidecar。
     *
     * 只删这个模组主体（连同它目录里的 `config.json`），不动别的模组。
     */
    fun delete(layout: ModLayout, modId: String, folderName: String?, disabled: Boolean, log: (String) -> Unit): Boolean {
        val root = if (disabled) layout.disabledModsRoot.absolutePath else layout.modsRoot.absolutePath
        val folder = folderName ?: modId
        val nested = "$root/$folder"
        val flatDll = "$root/$modId.dll"
        val flatJson = "$root/$modId.json"

        val hasNested = fs.exists(nested)
        val hasFlat = fs.exists(flatDll)
        if (!hasNested && !hasFlat) {
            throw ModPackageException("找不到 $modId：${if (disabled) "disabled_mods" else "mods"}/ 里没有它。")
        }

        if (hasNested) {
            fs.delete(nested)
            if (fs.exists(nested)) {
                throw ModPackageException("删除失败：$nested 仍然存在（加载器目录可能被占用或不可写）。")
            }
            log("已删除目录 $nested")
        }
        if (hasFlat) {
            fs.delete(flatDll)
            if (fs.exists(flatJson)) fs.delete(flatJson)
            if (fs.exists(flatDll)) {
                throw ModPackageException("删除失败：$flatDll 仍然存在。")
            }
            log("已删除文件 $flatDll")
        }

        val manifest = "${layout.manifestRoot.absolutePath}/packages/$modId.json"
        runCatching { if (fs.exists(manifest)) fs.delete(manifest) }
        return true
    }
    /** 读玩家配置：模组目录里的 `config.json`（停用模组读它当前所在的 `disabled_mods`）。 */
    fun readConfig(layout: ModLayout, modId: String, disabled: Boolean = false): String? =
        fs.readText(layout.modConfigFile(modId, disabled).absolutePath)

    fun writeConfig(layout: ModLayout, modId: String, text: String, disabled: Boolean = false) {
        val target = layout.modConfigFile(modId, disabled)
        fs.makeDirectories(target.parentFile?.absolutePath ?: layout.modsRoot.absolutePath)
        fs.writeText(target.absolutePath, text)
    }

    /**
     * 新装的模组看包里 sidecar 的 `enabled`：显式 false 就作为默认位置落进 `disabled_mods`。
     *
     * 已经装过的沿用原位置不在这里判断 —— 那是 [ModStateStore.place] 的职责（升级不会把停用的
     * 模组悄悄重新启用，双副本时优先保留停用那份）。
     */
    private fun resolveSidecarDisabled(
        modId: String,
        archive: ZipFile,
        entries: List<java.util.zip.ZipEntry>,
        names: Map<java.util.zip.ZipEntry, String>,
        prefix: String,
        log: (String) -> Unit,
    ): Boolean {
        val sidecarName = if (prefix.isEmpty()) "$modId.json" else "$prefix/$modId.json"
        val sidecarEntry = entries.firstOrNull { names.getValue(it) == sidecarName } ?: return false
        return try {
            val text = archive.getInputStream(sidecarEntry).use { it.readBytes().toString(Charsets.UTF_8) }
            val parsed = ModSidecar.parse(text) ?: return false
            if (parsed.enabled == false) {
                log("$modId 的 sidecar 声明默认停用，安装时作为默认位置")
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
    private fun pruneStale(layout: ModLayout, modId: String, keep: Set<String>, log: (String) -> Unit): Int {
        val manifest = fs.readText("${layout.manifestRoot.absolutePath}/packages/$modId.json") ?: return 0
        val previous = Regex("\"([^\"]+)\"").findAll(manifest)
            .map { it.groupValues[1] }
            .filter { it != modId && it != "modId" && it != "root" && it != "files" }
            .toSet()
        if (previous.isEmpty()) return 0

        // 旧文件可能在任一目录（切换过启停），两边都清。
        val roots = listOf(layout.modsRoot.absolutePath, layout.disabledModsRoot.absolutePath)
        var removed = 0
        for (name in previous - keep) {
            if (!isSafeRelative(name)) continue
            for (root in roots) {
                val path = "$root/$modId/$name"
                if (!fs.exists(path)) continue
                fs.delete(path)
                removed++
            }
        }
        if (removed > 0) log("清掉 $removed 个旧文件")
        return removed
    }

    private fun writeManifest(layout: ModLayout, modId: String, disabled: Boolean, files: Set<String>) {
        val dir = "${layout.manifestRoot.absolutePath}/packages"
        fs.makeDirectories(dir)
        val payload = buildString {
            appendLine("{")
            appendLine("  \"modId\": \"$modId\",")
            appendLine("  \"root\": \"${if (disabled) "disabled_mods" else "mods"}\",")
            appendLine("  \"files\": [")
            append(files.sorted().joinToString(",\n") { "    " + quote(it) })
            appendLine()
            appendLine("  ]")
            appendLine("}")
        }
        fs.writeText("$dir/$modId.json", payload)
    }

    /** 只接受包内的相对路径，挡掉绝对路径与 `..` 之类的越界写法。 */
    private fun isSafeRelative(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.startsWith("\\")) return false
        if (path.contains(':') && path.indexOf(':') <= 1) return false
        return path.split('/').none { it == ".." || it.isEmpty() }
    }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
