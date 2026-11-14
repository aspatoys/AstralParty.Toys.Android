package com.hiynet.aspatoys.data.loader

import com.hiynet.aspatoys.data.mods.ModLayout
import java.io.File
import java.util.zip.ZipFile

/**
 * 把模组包装进加载器目录。
 *
 * 加载器根在游戏的私有目录里，写盘一律经 [LoaderFs]（Shizuku `shell` 身份）。
 * 打包格式与发行版一致：`<ModId>/<ModId>.dll` + `<ModId>/<ModId>.json`，允许附带资源文件；
 * 也兼容平铺的 `<ModId>.dll`。
 */
class ModInstaller(private val fs: LoaderFs) {

    data class Result(
        val modId: String,
        val files: Int,
        val removed: Int,
    )

    /**
     * 解包并投放。会先按上一次的清单清掉旧文件，再写新文件，最后更新清单，
     * 这样模组升级时不会残留已经被删掉的资源。
     */
    fun install(zip: File, layout: ModLayout, log: (String) -> Unit): Result {
        ZipFile(zip).use { archive ->
            // 先确定包内根目录：有 <ModId>/<ModId>.dll 就用它，否则按平铺处理。
            val dllEntry = archive.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".dll", ignoreCase = true) }
                .maxByOrNull { it.size }
                ?: error("压缩包里没有 .dll")

            val normalized = dllEntry.name.replace('\\', '/')
            val parts = normalized.split('/')
            val nested = parts.size >= 2
            val modId = (if (nested) parts[parts.size - 2] else parts.last().removeSuffix(".dll"))
            val prefix = if (nested) normalized.substringBeforeLast('/') else ""

            log("模组：$modId（${if (nested) "一目录" else "平铺"}，DLL ${dllEntry.size} B）")

            val modRoot = "${layout.modsRoot.absolutePath}/$modId"
            fs.makeDirectories(modRoot)

            val written = mutableSetOf<String>()
            val staging = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", "cesium-mod-$modId").apply {
                deleteRecursively()
                mkdirs()
            }
            var files = 0
            try {
                for (zipEntry in archive.entries()) {
                    if (zipEntry.isDirectory) continue
                    val name = zipEntry.name.replace('\\', '/')
                    if (prefix.isNotEmpty() && !name.startsWith("$prefix/")) continue
                    val relative = if (prefix.isEmpty()) name else name.removePrefix("$prefix/")
                    if (relative.contains('/')) continue // 深入子目录的资源暂不投放
                    if (relative.endsWith(".pdb", ignoreCase = true)) continue

                    val local = File(staging, relative)
                    val input = archive.getInputStream(zipEntry)
                    try {
                        local.outputStream().use { output -> input.copyTo(output) }
                    } finally {
                        input.close()
                    }
                    fs.copyIn(local, "$modRoot/$relative")
                    written += relative
                    files++
                }

                val removed = pruneStale(layout, modId, written, log)
                writeManifest(layout, modId, written)
                log("写入 $files 个文件到 mods/$modId，清理 $removed 个旧文件")
                return Result(modId, files, removed)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    /** 启用/停用：在两个目录之间搬整个模组目录。 */
    fun setEnabled(layout: ModLayout, modId: String, enabled: Boolean, log: (String) -> Unit) {
        val from = if (enabled) "${layout.disabledModsRoot.absolutePath}/$modId" else "${layout.modsRoot.absolutePath}/$modId"
        val to = if (enabled) "${layout.modsRoot.absolutePath}/$modId" else "${layout.disabledModsRoot.absolutePath}/$modId"
        if (!fs.exists(from)) {
            log("找不到 mods/$modId，无法切换状态")
            return
        }
        fs.makeDirectories(to.substringBeforeLast('/'))
        fs.move(from, to)
        log(if (enabled) "$modId 已启用" else "$modId 已停用")
    }

    /** 读/写玩家配置：`configs/<ModId>.json`。 */
    fun readConfig(layout: ModLayout, modId: String): String? =
        fs.readText("${layout.configsRoot.absolutePath}/$modId.json")

    fun writeConfig(layout: ModLayout, modId: String, text: String) {
        fs.makeDirectories(layout.configsRoot.absolutePath)
        fs.writeText("${layout.configsRoot.absolutePath}/$modId.json", text)
    }

    private fun pruneStale(layout: ModLayout, modId: String, keep: Set<String>, log: (String) -> Unit): Int {
        val manifest = fs.readText("${layout.manifestRoot.absolutePath}/packages/$modId.json") ?: return 0
        val previous = Regex("\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        var removed = 0
        for (name in previous - keep) {
            if (!name.contains('/') || name.startsWith("files")) continue
            val path = "${layout.modsRoot.absolutePath}/$modId/$name"
            if (!fs.exists(path)) continue
            fs.delete(path)
            removed++
        }
        if (removed > 0) log("清掉 $removed 个旧文件")
        return removed
    }

    private fun writeManifest(layout: ModLayout, modId: String, files: Set<String>) {
        val dir = "${layout.manifestRoot.absolutePath}/packages"
        fs.makeDirectories(dir)
        val payload = buildString {
            appendLine("{")
            appendLine("  \"modId\": \"$modId\",")
            appendLine("  \"files\": [")
            append(files.sorted().joinToString(",\n") { "    \"$it\"" })
            appendLine()
            appendLine("  ]")
            appendLine("}")
        }
        fs.writeText("$dir/$modId.json", payload)
    }
}
