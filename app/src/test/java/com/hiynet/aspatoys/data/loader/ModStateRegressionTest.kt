package com.hiynet.aspatoys.data.loader

import com.hiynet.aspatoys.data.mods.ModLayout
import com.hiynet.aspatoys.data.settings.ThemeMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Integration boundaries missed by fixtures without bundled default configurations. */
class ModStateRegressionTest {
    private val root = "/game/CesiumLoader"
    private val id = "DemoMod"
    private fun deployer(fs: LoaderFs, assets: Map<String, String>) = BuiltinModDeployer(fs,
        { dir -> assets.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/").substringBefore('/') }.distinct() },
        { assets.getValue(it).toByteArray() })
    private val payload = mapOf("cesium/mods/$id/$id.dll" to "new-dll")

    @Test fun `reinstall into a disabled configuration directory stays disabled`() {
        val fs = InMemoryLoaderFs()
        fs.writeText("$root/disabled_mods/$id/config.json", "local")
        val outcome = deployer(fs, payload).deploy(root)
        assertEquals(listOf(id), outcome.disabled)
        assertEquals("local", fs.readText("$root/disabled_mods/$id/config.json"))
        assertFalse(fs.exists("$root/mods/$id"))
    }

    @Test fun `an existing empty disabled directory retains the installation destination`() {
        val fs = InMemoryLoaderFs()
        fs.makeDirectories("$root/disabled_mods/$id")
        val outcome = deployer(fs, payload).deploy(root)
        assertEquals(listOf(id), outcome.disabled)
        assertEquals("new-dll", fs.readText("$root/disabled_mods/$id/$id.dll"))
        assertFalse(fs.exists("$root/mods/$id"))
    }

    @Test fun `bundled defaults never replace existing player configuration`() {
        val fs = InMemoryLoaderFs()
        fs.writeText("$root/disabled_mods/$id/$id.dll", "old-dll")
        fs.writeText("$root/disabled_mods/$id/config.json", "player-json")
        fs.writeText("$root/disabled_mods/$id/mod.cfg", "player-cfg")
        fs.writeText("$root/configs/$id.json", "central")
        deployer(fs, payload + mapOf("cesium/mods/$id/config.json" to "default-json", "cesium/mods/$id/mod.cfg" to "default-cfg")).deploy(root)
        assertEquals("player-json", fs.readText("$root/disabled_mods/$id/config.json"))
        assertEquals("player-cfg", fs.readText("$root/disabled_mods/$id/mod.cfg"))
        assertEquals("central", fs.readText("$root/configs/$id.json"))
    }

    @Test fun `configuration only active directory does not reactivate a disabled flat mod`() {
        val fs = InMemoryLoaderFs()
        fs.writeText("$root/mods/$id/config.json", "local")
        fs.writeText("$root/disabled_mods/$id.dll", "old-dll")
        val result = deployer(fs, payload).deploy(root)
        assertEquals(listOf(id), result.disabled)
        assertFalse(fs.exists("$root/mods/$id"))
        assertEquals("new-dll", fs.readText("$root/disabled_mods/$id/$id.dll"))
        assertEquals("local", fs.readText("$root/disabled_mods/$id/config.json"))
    }

    @Test fun `configuration only disabled directory does not disable an active flat mod`() {
        val fs = InMemoryLoaderFs()
        fs.writeText("$root/disabled_mods/$id/config.json", "local")
        fs.writeText("$root/mods/$id.dll", "old-dll")
        val result = deployer(fs, payload).deploy(root)
        assertTrue(result.disabled.isEmpty())
        assertFalse(fs.exists("$root/disabled_mods/$id"))
        assertEquals("new-dll", fs.readText("$root/mods/$id/$id.dll"))
        assertEquals("local", fs.readText("$root/mods/$id/config.json"))
    }

    @Test fun `enabling a flat mod with configuration directories keeps its body`() {
        val fs = InMemoryLoaderFs()
        fs.writeText("$root/mods/$id/config.json", "local")
        fs.writeText("$root/disabled_mods/$id.dll", "old-dll")
        ModStateStore(fs).setEnabled(root, id, id, true, {})
        assertEquals("old-dll", fs.readText("$root/mods/$id/$id.dll"))
        assertEquals("local", fs.readText("$root/mods/$id/config.json"))
        assertFalse(fs.exists("$root/disabled_mods/$id.dll"))
    }

    @Test fun `configuration without any dll cannot report successful activation`() {
        val fs = InMemoryLoaderFs()
        fs.writeText("$root/disabled_mods/$id/config.json", "local")
        assertTrue(runCatching { ModStateStore(fs).setEnabled(root, id, id, true, {}) }.isFailure)
        assertEquals("local", fs.readText("$root/disabled_mods/$id/config.json"))
    }

    @Test fun `failed partial copy restores the destination and preserves the source`() {
        val memory = InMemoryLoaderFs()
        memory.writeText("$root/mods/$id/$id.dll", "active")
        memory.writeText("$root/mods/$id/a.txt", "a")
        memory.writeText("$root/mods/$id/b.txt", "b")
        memory.writeText("$root/disabled_mods/$id/$id.dll", "disabled")
        val fs = object : LoaderFs by memory {
            override fun copyIn(localFile: File, remotePath: String) {
                memory.copyIn(localFile, remotePath)
                if (remotePath.endsWith("b.txt")) error("injected partial copy failure")
            }
        }
        assertTrue(runCatching { ModStateStore(fs).setEnabled(root, id, id, false, {}) }.isFailure)
        assertEquals("active", memory.readText("$root/mods/$id/$id.dll"))
        assertEquals("a", memory.readText("$root/mods/$id/a.txt"))
        assertEquals("b", memory.readText("$root/mods/$id/b.txt"))
        assertEquals("disabled", memory.readText("$root/disabled_mods/$id/$id.dll"))
        assertFalse(memory.exists("$root/disabled_mods/$id/a.txt"))
        assertFalse(memory.exists("$root/disabled_mods/$id/b.txt"))
    }

    @Test fun `retiring a flat mod never overwrites an older sidecar backup`() {
        val fs = InMemoryLoaderFs()
        fs.writeText("$root/disabled_mods/$id.dll", "old-dll")
        fs.writeText("$root/disabled_mods/$id.json", "current-sidecar")
        fs.writeText("$root/disabled_mods/$id.json.pre-toys", "previous-sidecar")
        deployer(fs, payload).deploy(root)
        assertEquals("previous-sidecar", fs.readText("$root/disabled_mods/$id.json.pre-toys"))
        assertTrue(fs.files.values.any { it.toString(Charsets.UTF_8) == "current-sidecar" })
    }

    @Test fun `android overlay deployment preserves bundled default configurations too`() {
        val fs = InMemoryLoaderFs()
        val overlayId = BuiltinOverlay.ID
        fs.writeText("$root/sdk/CesiumLoader.SDK.dll", "sdk")
        fs.writeText("$root/mods/$overlayId/$overlayId.dll", "old")
        fs.writeText("$root/mods/$overlayId/config.json", "player")
        val assets = mapOf("cesium/CesiumAndroidApi.dll" to "api",
            "cesium/mods/$overlayId/$overlayId.dll" to "new",
            "cesium/mods/$overlayId/config.json" to "default",
            "cesium/mods/${BuiltinOverlay.SPEED_ID}/${BuiltinOverlay.SPEED_ID}.dll" to "speed",
            "cesium/mods/${BuiltinOverlay.CAMERA_ID}/${BuiltinOverlay.CAMERA_ID}.dll" to "camera")
        BuiltinOverlay(fs,
            { dir -> assets.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/").substringBefore('/') }.distinct() },
            { assets.getValue(it).toByteArray() }).install(root, ThemeMode.SYSTEM, true)
        assertEquals("player", fs.readText("$root/mods/$overlayId/config.json"))
    }
}
