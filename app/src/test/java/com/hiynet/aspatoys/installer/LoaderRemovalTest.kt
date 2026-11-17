package com.hiynet.aspatoys.installer

import com.hiynet.aspatoys.data.loader.LoaderFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Uninstall order is the whole point of [LoaderRemoval]: the game bundle must be restored and
 * verified first, and only then may the loader tree and the local install backups disappear.
 */
class LoaderRemovalTest {

    private val root = LoaderInstaller.LOADER_ROOT

    private fun payloadFs(): FakeFs = FakeFs().apply {
        files["$root/sdk/CesiumLoader.SDK.dll"] = byteArrayOf(1, 2, 3)
        files["$root/mods/FontMod/FontMod.dll"] = byteArrayOf(4, 5)
        files["$root/configs/loader.json"] = """{"theme":"dark"}""".toByteArray()
        files["$root/skins/role.pack"] = byteArrayOf(6)
    }

    @Test
    fun failedRestoreKeepsLoaderFilesAndTheInstallBackup() {
        val fs = payloadFs()
        var cleaned = false

        val failure = runCatching {
            LoaderRemoval.remove(
                fs,
                root,
                restoreOriginal = { error("原包校验失败，未删除加载器文件") },
                cleanupLocal = { cleaned = true },
            )
        }.exceptionOrNull()

        assertEquals("原包校验失败，未删除加载器文件", failure?.message)
        assertEquals("a failed restore must not delete anything", 0, fs.deletes)
        assertTrue(fs.exists("$root/sdk/CesiumLoader.SDK.dll"))
        assertTrue(fs.exists("$root/mods/FontMod/FontMod.dll"))
        assertTrue(fs.exists("$root/configs/loader.json"))
        assertTrue(fs.exists("$root/skins/role.pack"))
        assertFalse("backups must survive a failed restore", cleaned)
    }

    @Test
    fun restoreRunsBeforeTheDeleteAndCleanupRunsLast() {
        val fs = payloadFs()
        val events = mutableListOf<String>()
        fs.onDelete = { events += "delete" }

        LoaderRemoval.remove(
            fs,
            root,
            restoreOriginal = { events += "restore" },
            cleanupLocal = { events += "cleanup" },
        )

        assertEquals(listOf("restore", "delete", "cleanup"), events)
        assertFalse("the whole loader tree is gone", fs.exists("$root/sdk/CesiumLoader.SDK.dll"))
        assertFalse(fs.exists(root))
    }

    @Test
    fun deleteFailureKeepsTheInstallBackup() {
        val fs = payloadFs().apply { failDelete = true }
        var restored = false
        var cleaned = false

        val failure = runCatching {
            LoaderRemoval.remove(
                fs,
                root,
                restoreOriginal = { restored = true },
                cleanupLocal = { cleaned = true },
            )
        }.exceptionOrNull()

        assertTrue(restored)
        assertTrue(fs.deletes > 0)
        assertTrue(fs.exists("$root/sdk/CesiumLoader.SDK.dll"))
        assertFalse("files left behind must keep the restore backup", cleaned)
        assertTrue(
            "message must explain why the backup was kept: ${failure?.message}",
            failure?.message?.contains("保留安装备份") == true,
        )
    }

    @Test
    fun onlyTheFixedAndroidLoaderRootIsEverDeleted() {
        val fs = payloadFs()
        var restored = false
        var cleaned = false

        val failure = runCatching {
            LoaderRemoval.remove(
                fs,
                "/sdcard/Android/data/${LoaderInstaller.GAME_PACKAGE}/files",
                restoreOriginal = { restored = true },
                cleanupLocal = { cleaned = true },
            )
        }.exceptionOrNull()

        assertEquals("拒绝删除非 Android 加载器目录", failure?.message)
        assertFalse("nothing may be touched for a refused root", restored || cleaned)
        assertEquals(0, fs.deletes)
        assertFalse(fs.exists("$root/configs/loader.json").not())
    }

    @Test
    fun unavailableShellRefusesBeforeRestoring() {
        val fs = payloadFs().apply { available = false }
        var restored = false

        val failure = runCatching {
            LoaderRemoval.remove(fs, root, restoreOriginal = { restored = true }, cleanupLocal = {})
        }.exceptionOrNull()

        assertEquals("请先连接 Shizuku / Root", failure?.message)
        assertFalse(restored)
        assertEquals(0, fs.deletes)
    }

    private class FakeFs : LoaderFs {
        val files = linkedMapOf<String, ByteArray>()
        val directories = linkedSetOf<String>()
        var available = true
        var failDelete = false
        var deletes = 0
        var onDelete: (() -> Unit)? = null
        private var error: String? = null

        private fun gone(path: String) {
            files.keys.removeAll { it == path || it.startsWith("$path/") }
            directories.removeAll { it == path || it.startsWith("$path/") }
        }

        override fun isAvailable() = available
        override fun exists(path: String) = path in files || path in directories
        override fun readText(path: String) = files[path]?.toString(Charsets.UTF_8)
        override fun writeText(path: String, text: String) { files[path] = text.toByteArray() }
        override fun readBytes(path: String) = files[path]
        override fun fileSize(path: String) = files[path]?.size?.toLong() ?: 0L
        override fun listDirectories(path: String) = directories.filter { it.startsWith("$path/") }
            .map { it.removePrefix("$path/").substringBefore('/') }.distinct()
        override fun listFiles(path: String) = files.keys.filter { it.startsWith("$path/") }
            .map { it.removePrefix("$path/") }.filter { !it.contains('/') }
        override fun makeDirectories(path: String) { directories += path }
        override fun move(from: String, to: String) {
            val bytes = files.remove(from) ?: error("move 源不存在：$from")
            files[to] = bytes
        }
        override fun copyIn(localFile: File, remotePath: String) { files[remotePath] = localFile.readBytes() }
        override fun delete(path: String) {
            deletes++
            onDelete?.invoke()
            if (failDelete) {
                error = "rm -rf 失败：$path"
                return
            }
            gone(path)
        }
        override val lastError: String? get() = error
    }
}
