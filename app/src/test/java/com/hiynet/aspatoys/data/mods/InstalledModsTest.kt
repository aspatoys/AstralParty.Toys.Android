package com.hiynet.aspatoys.data.mods

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InstalledModsTest {
    private lateinit var root: File
    private lateinit var layout: ModLayout

    @Before
    fun setUp() {
        root = Files.createTempDirectory("aspatoys-installed").toFile()
        layout = ModLayout(File(root, "loader"))
        layout.modsRoot.mkdirs()
        layout.disabledModsRoot.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `scans new layout and reads sidecar`() {
        val folder = File(layout.modsRoot, "HandViewerMod").apply { mkdirs() }
        File(folder, "HandViewerMod.dll").writeBytes(byteArrayOf(1, 2, 3))
        File(folder, "HandViewerMod.json").writeText(
            """
            {
              // 注释也允许
              "id": "HandViewerMod",
              "name": "看牌",
              "version": "1.2.1",
              "sdkVersion": "2.8.6",
              "author": "hpenguins",
              "permissions": 2,
              "dependencies": [{"id": "BaseMod", "minVersion": "1.0.0"}]
            }
            """.trimIndent(),
        )

        val entry = InstalledMods.scan(layout).single()

        assertEquals("HandViewerMod", entry.name)
        assertEquals("看牌", entry.shownName)
        assertEquals("1.2.1", entry.version)
        assertEquals("2.8.6", entry.sdkVersion)
        assertTrue(entry.enabled)
        assertEquals(2, entry.permissions)
        assertEquals(listOf("BaseMod"), entry.dependencies.map { it.id })
        assertEquals(listOf("游戏内动作"), entry.permissionLabels)
    }

    @Test
    fun `scans legacy flat layout`() {
        File(layout.modsRoot, "OldMod.dll").writeBytes(byteArrayOf(1))
        File(layout.modsRoot, "OldMod.json").writeText("""{"id":"OldMod","version":"0.9.0"}""")

        val entry = InstalledMods.scan(layout).single()

        assertEquals("OldMod", entry.name)
        assertEquals(null, entry.directoryName)
        assertEquals("0.9.0", entry.version)
    }

    @Test
    fun `disabled folder entries come last and stay disabled`() {
        val enabledFolder = File(layout.modsRoot, "AMod").apply { mkdirs() }
        File(enabledFolder, "AMod.dll").writeBytes(byteArrayOf(1))
        val disabledFolder = File(layout.disabledModsRoot, "BMod").apply { mkdirs() }
        File(disabledFolder, "BMod.dll").writeBytes(byteArrayOf(1))

        val entries = InstalledMods.scan(layout)

        assertEquals(listOf("AMod", "BMod"), entries.map { it.name })
        assertTrue(entries[0].enabled)
        assertFalse(entries[1].enabled)
        assertTrue(entries[1].disabledFolder)
    }

    @Test
    fun `sidecar enabled false disables mod`() {
        val folder = File(layout.modsRoot, "ModX").apply { mkdirs() }
        File(folder, "ModX.dll").writeBytes(byteArrayOf(1))
        File(folder, "ModX.json").writeText("""{"id":"ModX","enabled":false}""")

        val entry = InstalledMods.scan(layout).single()

        assertFalse(entry.enabled)
    }

    @Test
    fun `diagnose reports sdk mismatch and missing dependency`() {
        val a = File(layout.modsRoot, "AMod").apply { mkdirs() }
        File(a, "AMod.dll").writeBytes(byteArrayOf(1))
        File(a, "AMod.json").writeText(
            """{"id":"AMod","version":"1.0.0","sdkVersion":"2.9.1","dependencies":[{"id":"MissingMod"}]}""",
        )

        val issues = InstalledMods.diagnose(InstalledMods.scan(layout), installedSdkVersion = "2.8.6")

        assertTrue(issues.any { it.level == ModIssue.Level.ERROR && it.message.contains("SDK") })
        assertTrue(issues.any { it.level == ModIssue.Level.WARNING && it.message.contains("MissingMod") })
    }

    @Test
    fun `diagnose reports duplicate ids and missing sidecar`() {
        for (name in listOf("OneMod", "TwoMod")) {
            val folder = File(layout.modsRoot, name).apply { mkdirs() }
            File(folder, "$name.dll").writeBytes(byteArrayOf(1))
        }
        File(layout.modsRoot, "OneMod/OneMod.json").writeText("""{"id":"SameId","version":"1.0.0"}""")
        File(layout.modsRoot, "TwoMod/TwoMod.json").writeText("""{"id":"SameId","version":"1.0.0"}""")

        val issues = InstalledMods.diagnose(InstalledMods.scan(layout), installedSdkVersion = null)

        assertTrue(issues.any { it.message.contains("同一个 id") })
        assertFalse(issues.isEmpty())
    }
}
