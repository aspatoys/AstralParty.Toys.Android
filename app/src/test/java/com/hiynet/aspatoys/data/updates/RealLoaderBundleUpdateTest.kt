package com.hiynet.aspatoys.data.updates

import com.hiynet.aspatoys.data.loader.LoaderFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Runs production prepare/install against the real official ZIP that ships next to this checkout. */
class RealLoaderBundleUpdateTest {
    private val root = "/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader"

    private class MemoryFs : LoaderFs {
        val files = linkedMapOf<String, ByteArray>()
        val directories = linkedSetOf<String>()
        override fun isAvailable() = true
        override fun exists(path: String) = path in files || path in directories
        override fun readText(path: String) = files[path]?.toString(Charsets.UTF_8)
        override fun writeText(path: String, text: String) { files[path] = text.toByteArray() }
        override fun copyIn(localFile: File, remotePath: String) { files[remotePath] = localFile.readBytes() }
        override fun makeDirectories(path: String) { directories += path }
        override fun listDirectories(path: String) = emptyList<String>()
        override fun listFiles(path: String) = emptyList<String>()
        override fun readBytes(path: String) = files[path]
        override fun fileSize(path: String) = files[path]?.size?.toLong() ?: 0L
        override fun move(from: String, to: String) { files[to] = files.remove(from) ?: error("missing $from") }
        override fun delete(path: String) { files.remove(path) }
        override val lastError: String? = null
    }

    private fun repositoryRoot(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (!File(current, "settings.gradle.kts").isFile) current = checkNotNull(current.parentFile) { "no repo root" }
        return current
    }

    @Test fun `official 3_8_7 zip imports sdk and mods but excludes android protected mods`() {
        val zip = File(repositoryRoot().parentFile, "loader-3.8.7/cesium-loader.zip")
        assumeTrue("official loader ZIP not present", zip.isFile)
        val sha = MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("9033c7820044d4e0b26100dbcb08acd91a0f73d8e398c822a7ca90b0a301d1b9", sha)

        val work = java.nio.file.Files.createTempDirectory("real-loader").toFile()
        try {
            val pkg = ManagedLoaderUpdate(MemoryFs()).prepare(zip, "3.8.7", File(work, "stage"))
            assertEquals("3.8.7", pkg.version)
            assertTrue(pkg.files.containsKey("sdk/CesiumLoader.SDK.dll"))
            assertEquals(65, pkg.files.size)
            assertEquals(23, pkg.mods.size)
            assertTrue(pkg.mods.containsAll(listOf("FontMod", "PvePanelMod", "UpdateNoticeMod")))
            assertFalse(pkg.mods.any { it in ManagedLoaderUpdate.protectedMods || it == "LanguageUnlockMod" })
            assertTrue(pkg.files.containsKey("mods/PvePanelMod/data/card.png"))
            assertTrue(pkg.files.containsKey("mods/TurnFocusMod/sounds/your-turn.wav"))
            assertTrue(pkg.files.keys.none { it.startsWith("bootstrap/") || it == "doorstop_config.json" || it.endsWith(".pdb") })
            assertTrue(File(work, "stage/sdk/CesiumLoader.SDK.dll").length() > 0)

            val fs = MemoryFs()
            fs.files["$root/sdk/CesiumLoader.SDK.dll"] = "android".toByteArray()
            fs.files["$root/bootstrap/CesiumLoader.Bootstrap.dll"] = "android-bootstrap".toByteArray()
            fs.files["$root/mods/SpeedHackMod/SpeedHackMod.dll"] = "android-speed".toByteArray()
            fs.files["$root/mods/FreeCameraMod/FreeCameraMod.dll"] = "android-camera".toByteArray()
            fs.files["$root/mods/CesiumOverlayMod/CesiumOverlayMod.dll"] = "android-overlay".toByteArray()
            ManagedLoaderUpdate(fs).install(root, pkg)

            assertEquals("android-bootstrap", fs.files["$root/bootstrap/CesiumLoader.Bootstrap.dll"]!!.toString(Charsets.UTF_8))
            assertEquals("android-speed", fs.files["$root/mods/SpeedHackMod/SpeedHackMod.dll"]!!.toString(Charsets.UTF_8))
            assertEquals("android-camera", fs.files["$root/mods/FreeCameraMod/FreeCameraMod.dll"]!!.toString(Charsets.UTF_8))
            assertEquals("android-overlay", fs.files["$root/mods/CesiumOverlayMod/CesiumOverlayMod.dll"]!!.toString(Charsets.UTF_8))
            assertEquals(File(work, "stage/sdk/CesiumLoader.SDK.dll").readBytes().size, fs.files["$root/sdk/CesiumLoader.SDK.dll"]!!.size)
            assertTrue(fs.files.containsKey("$root/mods/FontMod/FontMod.dll"))
            assertTrue(fs.files.containsKey("$root/installed.json"))
            assertFalse(fs.files.keys.any { it.contains("SpeedHackMod") && it.endsWith(".json") })
        } finally { work.deleteRecursively() }
    }
}
