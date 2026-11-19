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
    private val adaptivePatch: ((ByteArray, (String) -> Unit) -> ByteArray)? = null,
    private val useLegacyProfile: Boolean = true,
) {
    enum class State { ORIGINAL, INSTALLED, UNKNOWN }

    val backupFile: File get() = File(workDir, "backups/$originalSha256/__data.orig")
    private val legacyBackup: File get() = File(workDir, "__data.orig")

    fun classify(hash: String?, remote: String? = null): State {
        val value = hash?.lowercase()
        return when {
            useLegacyProfile && value == originalSha256 -> State.ORIGINAL
            useLegacyProfile && value == patchedSha256 -> State.INSTALLED
            remote == null || value == null -> State.UNKNOWN
            recordedOriginal(remote, value) != null -> State.INSTALLED
            knownOriginal(remote, value) -> State.ORIGINAL
            else -> State.UNKNOWN
        }
    }

    fun backupAvailable(remoteHash: String?, remote: String? = null): Boolean {
        val hash = remoteHash?.lowercase() ?: return false
        return runCatching {
            when {
                useLegacyProfile && (hash == originalSha256 || hash == patchedSha256) -> recoverySource()
                remote != null -> recoverySource(
                    recordedOriginal(remote, hash) ?: hash.takeIf { knownOriginal(remote, it) } ?: return false
                )
                else -> return false
            }
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun install(remote: String, log: (String) -> Unit = {}) = install(remote, log, updatePayload = true)

    @Synchronized
    fun enable(remote: String, log: (String) -> Unit) = install(remote, log, updatePayload = false)

    private fun install(remote: String, log: (String) -> Unit, updatePayload: Boolean) {
        val current = io.read(remote)
        val currentSha = sha256(current)
        val state = classify(currentSha, remote)
        log("缓存包：$remote")
        log("当前 ${current.size} B  sha256=$currentSha")

        if (state == State.INSTALLED) {
            // In particular, never save the installed bundle as __data.orig on a repeated install.
            val expected = if (useLegacyProfile && currentSha == patchedSha256) originalSha256 else
                checkNotNull(recordedOriginal(remote, currentSha)) { "缺少对应版本恢复记录" }
            preserveBackup(recoverySource(expected), log, expected)
            io.stopGame()
            require(io.hash(remote) == currentSha) { "缓存包在操作期间发生变化，未修改游戏缓存" }
            io.ensureAccessible(remote)
            if (updatePayload) deploy(log)
            log("已识别加载器入口，保留当前缓存包")
            return
        }

        val legacy = useLegacyProfile && currentSha == originalSha256
        val patcher = if (legacy) patch else adaptivePatch
        requireNotNull(patcher) {
            "缓存包版本未适配（$currentSha），未修改游戏缓存或原版备份；请勿用旧备份覆盖更新后的游戏"
        }
        val patched = patcher(current, log)
        val outputSha = sha256(patched)
        require(if (legacy) outputSha == patchedSha256 else outputSha != currentSha &&
            outputSha != originalSha256 && outputSha != patchedSha256) {
            "注入产物指纹无效，未修改游戏缓存"
        }
        preserveBackup(current, log, currentSha)
        io.stopGame()
        require(io.hash(remote) == currentSha) { "缓存包在操作期间发生变化，未修改游戏缓存" }
        // Keep the recovery identity even if the process is killed just after publication.
        if (!legacy) {
            AtomicFiles.writeText(recordFile(remote, "original", currentSha), currentSha)
            AtomicFiles.writeText(recordFile(remote, "patched", outputSha), currentSha)
        }
        io.ensureAccessible(remote)
        if (updatePayload) deploy(log)
        require(io.hash(remote) == currentSha) { "部署期间缓存包发生变化，未修改游戏缓存" }
        try {
            io.publish(remote, patched, outputSha)
            require(io.hash(remote) == outputSha) { "写回校验失败" }
        } catch (error: Exception) {
            // Roll back only our exact known output; never overwrite an unknown newer game cache.
            if (runCatching { io.hash(remote) }.getOrNull() == outputSha) {
                runCatching { io.publish(remote, current, currentSha) }
                    .onSuccess { log("已恢复本次安装前的原包") }
                    .onFailure { log("自动恢复失败，原版备份仍保留：${it.message}") }
            }
            throw error
        }
        log("安装及写回校验通过，原版备份已保留")
    }

    @Synchronized
    fun restore(remote: String, log: (String) -> Unit = {}): String {
        val currentSha = io.hash(remote)
        when (classify(currentSha, remote)) {
            State.ORIGINAL -> {
                io.stopGame()
                require(io.hash(remote) == currentSha) { "缓存包在还原前发生变化，未写回" }
                io.ensureAccessible(remote)
                log("缓存包已经是原版，已校验属主，无需重复还原")
                return currentSha
            }
            State.UNKNOWN -> error("当前缓存为未知版本（$currentSha），未用旧原包覆盖游戏；请在游戏中修复资源")
            State.INSTALLED -> Unit
        }
        val expected = if (useLegacyProfile && currentSha == patchedSha256) originalSha256 else
            checkNotNull(recordedOriginal(remote, currentSha)) { "缺少对应版本恢复记录" }
        val original = recoverySource(expected)
        preserveBackup(original, log, expected)
        io.stopGame()
        require(io.hash(remote) == currentSha) { "缓存包在还原前发生变化，未写回" }
        io.ensureAccessible(remote)
        io.publish(remote, original, expected)
        require(io.hash(remote) == expected) { "还原后指纹校验失败，原版备份仍保留" }
        log("还原完成：原包 sha256=$expected")
        return expected
    }

    private fun recoverySource(expected: String = originalSha256): ByteArray {
        val legacy = useLegacyProfile && expected == originalSha256
        val candidates = if (legacy) listOf(backupFile, legacyBackup) else listOf(backupFor(expected))
        for (file in candidates) {
            val bytes = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull() ?: continue
            if (sha256(bytes) == expected) return bytes
        }
        require(legacy) { "当前热更包的原始备份缺失或指纹不符，未写回游戏缓存" }
        val bundled = runCatching { originalBytes() }.getOrElse {
            throw IllegalStateException("没有经过校验的原包备份，内置恢复资源也不可用；请在游戏中修复资源", it)
        }
        require(sha256(bundled) == originalSha256) { "内置原包校验失败，拒绝写回游戏缓存" }
        return bundled
    }

    private fun preserveBackup(bytes: ByteArray, log: (String) -> Unit, expected: String = originalSha256) {
        require(sha256(bytes) == expected) { "仅允许备份已验证的原版缓存" }
        val backupFile = backupFor(expected)
        if (backupFile.isFile) {
            val previous = backupFile.readBytes()
            if (sha256(previous) == expected) return
            // Preserve damaged evidence instead of silently treating it as a valid original.
            val quarantine = File(backupFile.parentFile, "__data.orig.invalid-${sha256(previous).take(16)}-${System.nanoTime()}")
            AtomicFiles.move(backupFile, quarantine)
            log("旧备份损坏，已另存；使用校验通过的原版恢复资源")
        }
        AtomicFiles.writeBytes(backupFile, bytes)
        require(sha256(backupFile.readBytes()) == expected) { "原版备份落盘校验失败" }
        log("原版备份已验证：${backupFile.name}")
    }

    private fun backupFor(hash: String) = File(workDir, "backups/$hash/__data.orig")
    private fun recordFile(path: String, kind: String, hash: String) =
        File(workDir, "adaptive/${sha256(path.toByteArray())}/$kind/$hash")

    private fun recordedOriginal(path: String, patched: String): String? {
        if (!HASH.matches(patched)) return null
        return runCatching { recordFile(path, "patched", patched).readText() }
            .getOrNull()?.takeIf { HASH.matches(it) && it != patched }
    }

    private fun knownOriginal(path: String, hash: String): Boolean =
        HASH.matches(hash) && recordFile(path, "original", hash).isFile

    companion object {
        private val HASH = Regex("[a-f0-9]{64}")
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
