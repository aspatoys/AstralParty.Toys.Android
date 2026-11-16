package com.hiynet.aspatoys.data.cards

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Offline coverage for the Win-compatible card scheme store (import/replace/restore/export). */
class CardPackageStoreTest {
    private lateinit var tmp: File
    private lateinit var root: File
    private lateinit var store: CardPackageStore

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("aspatoys-cards").toFile()
        root = File(tmp, "cards").apply { mkdirs() }
        store = CardPackageStore(root)
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun `imports flat zip with a single scheme directory`() {
        val zip = zip("flat.zip") {
            png("UT_HandCard_10001.png")
            png("UT_HandCard_10002.png")
            text("skin.json", """{"name":"Flat","author":"me","description":"d"}""")
        }
        val imported = store.importZip(zip, "flat.zip")
        assertEquals(1, imported.size)
        assertEquals("Flat", imported[0].name)
        assertEquals(2, imported[0].cards)
        assertTrue(File(store.directory(imported[0].id), "UT_HandCard_10001.png").isFile)
    }

    @Test
    fun `imports AstralParty_ModLoader skins container`() {
        val zip = zip("loader.zip") {
            png("AstralParty_ModLoader/skins/alpha/UT_Event_12702.png")
            text("AstralParty_ModLoader/skins/alpha/skin.json", """{"name":"Alpha"}""")
            png("AstralParty_ModLoader/skins/beta/UT_MapEvent_31001.png")
        }
        val imported = store.importZip(zip, "loader.zip").sortedBy { it.id }
        assertEquals(2, imported.size)
        assertEquals(listOf("alpha", "beta"), imported.map { it.id })
    }

    @Test
    fun `imports nested multi-scheme zip and keeps both schemes`() {
        val zip = zip("multi.zip") {
            png("pack/one/UT_HandCard_20001.png")
            png("pack/two/UT_HandCard_20002.png")
            png("pack/two/UT_HandCard_20003.png")
        }
        val imported = store.importZip(zip, "multi.zip").sortedBy { it.id }
        assertEquals(listOf("one", "two"), imported.map { it.id })
        assertEquals(listOf(1, 2), imported.map { it.cards })
    }

    @Test
    fun `accepts GBK entry names from Windows archivers`() {
        val zip = File(tmp, "gbk.zip")
        val gbk = Charset.forName("GB18030")
        ZipOutputStream(zip.outputStream(), gbk).use { out ->
            out.putNextEntry(ZipEntry("星趴卡组/加速卡.png"))
            out.write(pngBytes())
            out.closeEntry()
        }
        val imported = store.importZip(zip, "gbk.zip")
        assertEquals(1, imported.size)
        assertEquals("星趴卡组", imported[0].id)
        assertEquals("加速卡", store.cards(imported[0].id).single().key)
    }

    @Test
    fun `imports cardanim with frames and counts it as one card`() {
        val zip = zip("anim.zip") {
            text("UT_HandCard_10001.cardanim", "version=1\nfps=12\nframeCount=2\nwidth=2\nheight=2\n")
            frame("UT_HandCard_10001.frames/000000.png")
            frame("UT_HandCard_10001.frames/000001.png")
        }
        val profile = store.importZip(zip, "anim.zip").single()
        val card = store.cards(profile.id).single()
        assertEquals("UT_HandCard_10001", card.key)
        assertTrue(card.animated)
        assertNotNull(card.file)
    }

    @Test
    fun `rejects absolute and drive-qualified zip entries`() {
        val absolute = zip("abs.zip") { png("/etc/passwd.png") }
        assertFailsWithMessage("不安全路径") { store.importZip(absolute, "abs.zip") }

        val drive = zip("drive.zip") { png("C:/Windows/win.png") }
        assertFailsWithMessage("不安全路径") { store.importZip(drive, "drive.zip") }
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".import-") })
    }

    @Test
    fun `rejects case-insensitive duplicate zip entries`() {
        val duplicate = zip("dup.zip") {
            png("UT_HandCard_10001.png")
            png("ut_handcard_10001.PNG")
        }
        assertFailsWithMessage("重名文件") { store.importZip(duplicate, "dup.zip") }
    }

    @Test
    fun `safeRelative rejects traversal, empty segments and control characters`() {
        listOf("../escape.png", "a/../../b.png", "..", "/abs.png", "a//b.png", "a/./b.png", "a\u0001b.png", "").forEach { value ->
            assertFailsWithMessage("不安全路径") { CardPackageStore.safeRelative(value) }
        }
        assertEquals("a/b.png", CardPackageStore.safeRelative("a\\b.png"))
        assertEquals("astral/skins/x.png", CardPackageStore.safeRelative("astral/skins/x.png"))
    }

    @Test
    fun `rejects zip without card art and leaves no hidden staging profile`() {
        val zip = zip("empty.zip") { text("readme.txt", "nothing here") }
        assertFailsWithMessage("没有找到卡牌图片") { store.importZip(zip, "empty.zip") }
        assertEquals(emptyList<String>(), store.profiles().map { it.id })
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".import-") })
    }

    @Test
    fun `create replace restore delete round trip`() {
        val profile = store.create("My Cards")
        assertEquals("My Cards", profile.name)

        val image = File(tmp, "new.png").apply { writeBytes(pngBytes()) }
        store.replace(profile.id, "UT_HandCard_10001", image, "PNG")
        assertEquals("UT_HandCard_10001", store.cards(profile.id).single().key)
        assertTrue(File(store.directory(profile.id), "UT_HandCard_10001.png").isFile)

        store.restoreCard(profile.id, "UT_HandCard_10001")
        assertEquals(0, store.cards(profile.id).size)
        assertTrue(File(store.directory(profile.id), ".pending").isFile)

        store.delete(profile.id)
        assertFalse(store.directory(profile.id).exists())
    }

    @Test
    fun `export produces a scheme-directory zip that re-imports`() {
        val zip = zip("seed.zip") {
            png("UT_HandCard_10001.png")
            text("skin.json", """{"name":"Seed"}""")
        }
        val profile = store.importZip(zip, "seed.zip").single()
        val exported = File(tmp, "export.zip")
        store.export(profile.id, exported)

        val names = java.util.zip.ZipFile(exported).use { zf -> zf.entries().toList().map { it.name }.sorted() }
        assertEquals(listOf("${profile.id}/UT_HandCard_10001.png", "${profile.id}/skin.json"), names)

        val again = CardPackageStore(File(tmp, "second").apply { mkdirs() }).importZip(exported, "export.zip")
        assertEquals(1, again.size)
        assertEquals(profile.name, again[0].name)
    }

    @Test
    fun `rejects invalid ids and unsupported image payloads`() {
        assertFailsWithMessage("方案名称无效") { store.directory("../escape") }
        assertFailsWithMessage("方案名称无效") { store.directory(CardPackageStore.GameDefault) }
        assertNull(CardPackageStore.pngSize(File(tmp, "missing.png")))
        assertFalse(CardPackageStore.basicImage(File(tmp, "fake.png").apply { writeText("nope") }))
    }

    @Test
    fun `unique ids avoid collisions with existing profiles`() {
        store.create("same")
        store.create("same")
        val ids = store.profiles().map { it.id }.sorted()
        assertEquals(listOf("same", "same-2"), ids)
        assertEquals("custom-cards", store.create(CardPackageStore.GameDefault).id)
    }

    private inline fun assertFailsWithMessage(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("expected failure containing: $fragment")
        } catch (error: Throwable) {
            if (error is AssertionError) throw error
            val message = error.message.orEmpty()
            assertTrue("unexpected message: $message", message.contains(fragment))
        }
    }

    private fun zip(name: String, entries: ZipBuilder.() -> Unit): File {
        val file = File(tmp, name)
        ZipOutputStream(file.outputStream(), Charsets.UTF_8).use { out -> ZipBuilder(out).entries() }
        return file
    }

    private inner class ZipBuilder(private val out: ZipOutputStream) {
        fun png(path: String) = bytes(path, pngBytes())
        fun frame(path: String) = bytes(path, frameBytes())
        fun text(path: String, value: String) = bytes(path, value.toByteArray(Charsets.UTF_8))
        fun bytes(path: String, value: ByteArray) {
            out.putNextEntry(ZipEntry(path))
            out.write(value)
            out.closeEntry()
        }
    }

    companion object {
        fun pngBytes(width: Int = 2, height: Int = 2): ByteArray {
            val header = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82)
            fun int(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
            return header + int(width) + int(height) + ByteArray(16) { it.toByte() }
        }

        private fun frameBytes(): ByteArray = pngBytes()
    }
}
