package com.hiynet.aspatoys.installer

import com.hiynet.cesiumloader.core.AtomicFiles
import java.io.File
import java.security.MessageDigest

/** A verified remote publication must leave the old file intact until its staged hash is correct. */
interface BundleIo {
    fun read(path: String): ByteArray
    fun hash(path: String): String
    fun publish(path: String, bytes: ByteArray, expectedSha256: String)
    fun stopGame()
    /** Content hashes alone cannot detect a cache left owned by root by an older release. */
    fun ensureAccessible(path: String) {}
}

/** Installation and recovery share the same version identity and never overwrite the legacy backup. */
class LoaderBundleTransaction(
    private val workDir: File,
    private val io: BundleIo,
    private val originalBytes: () -> ByteArray,
    private val patch: (ByteArray, (String) -> Unit) -> ByteArray,
    private val deploy: ((String) -> Unit) -> Unit,
    val originalSha256: String,
    val patchedSha256: String,
) {
    enum class State { ORIGINAL, INSTALLED, UNKNOWN }

    val backupFile: File get() = File(workDir, "backups/$originalSha256/__data.orig")
    private val legacyBackup: File get() = File(workDir, "__data.orig")

    fun classify(hash: String?): State = when (hash?.lowercase()) {
        originalSha256 -> State.ORIGINAL
        patchedSha256 -> State.INSTALLED
        else -> State.UNKNOWN
    }

    fun backupAvailable(remoteHash: String?): Boolean {
        if (classify(remoteHash) == State.UNKNOWN) return false
        return runCatching { recoverySource(); true }.getOrDefault(false)
    }

    fun install(remote: String, log: (String) -> Unit = {}) {
        val current = io.read(remote)
        val currentSha = sha256(current)
        val state = classify(currentSha)
        require(state != State.UNKNOWN) {
            "缓存包版本未适配（$currentSha），未修改游戏缓存或原版备份；请勿用旧备份覆盖更新后的游戏"
        }
        log("缓存包：$remote")
        log("当前 ${current.size} B  sha256=$currentSha")

        if (state == State.INSTALLED) {
            // In particular, never save the installed bundle as __data.orig on a repeated install.
            preserveBackup(recoverySource(), log)
            io.stopGame()
            require(io.hash(remote) == currentSha) { "缓存包在操作期间发生变化，未修改游戏缓存" }
            io.ensureAccessible(remote)
            deploy(log)
            log("已识别正常安装产物：仅更新加载器及模组，不重复注入")
            return
        }

        val patched = patch(current, log)
        require(sha256(patched) == patchedSha256) { "注入产物指纹与此游戏版本不符，未修改游戏缓存" }
        preserveBackup(current, log)
        io.stopGame()
        require(io.hash(remote) == currentSha) { "缓存包在操作期间发生变化，未修改游戏缓存" }
        io.ensureAccessible(remote)
        deploy(log)
        require(io.hash(remote) == currentSha) { "部署期间缓存包发生变化，未修改游戏缓存" }
        try {
            io.publish(remote, patched, patchedSha256)
            require(io.hash(remote) == patchedSha256) { "写回校验失败" }
        } catch (error: Exception) {
            // Roll back only our exact known output; never overwrite an unknown newer game cache.
            if (runCatching { io.hash(remote) }.getOrNull() == patchedSha256) {
                runCatching { io.publish(remote, current, originalSha256) }
                    .onSuccess { log("已恢复本次安装前的原包") }
                    .onFailure { log("自动恢复失败，原版备份仍保留：${it.message}") }
            }
            throw error
        }
        log("安装及写回校验通过，原版备份已保留")
    }

    fun restore(remote: String, log: (String) -> Unit = {}) {
        val currentSha = io.hash(remote)
        when (classify(currentSha)) {
            State.ORIGINAL -> {
                io.stopGame()
                io.ensureAccessible(remote)
                log("缓存包已经是原版，已校验属主，无需重复还原")
                return
            }
            State.UNKNOWN -> error("当前缓存为未知版本（$currentSha），未用旧原包覆盖游戏；请在游戏中修复资源")
            State.INSTALLED -> Unit
        }
        val original = recoverySource()
        preserveBackup(original, log)
        io.stopGame()
        require(io.hash(remote) == currentSha) { "缓存包在还原前发生变化，未写回" }
        io.ensureAccessible(remote)
        io.publish(remote, original, originalSha256)
        require(io.hash(remote) == originalSha256) { "还原后指纹校验失败，原版备份仍保留" }
        log("还原完成：原包 sha256=$originalSha256")
    }

    private fun recoverySource(): ByteArray {
        for (file in listOf(backupFile, legacyBackup)) {
            val bytes = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull() ?: continue
            if (sha256(bytes) == originalSha256) return bytes
        }
        val bundled = runCatching { originalBytes() }.getOrElse {
            throw IllegalStateException("没有经过校验的原包备份，内置恢复资源也不可用；请在游戏中修复资源", it)
        }
        require(sha256(bundled) == originalSha256) { "内置原包校验失败，拒绝写回游戏缓存" }
        return bundled
    }

    private fun preserveBackup(bytes: ByteArray, log: (String) -> Unit) {
        require(sha256(bytes) == originalSha256) { "仅允许备份已验证的原版缓存" }
        if (backupFile.isFile) {
            val previous = backupFile.readBytes()
            if (sha256(previous) == originalSha256) return
            // Preserve damaged evidence instead of silently treating it as a valid original.
            val quarantine = File(backupFile.parentFile, "__data.orig.invalid-${sha256(previous).take(16)}-${System.nanoTime()}")
            AtomicFiles.move(backupFile, quarantine)
            log("旧备份损坏，已另存；使用校验通过的原版恢复资源")
        }
        AtomicFiles.writeBytes(backupFile, bytes)
        require(sha256(backupFile.readBytes()) == originalSha256) { "原版备份落盘校验失败" }
        log("原版备份已验证：${backupFile.name}")
    }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
