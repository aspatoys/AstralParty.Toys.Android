package com.hiynet.aspatoys.data.updates

import com.hiynet.aspatoys.core.parseJsonObjectOrNull
import com.hiynet.aspatoys.core.string
import com.hiynet.aspatoys.data.loader.LoaderFs
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Imports only the managed SDK/mod payload from the same official ZIP used on Windows. */
class ManagedLoaderUpdate(private val fs: LoaderFs) {
    companion object {
        val protectedMods = setOf("CesiumOverlayMod", "SpeedHackMod", "FreeCameraMod")
        private val idPattern = Regex("[A-Za-z0-9_.-]{1,128}")
        fun safePath(path: String): String {
            val value = path.replace('\\', '/')
            require(value.isNotBlank() && !value.startsWith('/') && value.none { it == ':' || it.code < 32 } && value.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "加载器 ZIP 含不安全路径。" }
            return value
        }
    }
    data class Package(val version: String, val files: Map<String, File>, val mods: List<String>)
    fun prepare(zipFile: File, expectedVersion: String, staging: File): Package {
        staging.mkdirs()
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
            require(entries.size <= 10_000) { "加载器 ZIP 文件数过多。" }
            val names = hashSetOf<String>()
            var total = 0L
            for (entry in entries) {
                safePath(entry.name)
                require(names.add(entry.name.lowercase())) { "加载器 ZIP 有重名路径。" }
                require(entry.size in 0..(128L * 1024 * 1024)) { "加载器 ZIP 文件大小无效。" }
                total += entry.size; require(total <= 1024L * 1024 * 1024) { "加载器 ZIP 展开体积过大。" }
            }
            val manifestEntry = zip.getEntry("cesium-loader.json") ?: error("加载器 ZIP 缺少发行清单。")
            require(manifestEntry.size <= 2 * 1024 * 1024) { "发行清单过大。" }
            val manifest = parseJsonObjectOrNull(zip.getInputStream(manifestEntry).use { it.readBytes().toString(Charsets.UTF_8) }) ?: error("发行清单无效。")
            require(manifest.string("version") == expectedVersion) { "加载器 ZIP 与检查到的版本不一致。" }
            val hashes = manifest["files"] as? JsonObject ?: error("发行清单缺少文件校验。")
            val files = linkedMapOf<String, File>()
            val mods = linkedSetOf<String>()
            for (entry in entries) {
                if (!entry.name.startsWith("AstralParty_ModLoader/")) continue
                val relative = entry.name.removePrefix("AstralParty_ModLoader/")
                if (relative.endsWith(".pdb", true)) continue
                val pieces = relative.split('/')
                val isSdk = relative == "sdk/CesiumLoader.SDK.dll"
                val isLicense = relative in setOf("CesiumLoader-LICENSE.txt", "CesiumLoader-NOTICE.txt")
                val isMod = pieces.size >= 3 && pieces[0] == "mods" && idPattern.matches(pieces[1]) && protectedMods.none { it.equals(pieces[1], true) } && !pieces[1].equals("LanguageUnlockMod", true)
                if (!isSdk && !isLicense && !isMod) continue
                if (isMod && pieces.last().lowercase() in setOf("config.json", "mod.cfg")) continue
                val expected = (hashes[entry.name] as? JsonPrimitive)?.content?.lowercase() ?: error("清单未覆盖文件：${entry.name}")
                require(expected.matches(Regex("[0-9a-f]{64}"))) { "文件校验值无效。" }
                val target = File(staging, relative)
                target.parentFile!!.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var written = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; written += n; require(written <= entry.size); output.write(buffer, 0, n) }
                    require(written == entry.size) { "ZIP 文件不完整。" }
                } }
                require(GitHubUpdates.hash(target) == expected) { "加载器文件校验失败：$relative" }
                files[relative] = target
                if (isMod) mods += pieces[1]
            }
            require(files.containsKey("sdk/CesiumLoader.SDK.dll") && mods.isNotEmpty()) { "加载器包缺少托管 SDK 或模组。" }
            for (id in mods) require(files.containsKey("mods/$id/$id.dll")) { "模组 $id 缺少主体 DLL。" }
            return Package(expectedVersion, files, mods.toList())
        }
    }

    fun install(root: String, pkg: Package, progress: (String) -> Unit = {}) {
        require(pkg.files.keys.all { relative ->
            require(safePath(relative) == relative) { "部署路径必须使用标准分隔符。" }
            val parts = relative.split('/')
            relative == "sdk/CesiumLoader.SDK.dll" || relative in setOf("CesiumLoader-LICENSE.txt", "CesiumLoader-NOTICE.txt") ||
                (parts.size >= 3 && parts[0] == "mods" && idPattern.matches(parts[1]) && protectedMods.none { it.equals(parts[1], true) } &&
                    !parts[1].equals("LanguageUnlockMod", true) && parts.last().lowercase() !in setOf("config.json", "mod.cfg"))
        }) { "加载器包包含不允许部署的文件。" }
        check(fs.isAvailable()) { "请先连接 Shizuku / Root。" }
        check(fs.exists("$root/sdk/CesiumLoader.SDK.dll") || fs.exists("$root/CesiumLoader.SDK.dll")) { "请先安装 Android 加载器。" }
        val transaction = "$root/.toys-loader-update-${UUID.randomUUID()}"
        data class Step(val target: String, val backup: String, var backedUp: Boolean = false, var published: Boolean = false)
        val steps = mutableListOf<Step>()
        var rollbackOk = true
        val local = File.createTempFile("loader-index-", ".json")
        try {
            val planned = linkedMapOf<String, File>()
            for ((relative, file) in pkg.files) {
                var target = relative
                if (relative.startsWith("mods/")) {
                    val id = relative.split('/')[1]
                    val disabled = fs.exists("$root/disabled_mods/$id/$id.dll") || fs.exists("$root/disabled_mods/$id.dll")
                    val active = fs.exists("$root/mods/$id/$id.dll") || fs.exists("$root/mods/$id.dll")
                    check(!disabled || !active) { "$id 在启用和停用目录中同时存在，请先整理。" }
                    if (disabled) target = "disabled_mods/" + relative.removePrefix("mods/")
                }
                planned[target] = file
            }
            // Move legacy flat files out of the loader scan together with the transaction.
            val legacy = pkg.mods.flatMap { id -> listOf("mods/$id.dll", "mods/$id.json", "disabled_mods/$id.dll", "disabled_mods/$id.json") }
            val oldIndex = if (fs.exists("$root/installed.json")) fs.readText("$root/installed.json")?.let(::parseJsonObjectOrNull) ?: error("安装记录无法解析，已保留原文件。") else JsonObject(emptyMap())
            val index = oldIndex.toMutableMap()
            index["sdkVersion"] = JsonPrimitive(pkg.version)
            index["loaderVersion"] = JsonPrimitive(pkg.version)
            index["source"] = JsonPrimitive("https://github.com/${GitHubUpdates.LOADER_REPO}")
            index["updatedAt"] = JsonPrimitive(java.time.Instant.now().toString())
            local.writeText(JsonObject(index).toString())
            planned["installed.json"] = local // Publish version only after all SDK/mod files have committed.
            planned.forEach { (relative, file) ->
                val stage = "$transaction/stage/$relative"
                fs.makeDirectories(stage.substringBeforeLast('/')); fs.copyIn(file, stage)
                check(fs.fileSize(stage) == file.length()) { "更新文件写入不完整。" }
            }
            for (relative in legacy) {
                val target = "$root/$relative"
                if (fs.exists(target)) {
                    val step = Step(target, "$transaction/backup/$relative"); steps += step
                    fs.makeDirectories(step.backup.substringBeforeLast('/')); fs.move(target, step.backup); step.backedUp = true
                }
            }
            planned.keys.forEachIndexed { i, relative ->
                progress("正在更新加载器 ${i + 1} / ${planned.size}")
                val target = "$root/$relative"
                val step = Step(target, "$transaction/backup/$relative"); steps += step
                fs.makeDirectories(target.substringBeforeLast('/'))
                val exists = fs.exists(target); check(fs.lastError == null) { fs.lastError.orEmpty() }
                if (exists) { fs.makeDirectories(step.backup.substringBeforeLast('/')); fs.move(target, step.backup); step.backedUp = true }
                fs.move("$transaction/stage/$relative", target); step.published = true
            }
        } catch (error: Throwable) {
            for (step in steps.asReversed()) {
                runCatching { if (step.published) fs.delete(step.target); if (step.backedUp) fs.move(step.backup, step.target) }
                    .onFailure { rollbackOk = false; error.addSuppressed(it) }
            }
            if (!rollbackOk) throw IllegalStateException("更新失败且部分回滚失败，请保留备份：$transaction", error)
            throw error
        } finally {
            local.delete()
            if (rollbackOk) runCatching { fs.delete(transaction) }
        }
    }
}
