package com.hiynet.aspatoys.installer

import com.hiynet.cesiumloader.core.AtomicFiles
import java.io.File
import java.util.Properties
import java.util.UUID

interface CacheTransport {
    fun read(path: String): ByteArray
    fun hash(path: String): String
    fun write(path: String, bytes: ByteArray)
    fun move(from: String, to: String)
    fun copyIntoExisting(from: String, to: String)
    fun mkdir(path: String)
    fun delete(path: String)
}

/** Prefer rename; restricted Android/data directories require a verified backup and durable recovery journal. */
class CachePublisher(private val transport: CacheTransport, private val journalDir: File) {
    private fun digest(bytes: ByteArray) = LoaderBundleTransaction.sha256(bytes)
    private fun journal(path: String) = File(journalDir, digest(path.toByteArray()) + ".properties")
    private fun area(path: String): String {
        require(Regex("/sdcard/Android/data/[a-zA-Z0-9_.]+/files/com\\.unity\\.addressables/AssetBundles/[a-f0-9]{32}/[a-f0-9]{32}/__data").matches(path)) {
            "缓存路径无效：$path"
        }
        return path.substringBefore("/files/") + "/files/CesiumLoader/data/transport"
    }
    private fun cleanup(record: Properties, file: File) {
        file.delete()
        for (key in listOf("beforeFile", "afterFile")) runCatching { transport.delete(record.getProperty(key)) }
    }
    fun recover(path: String) {
        val file = journal(path)
        if (!file.isFile) return
        val record = Properties().apply { file.inputStream().use { load(it) } }
        require(record.getProperty("target") == path) { "恢复记录路径不匹配" }
        for (key in listOf("beforeFile", "afterFile")) {
            require(Regex(Regex.escape(area(path)) + "/[a-f0-9-]+\\.(before|after)").matches(record.getProperty(key))) { "恢复文件路径无效" }
        }
        val current = transport.read(path)
        val hash = digest(current)
        if (hash == record.getProperty("before") || hash == record.getProperty("after")) {
            cleanup(record, file); return
        }
        rollback(path, record)
        cleanup(record, file)
    }
    private fun rollback(path: String, record: Properties) {
        val before = transport.read(record.getProperty("beforeFile"))
        val after = transport.read(record.getProperty("afterFile"))
        require(digest(before) == record.getProperty("before") && digest(after) == record.getProperty("after")) { "恢复副本校验失败，保留恢复记录" }
        val current = transport.read(path)
        fun prefixOf(bytes: ByteArray) = current.size <= bytes.size && current.indices.all { current[it] == bytes[it] }
        require(prefixOf(before) || prefixOf(after)) { "缓存发生外部变化，拒绝覆盖；恢复副本保留" }
        transport.copyIntoExisting(record.getProperty("beforeFile"), path)
        require(transport.hash(path) == record.getProperty("before")) { "回滚校验失败，恢复记录保留" }
    }
    fun publish(path: String, bytes: ByteArray, expected: String) {
        require(digest(bytes) == expected) { "待写入文件指纹不符" }
        recover(path)
        val initialHash = transport.hash(path)
        if (initialHash == expected) return
        val paired = when (expected) {
            LoaderInstaller.PATCHED_SHA256 -> LoaderInstaller.ORIGINAL_SHA256
            LoaderInstaller.ORIGINAL_SHA256 -> LoaderInstaller.PATCHED_SHA256
            else -> initialHash
        }
        require(initialHash == paired) { "写入前缓存版本发生变化，未替换目标" }
        val temporary = "$path.cesium-stage-${UUID.randomUUID()}"
        try {
            transport.write(temporary, bytes)
            require(transport.hash(temporary) == expected) { "临时文件校验失败，原缓存未替换" }
            require(transport.hash(path) == initialHash) { "暂存期间缓存发生变化，未替换目标" }
            transport.move(temporary, path)
            require(transport.hash(path) == expected) { "原子写回后校验失败" }
            return
        } catch (error: Exception) {
            val messages = generateSequence<Throwable>(error) { it.cause }.joinToString(" ") { it.message.orEmpty() }.lowercase()
            if (!listOf("permission denied", "operation not permitted", "eacces", "eperm").any { it in messages }) throw error
        } finally { runCatching { transport.delete(temporary) } }

        // Some systems allow writes to __data but prohibit creating/renaming its siblings.
        // Transfer and verify BOTH complete copies before opening the existing inode for writing.
        val directory = area(path)
        transport.mkdir(directory)
        val prefix = "$directory/${UUID.randomUUID()}"
        val original = transport.read(path)
        val before = digest(original)
        require(before == initialHash) { "兼容写入前缓存发生变化，未替换目标" }
        val record = Properties().apply {
            setProperty("target", path); setProperty("before", before); setProperty("after", expected)
            setProperty("beforeFile", "$prefix.before"); setProperty("afterFile", "$prefix.after")
        }
        val file = journal(path)
        var journalWritten = false
        try {
            transport.write("$prefix.before", original)
            transport.write("$prefix.after", bytes)
            require(transport.hash("$prefix.before") == before && transport.hash("$prefix.after") == expected) { "兼容写入暂存校验失败，原缓存未修改" }
            val metadata = java.io.ByteArrayOutputStream().also { record.store(it, "Cesium cache recovery") }.toByteArray()
            AtomicFiles.writeBytes(file, metadata)
            journalWritten = true
            require(transport.hash(path) == before) { "写回前缓存发生变化，原文件未修改" }
            try {
                transport.copyIntoExisting("$prefix.after", path)
                require(transport.hash(path) == expected) { "兼容写回校验失败" }
            } catch (error: Exception) {
                try { rollback(path, record); cleanup(record, file) }
                catch (recovery: Exception) { throw IllegalStateException("写回失败且回滚未完成，恢复记录已保留：${recovery.message}", error) }
                throw IllegalStateException("写回失败，已恢复操作前缓存：${error.message}", error)
            }
            cleanup(record, file)
        } finally {
            if (!journalWritten) for (suffix in listOf("before", "after")) runCatching { transport.delete("$prefix.$suffix") }
        }
    }
}
