package com.hiynet.aspatoys.data.loader

import com.hiynet.aspatoys.data.settings.ThemeMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BuiltinOverlayTest {
    private val root = "/game/CesiumLoader"
    private val id = BuiltinOverlay.ID
    private val speedId = BuiltinOverlay.SPEED_ID
    private val cameraId = BuiltinOverlay.CAMERA_ID
    private val assets = mapOf(
        "cesium/CesiumAndroidApi.dll" to byteArrayOf(1, 2),
        "cesium/mods/$id/$id.dll" to byteArrayOf(3, 4),
        "cesium/mods/$id/$id.json" to "{}".toByteArray(),
        "cesium/mods/$id/licenses/MIT.txt" to "MIT".toByteArray(),
        "cesium/mods/$speedId/$speedId.dll" to byteArrayOf(5, 6),
        "cesium/mods/$speedId/$speedId.json" to "{\"version\":\"2.5.5-android-panel\"}".toByteArray(),
        "cesium/mods/$cameraId/$cameraId.dll" to byteArrayOf(11, 12),
        "cesium/mods/$cameraId/$cameraId.json" to "{\"version\":\"2.7.2-android-pinch\"}".toByteArray(),
        "cesium/mods/$cameraId/licenses/AGPL.txt" to "AGPL".toByteArray(),
    )
    private fun overlay(fs: InMemoryLoaderFs) = BuiltinOverlay(fs, { dir ->
        assets.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/").substringBefore('/') }.distinct()
    }, { assets.getValue(it) })

    @Test fun `installs api assets and theme without changing sdk or player config`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = byteArrayOf(9)
        fs.files["$root/configs/SpeedHackMod.json"] = "player".toByteArray()
        overlay(fs).install(root, ThemeMode.LIGHT, true)
        assertArrayEquals(byteArrayOf(1, 2), fs.files["$root/CesiumAndroidApi.dll"])
        assertArrayEquals(byteArrayOf(3, 4), fs.files["$root/mods/$id/$id.dll"])
        assertEquals("MIT", fs.readText("$root/mods/$id/licenses/MIT.txt"))
        assertArrayEquals(byteArrayOf(11, 12), fs.files["$root/mods/$cameraId/$cameraId.dll"])
        assertEquals("AGPL", fs.readText("$root/mods/$cameraId/licenses/AGPL.txt"))
        assertEquals("light\nlight\n", fs.readText("$root/data/toys-appearance.txt"))
        assertArrayEquals(byteArrayOf(9), fs.files["$root/sdk/CesiumLoader.SDK.dll"])
        assertEquals("player", fs.readText("$root/configs/SpeedHackMod.json"))
    }

    @Test fun `updating preserves disabled state`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = byteArrayOf(9)
        fs.files["$root/disabled_mods/$id/$id.dll"] = byteArrayOf(0)
        overlay(fs).install(root, ThemeMode.SYSTEM, true)
        assertTrue(fs.exists("$root/disabled_mods/$id/$id.dll"))
        assertFalse(fs.exists("$root/mods/$id/$id.dll"))
        assertEquals("system\ndark\n", fs.readText("$root/data/toys-appearance.txt"))
    }

    @Test fun `missing loader refuses deployment before writing anything`() {
        val fs = InMemoryLoaderFs()
        assertTrue(runCatching { overlay(fs).install(root, ThemeMode.DARK, false) }.isFailure)
        assertTrue(fs.files.isEmpty())
    }

    @Test fun `legacy direct installer dll is backed up and not loaded twice`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/CesiumLoader.SDK.dll"] = byteArrayOf(9)
        fs.files["$root/disabled_mods/$id.dll"] = byteArrayOf(0)
        fs.directories.add("$root/mods/$id") // Old direct installer left config directories here.
        overlay(fs).install(root, ThemeMode.SYSTEM, true)
        assertFalse(fs.exists("$root/disabled_mods/$id.dll"))
        assertTrue(fs.exists("$root/disabled_mods/$id.dll.pre-toys"))
        assertArrayEquals(byteArrayOf(3, 4), fs.files["$root/disabled_mods/$id/$id.dll"])
        assertFalse(fs.exists("$root/mods/$id/$id.dll"))
    }

    @Test fun `theme sync skips absent mod or unavailable privilege and avoids duplicate writes`() {
        val fs = InMemoryLoaderFs()
        val overlay = overlay(fs)
        overlay.syncAppearance(root, ThemeMode.DARK, false)
        assertTrue(fs.files.isEmpty())
        fs.files["$root/mods/$id/$id.dll"] = byteArrayOf(3)
        fs.available = false
        overlay.syncAppearance(root, ThemeMode.DARK, false)
        assertFalse(fs.exists("$root/data/toys-appearance.txt"))
        fs.available = true
        overlay.syncAppearance(root, ThemeMode.SYSTEM, false)
        assertEquals("system\nlight\n", fs.readText("$root/data/toys-appearance.txt"))
        val writes = fs.writes
        overlay.syncAppearance(root, ThemeMode.SYSTEM, false)
        assertEquals(writes, fs.writes)
        overlay.syncAppearance(root, ThemeMode.DARK, false)
        assertEquals("dark\ndark\n", fs.readText("$root/data/toys-appearance.txt"))
    }

    @Test fun `installs all three mods and leaves the player configs untouched`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = byteArrayOf(9)
        fs.files["$root/configs/SpeedHackMod.json"] = "player-speed-10".toByteArray()
        fs.files["$root/configs/FreeCameraMod.json"] = "player-camera".toByteArray()
        overlay(fs).install(root, ThemeMode.DARK, true)
        assertArrayEquals(byteArrayOf(5, 6), fs.files["$root/mods/$speedId/$speedId.dll"])
        assertEquals(
            "{\"version\":\"2.5.5-android-panel\"}",
            fs.readText("$root/mods/$speedId/$speedId.json"),
        )
        assertArrayEquals(byteArrayOf(11, 12), fs.files["$root/mods/$cameraId/$cameraId.dll"])
        assertEquals(
            "{\"version\":\"2.7.2-android-pinch\"}",
            fs.readText("$root/mods/$cameraId/$cameraId.json"),
        )
        assertArrayEquals("player-speed-10".toByteArray(), fs.files["$root/configs/SpeedHackMod.json"])
        assertArrayEquals("player-camera".toByteArray(), fs.files["$root/configs/FreeCameraMod.json"])
    }

    @Test fun `updating a disabled camera keeps it disabled and preserves its config`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = byteArrayOf(9)
        fs.files["$root/disabled_mods/$cameraId/$cameraId.dll"] = byteArrayOf(0)
        fs.files["$root/configs/FreeCameraMod.json"] = "player-camera".toByteArray()
        overlay(fs).install(root, ThemeMode.LIGHT, true)
        assertArrayEquals(byteArrayOf(11, 12), fs.files["$root/disabled_mods/$cameraId/$cameraId.dll"])
        assertFalse(fs.exists("$root/mods/$cameraId/$cameraId.dll"))
        assertArrayEquals("player-camera".toByteArray(), fs.files["$root/configs/FreeCameraMod.json"])
    }

    @Test fun `old nested camera dll is backed up once as pre panel`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = byteArrayOf(9)
        fs.files["$root/mods/$cameraId/$cameraId.dll"] = byteArrayOf(7)
        overlay(fs).install(root, ThemeMode.SYSTEM, true)
        assertArrayEquals(byteArrayOf(7), fs.files["$root/mods/$cameraId/$cameraId.dll.pre-panel"])
        assertArrayEquals(byteArrayOf(11, 12), fs.files["$root/mods/$cameraId/$cameraId.dll"])
        // A second update must not overwrite the original backup.
        overlay(fs).install(root, ThemeMode.SYSTEM, true)
        assertArrayEquals(byteArrayOf(7), fs.files["$root/mods/$cameraId/$cameraId.dll.pre-panel"])
    }

    @Test fun `flat direct camera dll is moved out of the loader scan`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = byteArrayOf(9)
        fs.files["$root/mods/$cameraId.dll"] = byteArrayOf(8)
        overlay(fs).install(root, ThemeMode.DARK, true)
        assertFalse(fs.exists("$root/mods/$cameraId.dll"))
        assertArrayEquals(byteArrayOf(8), fs.files["$root/mods/$cameraId.dll.pre-toys"])
        assertArrayEquals(byteArrayOf(11, 12), fs.files["$root/mods/$cameraId/$cameraId.dll"])
        assertEquals(
            "{\"version\":\"2.7.2-android-pinch\"}",
            fs.readText("$root/mods/$cameraId/$cameraId.json"),
        )
    }

    @Test fun `flat direct disabled camera dll is moved out and stays disabled`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = byteArrayOf(9)
        fs.files["$root/disabled_mods/$cameraId.dll"] = byteArrayOf(8)
        overlay(fs).install(root, ThemeMode.SYSTEM, true)
        assertFalse(fs.exists("$root/disabled_mods/$cameraId.dll"))
        assertArrayEquals(byteArrayOf(8), fs.files["$root/disabled_mods/$cameraId.dll.pre-toys"])
        assertArrayEquals(byteArrayOf(11, 12), fs.files["$root/disabled_mods/$cameraId/$cameraId.dll"])
        assertFalse(fs.exists("$root/mods/$cameraId/$cameraId.dll"))
    }

    @Test fun `updating a disabled speed mod keeps it disabled`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = byteArrayOf(9)
        fs.files["$root/disabled_mods/$speedId/$speedId.dll"] = byteArrayOf(0)
        overlay(fs).install(root, ThemeMode.LIGHT, false)
        assertArrayEquals(byteArrayOf(5, 6), fs.files["$root/disabled_mods/$speedId/$speedId.dll"])
        assertFalse(fs.exists("$root/mods/$speedId/$speedId.dll"))
    }

    @Test fun `old nested speed dll is backed up once as pre panel`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = byteArrayOf(9)
        fs.files["$root/mods/$speedId/$speedId.dll"] = byteArrayOf(7)
        overlay(fs).install(root, ThemeMode.SYSTEM, true)
        assertArrayEquals(byteArrayOf(7), fs.files["$root/mods/$speedId/$speedId.dll.pre-panel"])
        assertArrayEquals(byteArrayOf(5, 6), fs.files["$root/mods/$speedId/$speedId.dll"])
        // A second update must not overwrite the original backup.
        overlay(fs).install(root, ThemeMode.SYSTEM, true)
        assertArrayEquals(byteArrayOf(7), fs.files["$root/mods/$speedId/$speedId.dll.pre-panel"])
    }

    @Test fun `flat direct speed dll is moved out of the loader scan`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = byteArrayOf(9)
        fs.files["$root/mods/$speedId.dll"] = byteArrayOf(8)
        overlay(fs).install(root, ThemeMode.DARK, true)
        assertFalse(fs.exists("$root/mods/$speedId.dll"))
        assertArrayEquals(byteArrayOf(8), fs.files["$root/mods/$speedId.dll.pre-toys"])
        assertArrayEquals(byteArrayOf(5, 6), fs.files["$root/mods/$speedId/$speedId.dll"])
    }

    @Test fun `missing speed asset refuses deployment before writing anything`() {
        val fs = InMemoryLoaderFs()
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = byteArrayOf(9)
        val incomplete = BuiltinOverlay(fs, { dir ->
            if (dir == "cesium/mods/$speedId") listOf("SpeedHackMod.json")
            else assets.keys.filter { it.startsWith("$dir/") }
                .map { it.removePrefix("$dir/").substringBefore('/') }.distinct()
        }, { assets.getValue(it) })
        assertTrue(runCatching { incomplete.install(root, ThemeMode.DARK, true) }.isFailure)
        // Only the pre-existing SDK file: preflight must fail before any copy.
        assertEquals(1, fs.files.size)
        assertFalse(fs.exists("$root/CesiumAndroidApi.dll"))
        assertFalse(fs.exists("$root/mods/$id/$id.dll"))
    }

}