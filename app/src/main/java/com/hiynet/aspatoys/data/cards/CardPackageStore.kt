package com.hiynet.aspatoys.data.cards

import com.hiynet.aspatoys.core.parseJsonObjectOrNull
import com.hiynet.aspatoys.core.string
import java.io.File
import java.nio.charset.Charset
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Desktop-compatible card profiles. Import staging is never visible as a usable profile. */
class CardPackageStore(val root: File, private val validateImage: (File) -> Boolean = { basicImage(it) }) {
    val profilesRoot = File(root, "profiles")
    private val imageExtensions = setOf("png", "jpg", "jpeg", "bmp")

    data class Profile(val id: String, val name: String, val author: String = "", val description: String = "", val cards: Int = 0)
    data class Card(val key: String, val file: File?, val animated: Boolean = false)

    fun directory(id: String): File {
        require(validId(id)) { "方案名称无效。" }
        return File(profilesRoot, id)
    }

    fun profiles(): List<Profile> = profilesRoot.listFiles().orEmpty().filter { it.isDirectory && validId(it.name) }
        .map { profile(it) }.sortedBy { it.name.lowercase(Locale.ROOT) }

    fun cards(id: String): List<Card> {
        val directory = directory(id)
        val files = directory.listFiles().orEmpty().filter { it.isFile }
        val images = files.filter { it.extension.lowercase(Locale.ROOT) in imageExtensions }
        val animations = files.filter { it.extension.equals("cardanim", true) }.associateBy { it.nameWithoutExtension.lowercase(Locale.ROOT) }
        val result = images.map { image -> Card(image.nameWithoutExtension, image, image.nameWithoutExtension.lowercase(Locale.ROOT) in animations) }.toMutableList()
        val existing = result.map { it.key.lowercase(Locale.ROOT) }.toSet()
        animations.values.filter { it.nameWithoutExtension.lowercase(Locale.ROOT) !in existing }.forEach {
            result += Card(it.nameWithoutExtension, File(directory, "${it.nameWithoutExtension}.frames/000000.png").takeIf(File::isFile), true)
        }
        return result.sortedBy { it.key.lowercase(Locale.ROOT) }
    }

    fun create(name: String): Profile {
        require(name.trim().isNotEmpty() && name.trim().length <= 100) { "方案名称需要 1–100 个字符。" }
        val id = uniqueId(name)
        val dir = directory(id)
        check(dir.mkdirs()) { "无法创建方案。" }
        File(dir, "skin.json").writeText("{\"name\":${quote(name.trim())},\"author\":\"\",\"description\":\"自定义卡牌方案\"}")
        return profile(dir)
    }

    fun importZip(archive: File, filename: String, progress: (String) -> Unit = {}): List<Profile> {
        root.mkdirs()
        val stage = File(root, ".import-${UUID.randomUUID()}")
        val extracted = File(stage, "files")
        check(extracted.mkdirs()) { "无法准备导入目录。" }
        val committed = mutableListOf<File>()
        try {
            openZip(archive).use { zip ->
                val entries = zip.entries().asSequence().filterNot { it.isDirectory }.map { it to safeRelative(it.name) }.toList()
                require(entries.isNotEmpty()) { "压缩包中没有文件。" }
                val seen = hashSetOf<String>()
                var declared = 0L
                entries.forEach { (entry, relative) ->
                    require(seen.add(relative.lowercase(Locale.ROOT))) { "压缩包存在重名文件：$relative" }
                    require(entry.size >= 0 && entry.size <= Long.MAX_VALUE - declared) { "压缩包大小信息无效。" }
                    declared += entry.size
                }
                require(declared < root.usableSpace) { "手机剩余空间不足，无法完整导入。" }
                entries.forEachIndexed { index, (entry, relative) ->
                    progress("正在导入 ${index + 1} / ${entries.size}")
                    val target = File(extracted, relative)
                    target.parentFile!!.mkdirs()
                    var written = 0L
                    zip.getInputStream(entry).use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            require(written <= entry.size) { "压缩包文件长度异常：$relative" }
                            output.write(buffer, 0, n)
                        }
                    } }
                    require(written == entry.size) { "压缩包文件不完整：$relative" }
                }
            }
            val candidates = discover(extracted, filename)
            require(candidates.isNotEmpty()) { "没有找到卡牌图片或动态卡面。旧式 bundle 包请先在 Win 版转换后导出。" }
            val prepared = candidates.map { (source, suggested) ->
                validate(source)
                source to uniqueId(suggested, committed.map { it.name }.toSet())
            }
            profilesRoot.mkdirs()
            val reserved = hashSetOf<String>()
            prepared.forEach { (source, proposed) ->
                val id = uniqueId(proposed, reserved)
                reserved += id
                val destination = directory(id)
                check(source.renameTo(destination)) { "无法提交完整方案：$id" }
                committed += destination
            }
            return committed.map(::profile)
        } catch (error: Throwable) {
            committed.forEach { it.deleteRecursively() }
            throw error
        } finally { stage.deleteRecursively() }
    }

    private fun discover(extracted: File, filename: String): List<Pair<File, String>> {
        val loader = extracted.listFiles().orEmpty().firstOrNull { it.isDirectory && it.name.equals("AstralParty_ModLoader", true) }
        val skins = loader?.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("skins", true) }
        if (skins != null) return skins.listFiles().orEmpty().filter(File::isDirectory).map { it to it.name }
        var candidate = extracted
        while (!hasCards(candidate)) {
            val children = candidate.listFiles().orEmpty().filter { it.name != "__MACOSX" && !it.name.startsWith(".") }
            val directories = children.filter(File::isDirectory)
            // Win 版“多方案”导出可能只给一个上层目录，里面并排多个方案目录。
            if (directories.size > 1) {
                val schemes = directories.filter(::hasCards)
                if (schemes.size == directories.size && children.none(File::isFile)) {
                    return schemes.map { it to it.name }
                }
                break
            }
            if (directories.size != 1 || children.any { it.isFile && it.extension !in setOf("txt", "md") }) break
            candidate = directories.single()
        }
        if (hasCards(candidate)) return listOf(candidate to if (candidate == extracted) File(filename).nameWithoutExtension else candidate.name)
        return emptyList()
    }

    private fun hasCards(dir: File) = dir.listFiles().orEmpty().any { it.isFile && (it.extension.lowercase(Locale.ROOT) in imageExtensions || it.extension.equals("cardanim", true)) }

    private fun validate(dir: File) {
        val files = dir.listFiles().orEmpty().filter(File::isFile)
        require(hasCards(dir)) { "方案 ${dir.name} 没有可用卡面。" }
        files.filter { it.extension.lowercase(Locale.ROOT) in imageExtensions }.forEach {
            require(it.nameWithoutExtension.isNotBlank() && validateImage(it)) { "卡面图片无法读取：${it.name}" }
        }
        files.firstOrNull { it.name.equals("skin.json", true) }?.let {
            require(it.length() <= 1024 * 1024 && parseJsonObjectOrNull(it.readText()) != null) { "skin.json 不是有效的方案信息。" }
            if (it.name != "skin.json") check(it.renameTo(File(dir, "skin.json")))
        }
        files.filter { it.extension.equals("cardanim", true) }.forEach { validateAnimation(it) }
    }

    private fun validateAnimation(manifest: File) {
        require(manifest.length() <= 8192) { "动态卡面清单过大。" }
        val values = linkedMapOf<String, Int>()
        manifest.readText().trimStart('\uFEFF').lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.trim().split('=')
            require(parts.size == 2 && parts[0].trim() in setOf("version", "fps", "frameCount", "width", "height")) { "动态卡面清单格式错误。" }
            val value = parts[1].trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
            require(value != null && values.put(parts[0].trim(), value) == null) { "动态卡面清单包含无效或重复字段。" }
        }
        val w = values["width"] ?: 0; val h = values["height"] ?: 0; val count = values["frameCount"] ?: 0
        require(values.size == 5 && values["version"] == 1 && values["fps"] in 1..30 && count in 1..300 && w in 1..560 && h in 1..560 && w.toLong() * h * count <= 60_000_000) { "动态卡面的版本或尺寸超出 Win 版支持范围。" }
        repeat(count) { index ->
            val frame = File(manifest.parentFile, "${manifest.nameWithoutExtension}.frames/${index.toString().padStart(6, '0')}.png")
            require(frame.isFile && frame.length() <= 8L * 1024 * 1024 && pngSize(frame) == (w to h) && validateImage(frame)) { "动态卡面帧缺失、损坏或尺寸不一致：${frame.name}" }
        }
    }

    fun replace(id: String, key: String, image: File, extension: String) {
        require(validKey(key) && extension.lowercase(Locale.ROOT) in imageExtensions && validateImage(image)) { "卡面名称或图片无效。" }
        val dir = directory(id)
        require(dir.isDirectory) { "方案不存在。" }
        val target = File(dir, "$key.${extension.lowercase(Locale.ROOT)}")
        val temp = File(dir, ".replace-${UUID.randomUUID()}")
        try {
            image.copyTo(temp)
            check(temp.renameTo(target)) { "无法保存卡面。" }
            dir.listFiles().orEmpty().filter { it.isFile && it.nameWithoutExtension.equals(key, true) && it != target && (it.extension.lowercase(Locale.ROOT) in imageExtensions || it.extension.equals("cardanim", true)) }.forEach { it.delete() }
            File(dir, "$key.frames").takeIf(File::isDirectory)?.deleteRecursively()
        } finally { temp.delete() }
    }

    fun restoreCard(id: String, key: String) {
        require(validKey(key)) { "卡面名称无效。" }
        val dir = directory(id)
        require(dir.isDirectory) { "方案不存在。" }
        dir.listFiles().orEmpty().filter {
            (it.isFile && it.nameWithoutExtension.equals(key, true) && (it.extension.lowercase(Locale.ROOT) in imageExtensions || it.extension.equals("cardanim", true))) ||
                (it.isDirectory && it.name.equals("$key.frames", true))
        }.forEach { check(it.deleteRecursively()) { "无法恢复卡面：${it.name}" } }
        File(dir, ".pending").writeText("1")
    }

    fun delete(id: String) { require(directory(id).deleteRecursively()) { "删除方案失败。" } }

    fun export(id: String, destination: File) {
        val directory = directory(id)
        require(directory.isDirectory && hasCards(directory)) { "方案中没有可导出的卡面。" }
        ZipOutputStream(destination.outputStream()).use { zip ->
            directory.walkTopDown().filter(File::isFile).filterNot { it.name.startsWith(".") }.forEach { file ->
                zip.putNextEntry(ZipEntry("$id/" + file.relativeTo(directory).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun profile(dir: File): Profile {
        val metadata = File(dir, "skin.json").takeIf(File::isFile)?.let { runCatching { parseJsonObjectOrNull(it.readText()) }.getOrNull() }
        return Profile(dir.name, metadata?.string("name")?.ifBlank { dir.name } ?: dir.name, metadata?.string("author").orEmpty(), metadata?.string("description").orEmpty(), cards(dir.name).size)
    }

    private fun uniqueId(name: String, reserved: Set<String> = emptySet()): String {
        val clean = name.trim().map { if (it.isLetterOrDigit() || it in "-_ ") it else '_' }.joinToString("").take(80).trim().ifBlank { "custom-cards" }.let { if (it == GameDefault) "custom-cards" else it }
        var candidate = clean; var index = 2
        while (profilesRoot.listFiles().orEmpty().any { it.name.equals(candidate, true) } || reserved.any { it.equals(candidate, true) }) candidate = "$clean-${index++}"
        return candidate
    }

    companion object {
        const val GameDefault = "__game_default__"
        fun validId(id: String) = id.isNotBlank() && id.length <= 120 && !id.startsWith('.') && id != GameDefault && id.none { it == '/' || it == '\\' || it == ':' || it.code < 32 } && id != "." && id != ".."
        fun validKey(key: String) = validId(key) && key.length <= 180 && !key.endsWith('.')
        fun safeRelative(name: String): String {
            val path = name.replace('\\', '/')
            require(path.isNotBlank() && !path.startsWith('/') && path.none { it == ':' || it.code < 32 } && path.split('/').none { it == "." || it == ".." || it.isEmpty() }) { "压缩包包含不安全路径。" }
            return path
        }
        private fun openZip(file: File): ZipFile {
            fun open(charset: Charset): ZipFile {
                val zip = ZipFile(file, charset)
                try { zip.entries().asSequence().forEach { it.name }; return zip }
                catch (error: Exception) { zip.close(); throw error }
            }
            return try { open(Charsets.UTF_8) } catch (_: Exception) { open(Charset.forName("GB18030")) }
        }
        fun quote(value: String) = com.hiynet.aspatoys.data.mods.ModConfigText.quote(value)
        fun pngSize(file: File): Pair<Int, Int>? = runCatching {
            file.inputStream().use { input ->
                val bytes = ByteArray(24)
                if (input.read(bytes) != 24 || bytes.take(8) != listOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10).map { it.toByte() }) return@use null
                fun int(at: Int) = (0..3).fold(0) { value, i -> (value shl 8) or (bytes[at + i].toInt() and 255) }
                int(16) to int(20)
            }
        }.getOrNull()
        fun basicImage(file: File): Boolean = runCatching {
            if (file.length() <= 0) return@runCatching false
            file.inputStream().use { input ->
                val h = ByteArray(8); val n = input.read(h)
                n >= 2 && ((h[0] == 0x89.toByte() && h[1] == 0x50.toByte()) || (h[0] == 0xff.toByte() && h[1] == 0xd8.toByte()) || (h[0] == 0x42.toByte() && h[1] == 0x4d.toByte()))
            }
        }.getOrDefault(false)
    }
}
