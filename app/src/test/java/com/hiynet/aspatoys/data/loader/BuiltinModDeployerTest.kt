package com.hiynet.aspatoys.data.loader

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 内置模组投放：位置必须沿用玩家原有状态（停用的继续停用），且覆盖安装不重置本地配置。
 *
 * 这些用例走的是生产路径 [BuiltinModDeployer]（内部 [ModStateStore]），不是孤立的策略对象。
 */
class BuiltinModDeployerTest {

    private val root = "/game/CesiumLoader"
    private val assets = mapOf(
        "cesium/mods/AlphaMod/AlphaMod.dll" to byteArrayOf(1),
        "cesium/mods/AlphaMod/AlphaMod.json" to "{\"version\":\"1.3.1\"}".toByteArray(),
        "cesium/mods/AlphaMod/skins/new.png" to byteArrayOf(9),
        "cesium/mods/BetaMod/BetaMod.dll" to byteArrayOf(2),
        "cesium/mods/BetaMod/BetaMod.json" to "{}".toByteArray(),
        "cesium/mods/LanguageUnlockMod/LanguageUnlockMod.dll" to byteArrayOf(3),
        "cesium/mods/CesiumOverlayMod/CesiumOverlayMod.dll" to byteArrayOf(4),
        "cesium/mods/SpeedHackMod/SpeedHackMod.dll" to byteArrayOf(5),
        "cesium/mods/FreeCameraMod/FreeCameraMod.dll" to byteArrayOf(6),
    )

    private fun deployer(fs: InMemoryLoaderFs) = BuiltinModDeployer(
        fs,
        { dir -> assets.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/").substringBefore('/') }.distinct() },
        { assets.getValue(it) },
    )

    @Test
    fun `fresh install lands in mods and skips the overlay trio`() {
        val fs = InMemoryLoaderFs()
        val outcome = deployer(fs).deploy(root)

        assertEquals(listOf("AlphaMod", "BetaMod"), outcome.modIds)
        assertTrue(outcome.disabled.isEmpty())
        assertArrayEquals(byteArrayOf(1), fs.files["$root/mods/AlphaMod/AlphaMod.dll"])
        assertArrayEquals(byteArrayOf(9), fs.files["$root/mods/AlphaMod/skins/new.png"])
        assertFalse(fs.exists("$root/mods/CesiumOverlayMod/CesiumOverlayMod.dll"))
        assertFalse(fs.exists("$root/mods/LanguageUnlockMod/LanguageUnlockMod.dll"))
    }

    @Test
    fun `previously disabled mod stays disabled and is not copied into mods`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/disabled_mods/AlphaMod/AlphaMod.dll"] = byteArrayOf(0)
        val outcome = deployer(fs).deploy(root)

        assertTrue(outcome.disabled.contains("AlphaMod"))
        assertArrayEquals(byteArrayOf(1), fs.files["$root/disabled_mods/AlphaMod/AlphaMod.dll"])
        assertFalse(fs.exists("$root/mods/AlphaMod/AlphaMod.dll"))
        assertFalse(fs.exists("$root/mods/AlphaMod"))
    }

    @Test
    fun `flat disabled dll keeps it disabled and is retired out of the scan`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/disabled_mods/AlphaMod.dll"] = byteArrayOf(7)
        fs.files["$root/disabled_mods/AlphaMod.json"] = "{}".toByteArray()
        deployer(fs).deploy(root)

        assertFalse(fs.exists("$root/disabled_mods/AlphaMod.dll"))
        assertFalse(fs.exists("$root/disabled_mods/AlphaMod.json"))
        assertArrayEquals(byteArrayOf(7), fs.files["$root/disabled_mods/AlphaMod.dll.pre-toys"])
        assertArrayEquals(byteArrayOf(1), fs.files["$root/disabled_mods/AlphaMod/AlphaMod.dll"])
        assertFalse(fs.exists("$root/mods/AlphaMod"))
    }

    @Test
    fun `duplicate copies prefer the disabled one and keep one scannable body`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/mods/AlphaMod/AlphaMod.dll"] = byteArrayOf(0)
        fs.files["$root/mods/AlphaMod/config.json"] = "{\"volume\":1}".toByteArray()
        fs.files["$root/disabled_mods/AlphaMod/AlphaMod.dll"] = byteArrayOf(0)
        fs.files["$root/disabled_mods/AlphaMod/extra/skin.png"] = byteArrayOf(3)
        deployer(fs).deploy(root)

        assertFalse(fs.exists("$root/mods/AlphaMod"))
        assertArrayEquals(byteArrayOf(1), fs.files["$root/disabled_mods/AlphaMod/AlphaMod.dll"])
        assertArrayEquals(byteArrayOf(3), fs.files["$root/disabled_mods/AlphaMod/extra/skin.png"])
        assertArrayEquals("{\"volume\":1}".toByteArray(), fs.files["$root/disabled_mods/AlphaMod/config.json"])
        assertTrue(fs.exists("$root/backups/mod-state/AlphaMod"))
        assertFalse(fs.exists("$root/disabled_mods/AlphaMod/AlphaMod"))
    }

    @Test
    fun `overwriting keeps local config and player-only assets`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/mods/AlphaMod/AlphaMod.dll"] = byteArrayOf(0)
        fs.files["$root/mods/AlphaMod/config.json"] = "{\"volume\":5}".toByteArray()
        fs.files["$root/mods/AlphaMod/mod.cfg"] = "player".toByteArray()
        fs.files["$root/mods/AlphaMod/skins/mine.png"] = byteArrayOf(4)
        fs.files["$root/configs/AlphaMod.json"] = "central".toByteArray()
        deployer(fs).deploy(root)

        assertArrayEquals(byteArrayOf(1), fs.files["$root/mods/AlphaMod/AlphaMod.dll"])
        assertArrayEquals("{\"volume\":5}".toByteArray(), fs.files["$root/mods/AlphaMod/config.json"])
        assertArrayEquals("player".toByteArray(), fs.files["$root/mods/AlphaMod/mod.cfg"])
        assertArrayEquals(byteArrayOf(4), fs.files["$root/mods/AlphaMod/skins/mine.png"])
        assertArrayEquals("central".toByteArray(), fs.files["$root/configs/AlphaMod.json"])
    }

    @Test
    fun `an empty mods directory does not re-enable a disabled mod`() {
        val fs = InMemoryLoaderFs()
        fs.makeDirectories("$root/mods/AlphaMod")
        fs.files["$root/disabled_mods/AlphaMod/AlphaMod.dll"] = byteArrayOf(0)
        deployer(fs).deploy(root)

        assertArrayEquals(byteArrayOf(1), fs.files["$root/disabled_mods/AlphaMod/AlphaMod.dll"])
        assertFalse(fs.exists("$root/mods/AlphaMod/AlphaMod.dll"))
        assertFalse(fs.exists("$root/mods/AlphaMod"))
    }
}