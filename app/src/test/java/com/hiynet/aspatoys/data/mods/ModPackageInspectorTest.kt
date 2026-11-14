package com.hiynet.aspatoys.data.mods

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 模组包预检的行为测试：结构识别、版本/降级、保留玩家配置与停用状态、旧文件清理、
 * SDK 兼容性、以及各种不合法压缩包的拒绝路径。
 */
class ModPackageInspectorTest {
    private lateinit var root: File
    private lateinit var layout: ModLayout
    private lateinit var inspector: ModPackageInspector

    @Before
    fun setUp() {
        root = Files.createTempDirectory("aspatoys-inspect").toFile()
        layout = ModLayout(File(root, "AstralParty_ModLoader"))
        layout.modsRoot.mkdirs()
        layout.disabledModsRoot.mkdirs()
        inspector = ModPackageInspector(layout)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ============================== 结构识别 ==============================

    @Test
    fun `reads single mod with folder layout`() {
        val zip = zip(
            "TestMod/TestMod.dll" to managedDll(),
            "TestMod/TestMod.json" to sidecar("TestMod", "测试模组", "1.2.0", "2.9.1"),
            "TestMod/config.json" to """{"speed": 2}""".toByteArray(),
            "TestMod/readme.txt" to "hello".toByteArray(),
        )

        val inspection = inspector.inspect(zip)

        assertEquals(1, inspection.mods.size)
        val mod = inspection.mods.single()
        assertEquals("TestMod", mod.modId)
        assertEquals("测试模组", mod.name)
        assertEquals("1.2.0", mod.packageVersion)
        assertEquals("2.9.1", mod.sdkVersion)
        assertEquals("TestMod", mod.folderName)
        assertFalse(mod.installed)
        assertTrue(mod.dllLooksManaged)
        assertEquals(4, mod.files.size)
        assertTrue(inspection.skipped.isEmpty())
        assertEquals(4, inspection.fileCount)
        assertEquals(64, inspection.sha256.length)
    }

    @Test
    fun `wraps flat root dll into mod folder`() {
        val zip = zip(
            "FlatMod.dll" to managedDll(),
            "FlatMod.json" to sidecar("FlatMod", "平铺模组", "0.3.0", ""),
        )

        val inspection = inspector.inspect(zip)

        val mod = inspection.mods.single()
        assertEquals("FlatMod", mod.modId)
        assertEquals("0.3.0", mod.packageVersion)
        assertEquals(listOf("FlatMod.dll", "FlatMod.json"), mod.files.sorted())
    }

    @Test
    fun `accepts package with wrapper folder`() {
        val zip = zip(
            "release/ModA/ModA.dll" to managedDll(),
            "release/ModA/ModA.json" to sidecar("ModA", "A", "1.0.0", ""),
            "release/README.md" to "docs".toByteArray(),
        )

        val inspection = inspector.inspect(zip)

        assertEquals(1, inspection.mods.size)
        assertEquals("ModA", inspection.mods.single().modId)
        assertTrue(inspection.skipped.contains("release/README.md"))
    }

    @Test
    fun `handles multiple mods in one package`() {
        val zip = zip(
            "ModA/ModA.dll" to managedDll(),
            "ModB/ModB.dll" to managedDll(),
            "ModB/ModB.json" to sidecar("ModB", "B", "2.0.0", ""),
        )

        val inspection = inspector.inspect(zip)

        assertEquals(listOf("ModA", "ModB"), inspection.mods.map { it.modId })
    }

    // ============================== 拒绝路径 ==============================

    @Test
    fun `rejects loader package`() {
        val zip = zip("version.dll" to byteArrayOf(1, 2, 3))
        expectFailure(zip, "加载器发布包")
    }

    @Test
    fun `rejects sdk package`() {
        val zip = zip("CesiumLoader.SDK.dll" to byteArrayOf(1, 2, 3))
        expectFailure(zip, "SDK 包")
    }

    @Test
    fun `rejects package without mod structure`() {
        val zip = zip("docs/readme.txt" to "hi".toByteArray())
        expectFailure(zip, "没有找到模组")
    }

    @Test
    fun `rejects unsafe paths`() {
        val zip = zip("../evil.dll" to managedDll())
        expectFailure(zip, "不安全的路径")
    }

    @Test
    fun `rejects non zip file`() {
        val file = File(root, "TestMod.txt").apply { writeText("nope") }
        try {
            inspector.inspect(file)
            fail("应当拒绝非 zip 文件")
        } catch (expected: ModPackageException) {
            assertTrue(expected.message!!.contains(".zip"))
        }
    }

    @Test
    fun `rejects duplicate mod ids`() {
        val zip = zip(
            "a/TestMod/TestMod.dll" to managedDll(),
            "b/TestMod/TestMod.dll" to managedDll(),
        )
        expectFailure(zip, "同名模组")
    }

    // ============================== 已装状态 ==============================

    @Test
    fun `detects downgrade against installed version`() {
        installMod("TestMod", "2.0.0")
        val zip = zip(
            "TestMod/TestMod.dll" to managedDll(),
            "TestMod/TestMod.json" to sidecar("TestMod", "测试模组", "1.2.0", ""),
        )

        val mod = inspector.inspect(zip).mods.single()

        assertTrue(mod.installed)
        assertEquals("2.0.0", mod.installedVersion)
        assertTrue(mod.isDowngrade)
        assertFalse(mod.isUpgrade)
    }

    @Test
    fun `detects upgrade and preserves player config`() {
        installMod("TestMod", "1.0.0", config = """{"speed": 3}""")
        val zip = zip(
            "TestMod/TestMod.dll" to managedDll(),
            "TestMod/TestMod.json" to sidecar("TestMod", "测试模组", "1.1.0", ""),
            "TestMod/config.json" to """{"speed": 1}""".toByteArray(),
        )

        val mod = inspector.inspect(zip).mods.single()

        assertTrue(mod.isUpgrade)
        assertTrue(mod.preservesConfig)
        assertFalse(mod.preservesDisabled)
    }

    @Test
    fun `keeps disabled state and disabled folder`() {
        installMod("TestMod", "1.0.0", disabled = true)
        val zip = zip(
            "TestMod/TestMod.dll" to managedDll(),
            "TestMod/TestMod.json" to sidecar("TestMod", "测试模组", "1.0.1", ""),
        )

        val mod = inspector.inspect(zip).mods.single()

        assertTrue(mod.installed)
        assertTrue(mod.preservesDisabled)
        assertEquals("TestMod", mod.folderName)
        assertEquals(layout.disabledModsRoot.path, mod.containerRoot)
    }

    @Test
    fun `reports stale files from manifest and keeps unlisted player files`() {
        installMod("TestMod", "1.0.0")
        // 上次导入写过 old.dll + resource.png；玩家后来自己放了 notes.txt
        File(layout.modsRoot, "TestMod/old.dll").writeBytes(byteArrayOf(1))
        File(layout.modsRoot, "TestMod/resource.png").writeBytes(byteArrayOf(2))
        File(layout.modsRoot, "TestMod/notes.txt").writeText("mine")
        writeManifest("TestMod", listOf("TestMod.dll", "TestMod.json", "old.dll", "resource.png"))

        val zip = zip(
            "TestMod/TestMod.dll" to managedDll(),
            "TestMod/TestMod.json" to sidecar("TestMod", "测试模组", "1.1.0", ""),
        )

        val mod = inspector.inspect(zip).mods.single()

        assertEquals(listOf("old.dll", "resource.png"), mod.removedFiles.sorted())
        assertFalse(mod.removedFiles.contains("notes.txt"))
        assertFalse(mod.removedFiles.contains("config.json"))
    }

    @Test
    fun `falls back to artifact extensions without manifest`() {
        installMod("TestMod", "1.0.0")
        File(layout.modsRoot, "TestMod/previous.pdb").writeBytes(byteArrayOf(1))
        File(layout.modsRoot, "TestMod/art.png").writeBytes(byteArrayOf(2))

        val zip = zip(
            "TestMod/TestMod.dll" to managedDll(),
            "TestMod/TestMod.json" to sidecar("TestMod", "测试模组", "1.1.0", ""),
        )

        val mod = inspector.inspect(zip).mods.single()

        assertEquals(listOf("previous.pdb"), mod.removedFiles)
    }

    // ============================== SDK ==============================

    @Test
    fun `flags sdk newer than installed loader`() {
        writeLoaderConfig("2.8.6")
        val zip = zip(
            "TestMod/TestMod.dll" to managedDll(),
            "TestMod/TestMod.json" to sidecar("TestMod", "测试模组", "1.0.0", "2.9.1"),
        )

        val mod = inspector.inspect(zip).mods.single()

        assertEquals("2.8.6", mod.installedSdkVersion)
        assertTrue(mod.sdkBlocked)
    }

    @Test
    fun `does not flag sdk when loader version unknown`() {
        val zip = zip(
            "TestMod/TestMod.dll" to managedDll(),
            "TestMod/TestMod.json" to sidecar("TestMod", "测试模组", "1.0.0", "9.9.9"),
        )

        val mod = inspector.inspect(zip).mods.single()

        assertEquals("", mod.installedSdkVersion)
        assertFalse(mod.sdkBlocked)
    }

    @Test
    fun `detects non managed dll`() {
        val zip = zip(
            "BadMod/BadMod.dll" to "not a dll".toByteArray(),
            "BadMod/BadMod.json" to sidecar("BadMod", "坏模组", "1.0.0", ""),
        )

        val mod = inspector.inspect(zip).mods.single()

        assertFalse(mod.dllLooksManaged)
    }

    // ============================== 辅助 ==============================

    private fun expectFailure(zip: File, messagePart: String) {
        try {
            inspector.inspect(zip)
            fail("应当抛出 ModPackageException")
        } catch (expected: ModPackageException) {
            assertNotNull(expected.message)
            assertTrue(
                "消息里应包含「$messagePart」，实际是：${expected.message}",
                expected.message!!.contains(messagePart),
            )
        }
    }

    private fun installMod(id: String, version: String, config: String? = null, disabled: Boolean = false) {
        val rootDir = if (disabled) layout.disabledModsRoot else layout.modsRoot
        val folder = File(rootDir, id).apply { mkdirs() }
        File(folder, "$id.dll").writeBytes(managedDll())
        File(folder, "$id.json").writeBytes(sidecar(id, id, version, ""))
        if (config != null) File(folder, "config.json").writeText(config)
    }

    private fun writeManifest(id: String, files: List<String>) {
        val dir = File(layout.manifestRoot, "packages").apply { mkdirs() }
        File(dir, "$id.json").writeText("""{"files":[${files.joinToString(",") { "\"$it\"" }}]}""")
    }

    private fun writeLoaderConfig(sdkVersion: String) {
        layout.loaderRoot.mkdirs()
        layout.configFile.writeText("""{"sdkVersion":"$sdkVersion"}""")
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = File(root, "package-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { output ->
            for ((name, bytes) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }

    private fun managedDll(): ByteArray {
        val header = byteArrayOf('M'.code.toByte(), 'Z'.code.toByte())
        val payload = ByteArray(64)
        return header + payload + "BSJB".toByteArray() + ByteArray(32)
    }

    private fun sidecar(id: String, name: String, version: String, sdkVersion: String): ByteArray = """
        {
          // 模组元数据
          "id": "$id",
          "name": "$name",
          "version": "$version",
          "sdkVersion": "$sdkVersion",
          "author": "tester",
          "permissions": 1
        }
    """.trimIndent().toByteArray()
}
