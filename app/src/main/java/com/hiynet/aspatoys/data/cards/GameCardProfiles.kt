package com.hiynet.aspatoys.data.cards

import com.hiynet.aspatoys.core.booleanOrDefault
import com.hiynet.aspatoys.core.parseJsonObjectOrNull
import com.hiynet.aspatoys.core.string
import com.hiynet.aspatoys.data.loader.LoaderFs
import com.hiynet.aspatoys.data.mods.ModConfigText
import java.io.File
import java.util.UUID

/** All game paths are captured per operation and all remote writes use LoaderFs atomic copy. */
class GameCardProfiles(
    private val fs: LoaderFs,
    private val store: CardPackageStore,
    private val asset: (String) -> ByteArray,
    private val listAssets: (String) -> List<String>,
) {
    data class Snapshot(val selected: String, val profiles: List<CardPackageStore.Profile>, val problem: String? = null)

    fun snapshot(root: String): Snapshot {
        if (!fs.isAvailable()) return Snapshot("", emptyList(), "连接 Shizuku 或 Root 后可读取和应用游戏卡面。")
        if (!sdkExists(root)) return Snapshot("", emptyList(), "当前目录未安装加载器，请先到加载器页安装。")
        val raw = readConfig(root)
        val config = parseJsonObjectOrNull(raw) ?: error("CardSkinMod 配置无法解析，已保留原文件。")
        val selected = if (!config.booleanOrDefault("Enabled", true)) CardPackageStore.GameDefault else config.string("ActiveSkin").ifBlank { "default" }
        val resolvedSelection = if (selected != CardPackageStore.GameDefault && !fs.exists("$root/skins/$selected")) CardPackageStore.GameDefault else selected
        val profiles = fs.listDirectories("$root/skins").filter { CardPackageStore.validId(it) }.map { id ->
            val info = fs.readText("$root/skins/$id/skin.json")?.let(::parseJsonObjectOrNull)
            val count = fs.listFiles("$root/skins/$id").filter { it.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "bmp", "cardanim") }.map { it.substringBeforeLast('.').lowercase() }.distinct().size
            CardPackageStore.Profile(id, info?.string("name")?.ifBlank { id } ?: id, info?.string("author").orEmpty(), info?.string("description").orEmpty(), count)
        }
        fs.lastError?.let { error(it) }
        return Snapshot(resolvedSelection, profiles)
    }

    fun apply(root: String, id: String, progress: (String) -> Unit = {}) {
        ready(root)
        require(id == CardPackageStore.GameDefault || CardPackageStore.validId(id)) { "方案名称无效。" }
        // Prepare a complete config first. A malformed existing file must never be overwritten.
        val config = updatedConfig(readConfig(root), id)
        if (id != CardPackageStore.GameDefault) {
            val local = store.directory(id)
            require(local.isDirectory || fs.exists("$root/skins/$id")) { "方案不存在，请重新导入。" }
            // An empty edited profile clears its last override and restores the game's artwork.
            installRuntime(root)
            if (local.isDirectory) {
                progress("正在把卡面写入游戏")
                deployDirectory(root, id, local, progress)
            }
        }
        fs.makeDirectories("$root/configs")
        fs.writeText("$root/configs/CardSkinMod.json", config)
        if (id != CardPackageStore.GameDefault) File(store.directory(id), ".pending").delete()
    }

    private fun installRuntime(root: String) {
        val relative = "cesium/mods/CardSkinMod"
        require(listAssets(relative).contains("CardSkinMod.dll")) { "安装包缺少卡面模组，请更新应用。" }
        val active = "$root/mods/CardSkinMod"
        val disabled = "$root/disabled_mods/CardSkinMod"
        require(!fs.exists(active) || !fs.exists(disabled)) { "启用和停用目录中同时存在 CardSkinMod，请先在模组页整理。" }
        // Applying a card scheme explicitly enables its runtime. Unrelated mods are never moved.
        if (!fs.exists(active) && fs.exists(disabled)) fs.move(disabled, active)
        fs.makeDirectories(active)
        fun copyTree(source: String, target: String) {
            fs.makeDirectories(target)
            for (name in listAssets(source)) {
                val path = "$source/$name"
                if (listAssets(path).isNotEmpty()) copyTree(path, "$target/$name")
                else {
                    val bytes = asset(path)
                    require(bytes.isNotEmpty()) { "卡面模组文件为空。" }
                    if (name == "CardSkinMod.dll" && fs.exists("$target/$name") && !fs.exists("$target/$name.pre-1.2.0")) {
                        val previous = fs.readBytes("$target/$name") ?: error("无法备份原卡面模组。")
                        copyBytes(previous, "$target/$name.pre-1.2.0")
                    }
                    copyBytes(bytes, "$target/$name")
                }
            }
        }
        copyTree(relative, active)
    }

    private fun deployDirectory(root: String, id: String, source: File, progress: (String) -> Unit) {
        val suffix = UUID.randomUUID().toString()
        val stage = "$root/.card-import-$suffix"
        val backup = "$root/.card-backup-$suffix"
        val target = "$root/skins/$id"
        var movedOld = false
        var committed = false
        try {
            fs.makeDirectories(stage)
            val files = source.walkTopDown().filter { it.isFile && !it.relativeTo(source).invariantSeparatorsPath.split('/').any { segment -> segment.startsWith('.') } }.toList()
            files.forEachIndexed { index, file ->
                val relative = CardPackageStore.safeRelative(file.relativeTo(source).invariantSeparatorsPath)
                val remote = "$stage/$relative"
                fs.makeDirectories(remote.substringBeforeLast('/'))
                fs.copyIn(file, remote)
                check(fs.fileSize(remote) == file.length()) { "卡面写入不完整：${file.name}" }
                progress("正在写入卡面 ${index + 1} / ${files.size}")
            }
            fs.makeDirectories("$root/skins")
            if (fs.exists(target)) { fs.move(target, backup); movedOld = true }
            fs.move(stage, target)
            committed = true
        } catch (error: Throwable) {
            if (movedOld && !committed) runCatching { fs.move(backup, target) }.onFailure { error.addSuppressed(it) }
            throw error
        } finally {
            runCatching { fs.delete(stage) }
            if (committed && movedOld) runCatching { fs.delete(backup) }
        }
    }

    fun remove(root: String, id: String) {
        ready(root)
        require(CardPackageStore.validId(id)) { "方案名称无效。" }
        require(snapshot(root).selected != id) { "请先切换到游戏默认卡面，再删除当前方案。" }
        fs.delete("$root/skins/$id")
    }

    /** Fetch an existing game-side scheme into the editor without exposing a partial local profile. */
    fun fetch(root: String, id: String, progress: (String) -> Unit = {}) {
        ready(root)
        require(CardPackageStore.validId(id))
        if (store.directory(id).isDirectory) return
        val stage = File(store.root, ".pull-${UUID.randomUUID()}")
        stage.mkdirs()
        try {
            fun copy(remote: String, local: File) {
                local.mkdirs()
                for (name in fs.listFiles(remote)) {
                    CardPackageStore.safeRelative(name)
                    require(!name.contains('/') && !name.contains('\\')) { "游戏方案文件名无效。" }
                    progress("正在读取 $name")
                    val bytes = fs.readBytes("$remote/$name") ?: error("无法读取卡面：$name")
                    File(local, name).writeBytes(bytes)
                }
                for (name in fs.listDirectories(remote)) {
                    require(CardPackageStore.validId(name) && name != local.name) { "方案目录结构无效。" }
                    copy("$remote/$name", File(local, name))
                }
                fs.lastError?.let { error(it) }
            }
            copy("$root/skins/$id", stage)
            store.profilesRoot.mkdirs()
            check(stage.renameTo(store.directory(id))) { "无法保存游戏方案。" }
        } finally { stage.deleteRecursively() }
    }

    private fun ready(root: String) {
        check(fs.isAvailable()) { "请先在加载器页连接 Shizuku 或 Root。" }
        check(sdkExists(root)) { "请先安装加载器，或在设置中选择正确的加载器目录。" }
    }

    private fun sdkExists(root: String) = fs.exists("$root/sdk/CesiumLoader.SDK.dll") || fs.exists("$root/CesiumLoader.SDK.dll")

    private fun readConfig(root: String): String {
        val current = "$root/configs/CardSkinMod.json"
        if (fs.exists(current)) return fs.readText(current)?.takeIf { it.isNotBlank() } ?: error("无法读取卡面配置，原文件已保留。")
        for (folder in listOf("mods", "disabled_mods")) {
            val legacy = "$root/$folder/CardSkinMod/config.json"
            if (fs.exists(legacy)) return fs.readText(legacy)?.takeIf { it.isNotBlank() } ?: error("无法读取旧卡面配置。")
        }
        return "{}"
    }

    private fun copyBytes(bytes: ByteArray, remote: String) {
        val temp = File.createTempFile("card-runtime-", ".tmp", store.root.apply { mkdirs() })
        try { temp.writeBytes(bytes); fs.copyIn(temp, remote) } finally { temp.delete() }
    }

    companion object {
        fun updatedConfig(raw: String, id: String): String {
            val parsed = parseJsonObjectOrNull(raw) ?: error("CardSkinMod 配置不是有效 JSON，已保留原文件。")
            var result = raw
            val updates = linkedMapOf("Enabled" to (id != CardPackageStore.GameDefault).toString())
            if (id != CardPackageStore.GameDefault) updates["ActiveSkin"] = ModConfigText.quote(id)
            for ((key, value) in updates) {
                result = if (parsed.containsKey(key)) ModConfigText.patchValue(result, key, value) ?: error("无法安全修改卡面配置字段：$key")
                else {
                    val at = result.indexOf('{') + 1
                    result.substring(0, at) + "\n  \"$key\": $value" + (if (parseJsonObjectOrNull(result)!!.isNotEmpty()) "," else "") + result.substring(at)
                }
            }
            check(parseJsonObjectOrNull(result) != null)
            return result
        }
    }
}
