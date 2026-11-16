package com.hiynet.aspatoys.data.cards

import com.hiynet.aspatoys.core.parseJsonObjectOrNull
import com.hiynet.aspatoys.core.booleanOrDefault
import com.hiynet.aspatoys.core.string
import com.hiynet.aspatoys.data.loader.LoaderFs
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/** Drives GameCardProfiles against a real temp directory through a file-backed LoaderFs. */
class GameCardProfilesTest {

    private fun pngBytes(width: Int = 2, height: Int = 2): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val header = java.nio.ByteBuffer.allocate(25).order(java.nio.ByteOrder.BIG_ENDIAN)
        header.putInt(13)
        header.put("IHDR".toByteArray(Charsets.US_ASCII))
        header.putInt(width)
        header.putInt(height)
        header.put(byteArrayOf(8, 6, 0, 0, 0))
        out.write(header.array())
        out.write(ByteArray(4))
        out.write(ByteArray(16) { 0x20 })
        return out.toByteArray()
    }

    private fun zip(destination: File, contents: Map<String, ByteArray>): File {
        ZipOutputStream(destination.outputStream()).use { zip ->
            contents.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return destination
    }

    private class TempRoot : AutoCloseable {
        val dir: File = Files.createTempDirectory("cards-game").toFile()
        override fun close() {
            dir.deleteRecursively()
        }
    }

    private class FileLoaderFs : LoaderFs {
        private var last: String? = null
        override fun isAvailable(): Boolean = true
        override fun listDirectories(path: String): List<String> = File(path).listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()
        override fun listFiles(path: String): List<String> = File(path).listFiles { f -> f.isFile }?.map { it.name }.orEmpty()
        override fun exists(path: String): Boolean = File(path).exists()
        override fun readText(path: String): String? = File(path).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        override fun writeText(path: String, text: String) {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
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
            if (!File(path).deleteRecursively()) last = "delete failed: $path"
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
        override val lastError: String?
            get() = last
    }

    private fun assets(): Pair<(String) -> ByteArray, (String) -> List<String>> {
        val map = mapOf(
            "cesium/mods/CardSkinMod/CardSkinMod.dll" to byteArrayOf(0x4D, 0x5A, 0x01, 0x02),
            "cesium/mods/CardSkinMod/CardSkinMod.json" to """{"Enabled":true}""".toByteArray(),
        )
        val children = mapOf("cesium/mods/CardSkinMod" to listOf("CardSkinMod.json", "CardSkinMod.dll"))
        return Pair(
            { path: String -> map[path] ?: error("missing asset $path") },
            { path: String -> children[path].orEmpty() },
        )
    }

    private fun prepared(root: File): Triple<CardPackageStore, GameCardProfiles, File> {
        val storeRoot = File(root, "local-cards").apply { mkdirs() }
        val store = CardPackageStore(storeRoot, CardPackageStore::basicImage)
        val game = File(root, "game").apply { mkdirs() }
        File(game, "sdk").mkdirs()
        File(game, "sdk/CesiumLoader.SDK.dll").writeBytes(byteArrayOf(1))
        File(game, "mods/OtherMod").mkdirs()
        File(game, "mods/OtherMod/OtherMod.dll").writeBytes(byteArrayOf(9, 9))
        File(game, "configs").mkdirs()
        File(game, "configs/OtherMod.json").writeText("""{"keep":true}""")
        val (asset, listAssets) = assets()
        return Triple(store, GameCardProfiles(FileLoaderFs(), store, asset, listAssets), game)
    }

    @Test
    fun `updatedConfig patches existing keys and preserves unrelated fields`() {
        val raw = """{"Enabled": false, "ActiveSkin": "default", "Scale": 1.5}"""
        val patched = GameCardProfiles.updatedConfig(raw, "my-cards")
        val parsed = parseJsonObjectOrNull(patched)!!
        assertEquals(true, parsed.booleanOrDefault("Enabled", false))
        assertEquals("my-cards", parsed.string("ActiveSkin"))
        assertEquals(1.5, parsed["Scale"].toString().toDouble(), 0.0001)
    }

    @Test
    fun `updatedConfig inserts missing keys and keeps valid json`() {
        val patched = GameCardProfiles.updatedConfig("{}", "my-cards")
        val parsed = parseJsonObjectOrNull(patched)!!
        assertEquals(true, parsed.booleanOrDefault("Enabled", false))
        assertEquals("my-cards", parsed.string("ActiveSkin"))
    }

    @Test
    fun `updatedConfig disables the mod for the game default profile`() {
        val patched = GameCardProfiles.updatedConfig("""{"Enabled":true,"ActiveSkin":"old"}""", CardPackageStore.GameDefault)
        val parsed = parseJsonObjectOrNull(patched)!!
        assertEquals(false, parsed.booleanOrDefault("Enabled", true))
        assertEquals("old", parsed.string("ActiveSkin"))
    }

    @Test
    fun `updatedConfig refuses malformed json instead of overwriting it`() {
        try {
            GameCardProfiles.updatedConfig("{ not json", "my-cards")
            fail("malformed config must be rejected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("CardSkinMod"))
        }
    }

    @Test
    fun `apply writes config skins and runtime while keeping other mods and configs`() {
        TempRoot().use { tmp ->
            val (store, game, root) = prepared(tmp.dir)
            val archive = zip(File(tmp.dir, "pack.zip"), mapOf("UT_HandCard_10001.png" to pngBytes()))
            val imported = store.importZip(archive, "pack.zip")
            val id = imported.single().id

            game.apply(root.absolutePath, id)

            val config = parseJsonObjectOrNull(File(root, "configs/CardSkinMod.json").readText())!!
            assertEquals(true, config.booleanOrDefault("Enabled", false))
            assertEquals(id, config.string("ActiveSkin"))
            assertTrue(File(root, "skins/$id/UT_HandCard_10001.png").isFile)
            assertTrue("runtime must be deployed", File(root, "mods/CardSkinMod/CardSkinMod.dll").isFile)
            // Unrelated mods and configs survive.
            assertEquals(byteArrayOf(9, 9).toList(), File(root, "mods/OtherMod/OtherMod.dll").readBytes().toList())
            assertEquals("""{"keep":true}""", File(root, "configs/OtherMod.json").readText())
            // Staging leftovers are cleaned up.
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".card-import-") || it.name.startsWith(".card-backup-") })

            val snapshot = game.snapshot(root.absolutePath)
            assertEquals(id, snapshot.selected)
            assertEquals(listOf(id), snapshot.profiles.map { it.id })
        }
    }

    @Test
    fun `apply backs up an existing runtime dll once`() {
        TempRoot().use { tmp ->
            val (store, game, root) = prepared(tmp.dir)
            File(root, "mods/CardSkinMod").mkdirs()
            File(root, "mods/CardSkinMod/CardSkinMod.dll").writeBytes(byteArrayOf(7, 7, 7))
            val archive = zip(File(tmp.dir, "pack.zip"), mapOf("UT_HandCard_10001.png" to pngBytes()))
            val id = store.importZip(archive, "pack.zip").single().id

            game.apply(root.absolutePath, id)

            assertEquals(byteArrayOf(7, 7, 7).toList(), File(root, "mods/CardSkinMod/CardSkinMod.dll.pre-1.2.0").readBytes().toList())
        }
    }

    @Test
    fun `apply of the game default turns the mod off without touching skins`() {
        TempRoot().use { tmp ->
            val (store, game, root) = prepared(tmp.dir)
            val archive = zip(File(tmp.dir, "pack.zip"), mapOf("UT_HandCard_10001.png" to pngBytes()))
            val id = store.importZip(archive, "pack.zip").single().id
            game.apply(root.absolutePath, id)

            game.apply(root.absolutePath, CardPackageStore.GameDefault)

            val config = parseJsonObjectOrNull(File(root, "configs/CardSkinMod.json").readText())!!
            assertEquals(false, config.booleanOrDefault("Enabled", true))
            assertTrue("restoring default keeps the imported scheme", File(root, "skins/$id/UT_HandCard_10001.png").isFile)
        }
    }

    @Test
    fun `snapshot reports a problem when the loader is missing`() {
        TempRoot().use { tmp ->
            val (_, game, root) = prepared(tmp.dir)
            File(root, "sdk/CesiumLoader.SDK.dll").delete()
            val snapshot = game.snapshot(root.absolutePath)
            assertTrue(snapshot.problem != null && snapshot.problem!!.contains("加载器"))
            assertFalse(snapshot.problem!!.contains("CardSkinMod"))
        }
    }
}
