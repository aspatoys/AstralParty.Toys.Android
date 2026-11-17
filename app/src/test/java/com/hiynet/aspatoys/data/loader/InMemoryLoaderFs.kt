package com.hiynet.aspatoys.data.loader

import java.io.File

/**
 * 内存版 [LoaderFs]，给启停/内置投放的单元测试用。
 *
 * 语义要与真实 `mv`/`rm -rf` 对齐：目录搬移会把子文件一起带走、`delete` 递归删子树、
 * `exists` 对祖先目录也返回 true —— 重复目录整理依赖这些行为才能被测出来。
 */
class InMemoryLoaderFs : LoaderFs {
    val files = mutableMapOf<String, ByteArray>()
    val directories = mutableSetOf<String>()
    var available = true
    var writes = 0
    var deletes = 0
        private set
    var moves = 0
        private set

    override fun isAvailable() = available
    override fun exists(path: String) =
        path in files || path in directories || (files.keys + directories).any { it.startsWith("$path/") }

    override fun readText(path: String) = files[path]?.toString(Charsets.UTF_8)

    override fun writeText(path: String, text: String) {
        makeDirectories(path.substringBeforeLast('/', ""))
        files[path] = text.toByteArray()
        writes++
    }

    override fun readBytes(path: String) = files[path]

    override fun fileSize(path: String) = files[path]?.size?.toLong() ?: 0L

    override fun listFiles(path: String) = children(path).filter { it in files }.map { it.substringAfterLast('/') }

    override fun listDirectories(path: String) = children(path).filter { it !in files }.map { it.substringAfterLast('/') }

    override fun copyIn(localFile: File, remotePath: String) {
        makeDirectories(remotePath.substringBeforeLast('/', ""))
        files[remotePath] = localFile.readBytes()
        writes++
    }

    override fun makeDirectories(path: String) {
        var current = path
        while (current.isNotEmpty()) {
            if (current !in files) directories += current
            current = current.substringBeforeLast('/', "")
        }
    }

    override fun move(from: String, to: String) {
        moves++
        if (from in files) {
            makeDirectories(to.substringBeforeLast('/', ""))
            files[to] = files.remove(from) ?: error("Missing $from")
            return
        }
        require(from in directories || files.keys.any { it.startsWith("$from/") }) { "Missing $from" }
        makeDirectories(to)
        for (path in files.keys.filter { it.startsWith("$from/") }) {
            files["$to/${path.removePrefix("$from/")}"] = files.remove(path) ?: continue
        }
        for (path in directories.filter { it == from || it.startsWith("$from/") }) {
            directories -= path
            directories += ("$to/${path.removePrefix("$from/")}").trimEnd('/')
        }
        directories += to
    }

    override fun delete(path: String) {
        deletes++
        files.keys.removeAll { it == path || it.startsWith("$path/") }
        directories.removeAll { it == path || it.startsWith("$path/") }
    }

    override val lastError: String? = null

    private fun children(path: String) = (files.keys + directories)
        .filter { it != path && it.startsWith("$path/") && it.removePrefix("$path/").substringBefore('/') == it.removePrefix("$path/") }
}