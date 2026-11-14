package com.hiynet.aspatoys.data.loader

import com.hiynet.aspatoys.data.mods.ModLayout
import com.hiynet.aspatoys.data.mods.ModPackageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [ModInstaller] 的行为：安装（嵌套资源、停用位置、旧文件清理）与启停（目录搬移的边界情况）。
 *
 * 用一个直连本地文件的 [LoaderFs] 顶替 Shizuku —— 目录搬移的坑（目标已存在、源不存在）都在
 * 语义层面，与命令通道无关。
 */
class ModInstallerTest {

    private lateinit var root: File
    private lateinit var layout: ModLayout
    private lateinit var fs: LocalFs
    private lateinit var installer: ModInstaller

    @Before
    fun setUp() {
        root = Files.createTempDirectory("cesium-installer").toFile()
        layout = ModLayout(root)
        fs = LocalFs()
        installer = ModInstaller(fs)
        layout.modsRoot.mkdirs()
        layout.disabledModsRoot.mkdirs()
    }

    // ============================== 安装 ==============================

    @Test
    fun `install writes nested resources`() {
        val zip = zipOf(
            "DemoMod/DemoMod.dll" to byteArrayOf(1, 2, 3),
            "DemoMod/DemoMod.json" to """{"id":"DemoMod","version":"1.0.0"}""".toByteArray(),
            "DemoMod/data/strings/zh.json" to """{"a":"b"}""".toByteArray(),
        )

        val result = installer.install(zip, layout, {})

        assertEquals("DemoMod", result.modId)
        assertEquals(3, result.files)
        assertFalse(result.disabled)
        assertTrue(File(layout.modsRoot, "DemoMod/DemoMod.dll").isFile)
        assertTrue(File(layout.modsRoot, "DemoMod/data/strings/zh.json").isFile)
    }

    @Test
    fun `install accepts the loader-root prefix inside the archive`() {
        val zip = zipOf(
            "mods/PrefixedMod/PrefixedMod.dll" to byteArrayOf(9),
            "mods/PrefixedMod/PrefixedMod.json" to """{"id":"PrefixedMod","version":"1.0.0"}""".toByteArray(),
        )

        val result = installer.install(zip, layout, {})

        assertEquals("PrefixedMod", result.modId)
        assertTrue(File(layout.modsRoot, "PrefixedMod/PrefixedMod.dll").isFile)
        assertFalse(File(root, "mods/mods").exists())
    }

    @Test
    fun `install prunes files that are gone from the new version`() {
        val first = zipOf(
            "DemoMod/DemoMod.dll" to byteArrayOf(1),
            "DemoMod/DemoMod.json" to """{"id":"DemoMod","version":"1.0.0"}""".toByteArray(),
            "DemoMod/legacy.dll" to byteArrayOf(2),
        )
        installer.install(first, layout, {})
        assertTrue(File(layout.modsRoot, "DemoMod/legacy.dll").isFile)

        val second = zipOf(
            "DemoMod/DemoMod.dll" to byteArrayOf(1),
            "DemoMod/DemoMod.json" to """{"id":"DemoMod","version":"1.1.0"}""".toByteArray(),
        )
        val result = installer.install(second, layout, {})

        assertEquals(1, result.removed)
        assertFalse(File(layout.modsRoot, "DemoMod/legacy.dll").exists())
    }

    @Test
    fun `install keeps an upgrade of a disabled mod in disabled mods`() {
        val dir = File(layout.disabledModsRoot, "OffMod").apply { mkdirs() }
        File(dir, "OffMod.dll").writeBytes(byteArrayOf(1))
        File(dir, "OffMod.json").writeText("""{"id":"OffMod","version":"0.9.0"}""")

        val result = installer.install(zipOf("OffMod/OffMod.dll" to byteArrayOf(2)), layout, {})

        assertTrue(result.disabled)
        assertTrue(File(layout.disabledModsRoot, "OffMod/OffMod.dll").isFile)
        assertFalse(File(layout.modsRoot, "OffMod").exists())
    }

    @Test
    fun `a new mod declaring enabled false lands in disabled mods`() {
        val result = installer.install(
            zipOf(
                "QuietMod/QuietMod.dll" to byteArrayOf(1),
                "QuietMod/QuietMod.json" to """{"id":"QuietMod","version":"1.0.0","enabled":false}""".toByteArray(),
            ),
            layout,
            {},
        )

        assertTrue(result.disabled)
        assertTrue(File(layout.disabledModsRoot, "QuietMod/QuietMod.dll").isFile)
    }

    @Test
    fun `install rejects unsafe entry names`() {
        val zip = zipOf(
            "Evil/Evil.dll" to byteArrayOf(1),
            "Evil/../../escaped.txt" to byteArrayOf(2),
        )

        val result = installer.install(zip, layout, {})

        assertEquals(1, result.files)
        assertFalse(File(root.parentFile, "escaped.txt").exists())
    }

    @Test(expected = ModPackageException::class)
    fun `install fails when the archive has no dll`() {
        installer.install(zipOf("readme.txt" to byteArrayOf(1)), layout, {})
    }

    // ============================== 启停 ==============================

    @Test
    fun `disable moves the whole folder into disabled mods`() {
        val dir = File(layout.modsRoot, "DemoMod").apply { mkdirs() }
        File(dir, "DemoMod.dll").writeBytes(byteArrayOf(1))
        File(dir, "data").mkdirs()
        File(dir, "data/a.txt").writeText("x")

        val result = installer.setEnabled(layout, "DemoMod", "DemoMod", enabled = false, log = {})

        assertTrue(result.changed)
        assertFalse(File(layout.modsRoot, "DemoMod").exists())
        assertTrue(File(layout.disabledModsRoot, "DemoMod/DemoMod.dll").isFile)
        assertTrue(File(layout.disabledModsRoot, "DemoMod/data/a.txt").isFile)
    }

    @Test
    fun `enable moves it back`() {
        val dir = File(layout.disabledModsRoot, "DemoMod").apply { mkdirs() }
        File(dir, "DemoMod.dll").writeBytes(byteArrayOf(1))

        installer.setEnabled(layout, "DemoMod", "DemoMod", enabled = true, log = {})

        assertTrue(File(layout.modsRoot, "DemoMod/DemoMod.dll").isFile)
        assertFalse(File(layout.disabledModsRoot, "DemoMod").exists())
    }

    @Test
    fun `toggling to the state it already has is a no-op instead of an error`() {
        val dir = File(layout.modsRoot, "DemoMod").apply { mkdirs() }
        File(dir, "DemoMod.dll").writeBytes(byteArrayOf(1))

        val result = installer.setEnabled(layout, "DemoMod", "DemoMod", enabled = true, log = {})

        assertFalse(result.changed)
        assertTrue(File(layout.modsRoot, "DemoMod/DemoMod.dll").isFile)
    }

    @Test
    fun `missing mod fails with a readable message instead of a fake success`() {
        val failure = runCatching {
            installer.setEnabled(layout, "GhostMod", "GhostMod", enabled = false, log = {})
        }.exceptionOrNull()

        assertTrue(failure is ModPackageException)
        assertTrue(failure!!.message!!.contains("GhostMod"))
    }

    @Test
    fun `both copies present fails instead of nesting one into the other`() {
        File(layout.modsRoot, "DemoMod").mkdirs()
        File(layout.modsRoot, "DemoMod/DemoMod.dll").writeBytes(byteArrayOf(1))
        File(layout.disabledModsRoot, "DemoMod").mkdirs()
        File(layout.disabledModsRoot, "DemoMod/DemoMod.dll").writeBytes(byteArrayOf(1))

        val failure = runCatching {
            installer.setEnabled(layout, "DemoMod", "DemoMod", enabled = false, log = {})
        }.exceptionOrNull()

        assertTrue(failure is ModPackageException)
        // 不能变成 disabled_mods/DemoMod/DemoMod
        assertFalse(File(layout.disabledModsRoot, "DemoMod/DemoMod").exists())
    }

    @Test
    fun `flat legacy layout moves the dll together with its sidecar`() {
        File(layout.modsRoot, "OldMod.dll").writeBytes(byteArrayOf(1))
        File(layout.modsRoot, "OldMod.json").writeText("""{"id":"OldMod","version":"0.9.0"}""")

        installer.setEnabled(layout, "OldMod", null, enabled = false, log = {})

        assertTrue(File(layout.disabledModsRoot, "OldMod.dll").isFile)
        assertTrue(File(layout.disabledModsRoot, "OldMod.json").isFile)
        assertFalse(File(layout.modsRoot, "OldMod.dll").exists())
    }

    // ============================== 辅助 ==============================

    private fun zipOf(vararg files: Pair<String, ByteArray>): File {
        val zip = File(Files.createTempDirectory("cesium-zip").toFile(), "mod.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((name, bytes) in files) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return zip
    }

    /** 直连本地文件的 [LoaderFs]；语义与 Shizuku 版一致，只是不需要设备。 */
    private class LocalFs : LoaderFs {
        override fun isAvailable(): Boolean = true

        override fun listDirectories(path: String): List<String> =
            File(path).listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()

        override fun listFiles(path: String): List<String> =
            File(path).listFiles { f -> f.isFile }?.map { it.name }.orEmpty()

        override fun exists(path: String): Boolean = File(path).exists()

        override fun readText(path: String): String? =
            File(path).takeIf { it.isFile }?.readText(Charsets.UTF_8)

        override fun writeText(path: String, text: String) {
            File(path).parentFile?.mkdirs()
            File(path).writeText(text, Charsets.UTF_8)
        }

        override fun readBytes(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

        override fun move(from: String, to: String) {
            val source = File(from)
            val target = File(to)
            target.parentFile?.mkdirs()
            if (!source.renameTo(target)) {
                source.copyRecursively(target, overwrite = true)
                source.deleteRecursively()
            }
        }

        override fun delete(path: String) {
            File(path).deleteRecursively()
        }

        override fun makeDirectories(path: String) {
            File(path).mkdirs()
        }

        override fun fileSize(path: String): Long = File(path).takeIf { it.isFile }?.length() ?: 0L

        override fun copyIn(localFile: File, remotePath: String) {
            val target = File(remotePath)
            target.parentFile?.mkdirs()
            localFile.copyTo(target, overwrite = true)
        }

        override val lastError: String? get() = null
    }
}
