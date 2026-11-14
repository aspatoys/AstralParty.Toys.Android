package com.hiynet.cesiumloader.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 原子写入：先写同目录的 `.tmp`，fsync 后 rename 覆盖。
 *
 * 加载器目录会被游戏进程同时读取，半截文件比旧文件更危险，所以任何写入都必须走这里。
 */
object AtomicFiles {

    private const val TmpSuffix = ".tmp"

    fun ensureDirs(vararg dirs: File) {
        for (dir in dirs) {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                throw IllegalStateException("无法创建目录：$dir")
            }
        }
    }

    fun writeBytes(target: File, bytes: ByteArray) {
        val parent = target.parentFile
        if (parent != null) ensureDirs(parent)
        val tmp = File(parent, target.name + TmpSuffix)
        if (tmp.exists()) tmp.delete()
        FileOutputStream(tmp).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        move(tmp, target)
    }

    fun writeText(target: File, text: String) = writeBytes(target, text.toByteArray(Charsets.UTF_8))

    /** 优先 ATOMIC_MOVE，跨设备或文件系统不支持时退回普通 rename。 */
    fun move(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun deleteQuietly(file: File?): Boolean {
        if (file == null || !file.exists()) return true
        return runCatching { file.delete() }.getOrDefault(false)
    }
}
