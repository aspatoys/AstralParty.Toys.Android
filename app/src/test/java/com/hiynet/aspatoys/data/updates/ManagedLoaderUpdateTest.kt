package com.hiynet.aspatoys.data.updates

import com.hiynet.aspatoys.data.loader.LoaderFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Drives the production [ManagedLoaderUpdate] with a real ZIP on disk and an in-memory device.
 * The fixture models only the loader root; nothing here touches a game directory.
 */
class ManagedLoaderUpdateTest {
    private val root = "/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader"

    private class MemoryFs : LoaderFs {
        val files = linkedMapOf<String, ByteArray>()
        val directories = linkedSetOf<String>()
        var available = true
        var failCopy: ((String) -> Boolean)? = null
        var failMove: ((String, String) -> Boolean)? = null
        var deletes = 0
        override fun isAvailable() = available
        override fun exists(path: String) = path in files || path in directories
        override fun readText(path: String) = files[path]?.toString(Charsets.UTF_8)
        override fun writeText(path: String, text: String) { files[path] = text.toByteArray() }
        override fun copyIn(localFile: File, remotePath: String) {
            if (failCopy?.invoke(remotePath) == true) error("copyIn 失败：$remotePath")
            files[remotePath] = localFile.readBytes()
        }
        override fun makeDirectories(path: String) { directories += path }
        override fun listDirectories(path: String) = directories.filter { it.startsWith("$path/") && it.removePrefix("$path/").let { n -> !n.contains('/') } }
        override fun listFiles(path: String) = files.keys.filter { it.startsWith("$path/") && it.removePrefix("$path/").let { n -> !n.contains('/') } }.map { it.substringAfterLast('/') }
        override fun readBytes(path: String) = files[path]
        override fun fileSize(path: String) = files[path]?.size?.toLong() ?: 0L
        override fun move(from: String, to: String) {
            if (failMove?.invoke(from, to) == true) error("move 失败：$from")
            val bytes = files.remove(from) ?: error("move 源不存在：$from")
            files[to] = bytes
        }
        // Mirrors ShellLoaderFs "rm -rf": the path and everything below it disappear.
        override fun delete(path: String) { deletes++; files.keys.removeAll { it == path || it.startsWith("$path/") }; directories.removeAll { it == path || it.startsWith("$path/") } }
        override val lastError: String? = null
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun text(value: String) = value.toByteArray()

    /** entries: relative path -> bytes, hashed by default into a manifest matching [version]. */
    private fun zip(dir: File, entries: Map<String, ByteArray>, version: String = "3.8.7",
                    hashes: Map<String, String>? = null, manifestOverride: String? = null, name: String = "loader.zip"): File {
        val manifest = manifestOverride ?: buildString {
            append("{\"name\":\"CesiumLoader\",\"version\":\"$version\",\"files\":{")
            append(entries.entries.joinToString(",") { (path, bytes) ->
                "\"AstralParty_ModLoader/$path\":\"${hashes?.get(path) ?: sha(bytes)}\""
            })
            append("}}")
        }
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (path, bytes) ->
                out.putNextEntry(ZipEntry("AstralParty_ModLoader/$path")); out.write(bytes); out.closeEntry()
            }
            out.putNextEntry(ZipEntry("cesium-loader.json")); out.write(text(manifest)); out.closeEntry()
            out.putNextEntry(ZipEntry("version.dll")); out.write(text("native")); out.closeEntry()
        }
        return file
    }

    private val payload = mapOf(
        "sdk/CesiumLoader.SDK.dll" to text("sdk"),
        "CesiumLoader-LICENSE.txt" to text("license"),
        "mods/FontMod/FontMod.dll" to text("font-dll"),
        "mods/FontMod/FontMod.json" to text("{\"id\":\"FontMod\"}"),
        "mods/FontMod/data/zh.pack" to text("pack"),
        "mods/FontMod/config.json" to text("user-config"),
        "mods/FontMod/mod.cfg" to text("user-cfg"),
        "mods/SpeedHackMod/SpeedHackMod.dll" to text("win-speed"),
        "mods/FreeCameraMod/FreeCameraMod.dll" to text("win-camera"),
        "mods/CesiumOverlayMod/CesiumOverlayMod.dll" to text("win-overlay"),
        "mods/LanguageUnlockMod/LanguageUnlockMod.dll" to text("win-lang"),
        "mods/PvePanelMod/PvePanelMod.dll" to text("pve"),
        "mods/PvePanelMod/data/card.png" to text("png"),
        "bootstrap/CesiumLoader.Bootstrap.dll" to text("bootstrap"),
        "doorstop_config.json" to text("doorstop"),
    )

    @Test fun `prepare keeps sdk mods and resources but drops protected windows mods`() = withTemp { dir ->
        val fs = MemoryFs()
        val pkg = ManagedLoaderUpdate(fs).prepare(zip(dir, payload), "3.8.7", File(dir, "stage"))
        assertEquals(listOf("FontMod", "PvePanelMod"), pkg.mods)
        assertEquals("3.8.7", pkg.version)
        assertTrue(pkg.files.containsKey("sdk/CesiumLoader.SDK.dll"))
        assertTrue(pkg.files.containsKey("CesiumLoader-LICENSE.txt"))
        assertTrue(pkg.files.containsKey("mods/FontMod/FontMod.dll"))
        assertTrue(pkg.files.containsKey("mods/FontMod/FontMod.json"))
        assertTrue(pkg.files.containsKey("mods/FontMod/data/zh.pack"))
        assertFalse(pkg.files.keys.any { it.endsWith("config.json") || it.endsWith("mod.cfg") })
        assertTrue(pkg.files.keys.none { it.contains("SpeedHackMod") || it.contains("FreeCameraMod") || it.contains("CesiumOverlayMod") })
        assertTrue(pkg.files.keys.none { it.contains("LanguageUnlockMod") })
        assertTrue(pkg.files.keys.none { it.startsWith("bootstrap/") || it == "doorstop_config.json" || it.endsWith(".pdb") })
        assertFalse(File(dir, "stage/sdk/CesiumLoader.SDK.dll").readText().isEmpty())
    }

    @Test fun `prepare rejects traversal duplicate version mismatch and bad hash`() = withTemp { dir ->
        val installer = ManagedLoaderUpdate(MemoryFs())
        fun expectFailure(zip: File, expected: String, version: String = "3.8.7") {
            try { installer.prepare(zip, version, File(dir, "stage")); fail("accepted $expected") }
            catch (error: Exception) { assertTrue("unexpected message: ${error.message}", error.message.orEmpty().isNotBlank()) }
        }
        expectFailure(zip(dir, mapOf("../escape.dll" to text("x")), name = "traversal.zip"), "traversal")
        expectFailure(zip(dir, payload, version = "3.8.6", name = "old.zip"), "version", version = "3.8.7")
        expectFailure(zip(dir, payload, hashes = mapOf("sdk/CesiumLoader.SDK.dll" to "b".repeat(64)), name = "hash.zip"), "hash")
        expectFailure(zip(dir, payload, manifestOverride = "{}", name = "no-manifest.zip"), "manifest")
        expectFailure(zip(dir, payload, manifestOverride = "{\"version\":\"3.8.7\",\"files\":{}}", name = "empty.zip"), "missing sdk")
        expectFailure(zip(dir, mapOf("mods/FontMod/FontMod.dll" to text("dll")), name = "nomods.zip"), "mods")
        // Duplicate path differing only by case must not be silently merged.
        val dup = File(dir, "dup.zip")
        ZipOutputStream(dup.outputStream()).use { out ->
            for (path in listOf("AstralParty_ModLoader/sdk/CesiumLoader.SDK.dll", "AstralParty_ModLoader/SDK/CesiumLoader.SDK.dll")) {
                out.putNextEntry(ZipEntry(path)); out.write(text("x")); out.closeEntry()
            }
            out.putNextEntry(ZipEntry("cesium-loader.json")); out.write(text("{\"version\":\"3.8.7\",\"files\":{}}")); out.closeEntry()
        }
        expectFailure(dup, "duplicate")
    }

    @Test fun `install publishes sdk mods and keeps disabled and legacy layouts`() = withTemp { dir ->
        val fs = MemoryFs()
        val installer = ManagedLoaderUpdate(fs)
        val pkg = installer.prepare(zip(dir, payload), "3.8.7", File(dir, "stage"))
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = text("android-sdk")
        fs.files["$root/bootstrap/CesiumLoader.Bootstrap.dll"] = text("android-bootstrap")
        fs.files["$root/mods/FontMod.dll"] = text("legacy-flat")
        fs.files["$root/mods/FontMod.json"] = text("{}")
        fs.files["$root/mods/PvePanelMod/PvePanelMod.json"] = text("{\"user\":true}")
        fs.files["$root/mods/FontMod/config.json"] = text("keep-config")
        fs.files["$root/disabled_mods/AudioSkinMod/AudioSkinMod.dll"] = text("disabled")
        installer.install(root, pkg)

        assertEquals("sdk", fs.files["$root/sdk/CesiumLoader.SDK.dll"]!!.toString(Charsets.UTF_8))
        assertEquals("android-bootstrap", fs.files["$root/bootstrap/CesiumLoader.Bootstrap.dll"]!!.toString(Charsets.UTF_8))
        assertEquals("font-dll", fs.files["$root/mods/FontMod/FontMod.dll"]!!.toString(Charsets.UTF_8))
        assertEquals("pack", fs.files["$root/mods/FontMod/data/zh.pack"]!!.toString(Charsets.UTF_8))
        assertEquals("keep-config", fs.files["$root/mods/FontMod/config.json"]!!.toString(Charsets.UTF_8))
        assertFalse(fs.files.containsKey("$root/mods/FontMod/config.json.new"))
        assertEquals("{\"user\":true}", fs.files["$root/mods/PvePanelMod/PvePanelMod.json"]!!.toString(Charsets.UTF_8))
        assertFalse(fs.files.containsKey("$root/mods/FontMod.dll"))
        assertFalse(fs.files.containsKey("$root/mods/FontMod.json"))
        assertEquals("disabled", fs.files["$root/disabled_mods/AudioSkinMod/AudioSkinMod.dll"]!!.toString(Charsets.UTF_8))
        assertTrue(fs.files.keys.none { it.contains("SpeedHackMod") || it.contains("FreeCameraMod") || it.contains("CesiumOverlayMod") })
        val index = fs.files["$root/installed.json"]!!.toString(Charsets.UTF_8)
        assertTrue(index.contains("\"3.8.7\""))
        assertEquals(1, fs.deletes) // only the finished transaction directory is removed
    }

    @Test fun `install refuses a disabled mod that is also active`() = withTemp { dir ->
        val fs = MemoryFs()
        val installer = ManagedLoaderUpdate(fs)
        val pkg = installer.prepare(zip(dir, payload), "3.8.7", File(dir, "stage"))
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = text("sdk")
        fs.files["$root/mods/FontMod/FontMod.dll"] = text("active")
        fs.files["$root/disabled_mods/FontMod/FontMod.dll"] = text("disabled")
        try { installer.install(root, pkg); fail("conflict accepted") } catch (_: Exception) { }
        assertEquals("active", fs.files["$root/mods/FontMod/FontMod.dll"]!!.toString(Charsets.UTF_8))
    }

    @Test fun `install requires an installed loader and an available device`() = withTemp { dir ->
        val fs = MemoryFs()
        val installer = ManagedLoaderUpdate(fs)
        val pkg = installer.prepare(zip(dir, payload), "3.8.7", File(dir, "stage"))
        try { installer.install(root, pkg); fail("installed without loader") } catch (_: Exception) { }
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = text("sdk")
        fs.available = false
        try { installer.install(root, pkg); fail("installed offline") } catch (_: Exception) { }
        assertTrue(fs.files.keys.none { it.contains("FontMod") })
    }

    @Test fun `copy failure rolls back every original file and keeps installed version`() = withTemp { dir ->
        val fs = MemoryFs()
        val installer = ManagedLoaderUpdate(fs)
        val pkg = installer.prepare(zip(dir, payload), "3.8.7", File(dir, "stage"))
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = text("old-sdk")
        fs.files["$root/installed.json"] = text("{\"loaderVersion\":\"3.7.0\"}")
        fs.files["$root/mods/FontMod/FontMod.dll"] = text("old-font")
        fs.files["$root/mods/DisabledMod.dll"] = text("legacy-flat")
        fs.failCopy = { it.contains("PvePanelMod/PvePanelMod.dll") }
        try { installer.install(root, pkg); fail("copy failure ignored") } catch (_: Exception) { }
        assertEquals("old-sdk", fs.files["$root/sdk/CesiumLoader.SDK.dll"]!!.toString(Charsets.UTF_8))
        assertEquals("old-font", fs.files["$root/mods/FontMod/FontMod.dll"]!!.toString(Charsets.UTF_8))
        assertFalse(fs.files["$root/installed.json"]!!.toString(Charsets.UTF_8).contains("3.8.7"))
        assertEquals("legacy-flat", fs.files["$root/mods/DisabledMod.dll"]!!.toString(Charsets.UTF_8))
    }

    @Test fun `publish failure restores overwritten files and leaves no staging`() = withTemp { dir ->
        val fs = MemoryFs()
        val installer = ManagedLoaderUpdate(fs)
        val pkg = installer.prepare(zip(dir, payload), "3.8.7", File(dir, "stage"))
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = text("old-sdk")
        fs.files["$root/installed.json"] = text("{\"loaderVersion\":\"3.7.0\"}")
        fs.files["$root/mods/FontMod/FontMod.dll"] = text("old-font")
        // Fail the last published file: installed.json must not land before the payload does.
        fs.failMove = { _, to -> to.endsWith("installed.json") }
        try { installer.install(root, pkg); fail("publish failure ignored") } catch (_: Exception) { }
        assertEquals("old-sdk", fs.files["$root/sdk/CesiumLoader.SDK.dll"]!!.toString(Charsets.UTF_8))
        assertEquals("old-font", fs.files["$root/mods/FontMod/FontMod.dll"]!!.toString(Charsets.UTF_8))
        assertEquals("{\"loaderVersion\":\"3.7.0\"}", fs.files["$root/installed.json"]!!.toString(Charsets.UTF_8))
        assertFalse(fs.files.keys.any { it.contains("PvePanelMod/PvePanelMod.dll") })
        assertTrue(fs.files.keys.none { it.contains(".toys-loader-update-") })
        assertFalse(fs.directories.any { it.contains(".toys-loader-update-") })
    }

    @Test fun `install rejects files outside the managed payload`() = withTemp { dir ->
        val fs = MemoryFs()
        val installer = ManagedLoaderUpdate(fs)
        fs.files["$root/sdk/CesiumLoader.SDK.dll"] = text("sdk")
        val pkg = ManagedLoaderUpdate.Package("3.8.7", linkedMapOf("../escape.dll" to File(dir, "x")), emptyList())
        try { installer.install(root, pkg); fail("traversal install accepted") } catch (_: Exception) { }
        assertFalse(fs.files.keys.any { it.contains("escape") })
        val protected = ManagedLoaderUpdate.Package("3.8.7",
            linkedMapOf("mods/SpeedHackMod/SpeedHackMod.dll" to File(dir, "x")), listOf("SpeedHackMod"))
        try { installer.install(root, protected); fail("protected mod deployed") } catch (_: Exception) { }
    }

    private fun withTemp(block: (File) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("managed").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
