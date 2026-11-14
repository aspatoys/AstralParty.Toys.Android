package com.hiynet.aspatoys.data.market

import com.hiynet.aspatoys.data.mods.ModLayout
import com.hiynet.aspatoys.data.mods.ModPackageInspector
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「下载 → 校验 → 预检」整条链路的测试：市场包、哈希、模组 ID/版本一致性、SDK 门槛。
 */
class ModPackageServiceTest {
    private lateinit var root: File
    private lateinit var layout: ModLayout
    private lateinit var downloads: File
    private lateinit var service: ModPackageService
    private lateinit var server: HttpServer

    private val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("aspatoys-service").toFile()
        layout = ModLayout(File(root, "loader"))
        layout.modsRoot.mkdirs()
        layout.disabledModsRoot.mkdirs()
        downloads = File(root, "downloads")
        service = ModPackageService(
            client = MarketClient("AstralParty Toys/test"),
            inspector = ModPackageInspector(layout),
            layout = layout,
            downloadsDir = downloads,
        )
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        root.deleteRecursively()
    }

    @Test
    fun `successful download passes all checks`() {
        val bytes = modZip("TestMod", "1.0.0")
        servePackage(bytes)

        val report = service.downloadAndCheck(
            baseUrl = baseUrl,
            detail = detail("TestMod", "1.0.0"),
            version = version("1.0.0", sha256(bytes)),
        )

        assertTrue(report.issues.joinToString { it.message }, report.success)
        assertEquals("检查通过", report.headline)
        assertTrue(File(downloads, "TestMod-1.0.0.zip").isFile)
        assertEquals(bytes.size.toLong(), report.sizeBytes)
        assertEquals(1, report.inspection!!.mods.size)
        assertTrue(report.issues.none { it.level == CheckIssue.Level.ERROR })
    }

    @Test
    fun `sha mismatch fails the check`() {
        val bytes = modZip("TestMod", "1.0.0")
        servePackage(bytes)

        val report = service.downloadAndCheck(
            baseUrl = baseUrl,
            detail = detail("TestMod", "1.0.0"),
            version = version("1.0.0", "0000000000000000000000000000000000000000000000000000000000000000"),
        )

        assertFalse(report.success)
        assertTrue(report.issues.any { it.level == CheckIssue.Level.ERROR && it.message.contains("SHA-256") })
    }

    @Test
    fun `mod id mismatch fails the check`() {
        val bytes = modZip("OtherMod", "1.0.0")
        servePackage(bytes)

        val report = service.downloadAndCheck(
            baseUrl = baseUrl,
            detail = detail("TestMod", "1.0.0"),
            version = version("1.0.0", sha256(bytes)),
        )

        assertFalse(report.success)
        assertTrue(report.issues.any { it.message.contains("与市场登记的 TestMod 不一致") })
    }

    @Test
    fun `version mismatch fails the check`() {
        val bytes = modZip("TestMod", "2.2.2")
        servePackage(bytes)

        val report = service.downloadAndCheck(
            baseUrl = baseUrl,
            detail = detail("TestMod", "1.0.0"),
            version = version("1.0.0", sha256(bytes)),
        )

        assertFalse(report.success)
        assertTrue(report.issues.any { it.message.contains("包内版本") })
    }

    @Test
    fun `sdk newer than loader blocks the check`() {
        layout.loaderRoot.mkdirs()
        layout.configFile.writeText("""{"sdkVersion":"2.8.6"}""")
        val bytes = modZip("TestMod", "1.0.0", sdkVersion = "2.9.1")
        servePackage(bytes)

        val report = service.downloadAndCheck(
            baseUrl = baseUrl,
            detail = detail("TestMod", "1.0.0"),
            version = version("1.0.0", sha256(bytes)),
        )

        assertFalse(report.success)
        assertTrue(report.issues.any { it.message.contains("要求 SDK 2.9.1") })
        assertEquals("2.8.6", report.installedSdkVersion)
    }

    @Test
    fun `downgrade is reported but only as warning`() {
        installMod("TestMod", "2.0.0")
        val bytes = modZip("TestMod", "1.0.0")
        servePackage(bytes)

        val report = service.downloadAndCheck(
            baseUrl = baseUrl,
            detail = detail("TestMod", "1.0.0"),
            version = version("1.0.0", sha256(bytes)),
        )

        assertTrue(report.success)
        assertEquals("检查通过，但包内版本比已装的旧", report.headline)
        assertTrue(report.issues.any { it.level == CheckIssue.Level.WARNING && it.message.contains("降级") })
    }

    @Test
    fun `local check reports structure errors`() {
        File(root, "not-zip.zip").writeText("plain text")
        val report = service.checkLocal(File(root, "not-zip.zip"))

        assertFalse(report.success)
        assertTrue(report.issues.any { it.level == CheckIssue.Level.ERROR })
    }

    @Test
    fun `local check passes for valid package`() {
        val file = File(root, "TestMod-1.0.0.zip").apply { writeBytes(modZip("TestMod", "1.0.0")) }

        val report = service.checkLocal(file)

        assertTrue(report.success)
        assertEquals("TestMod", report.modId)
        assertEquals("检查通过", report.headline)
    }

    @Test
    fun `downloaded packages are listed`() {
        downloads.mkdirs()
        File(downloads, "a.zip").writeBytes(modZip("A", "1.0.0"))
        File(downloads, "b.zip").writeBytes(modZip("B", "1.0.0"))
        File(downloads, "notes.txt").writeText("x")

        val packages = service.downloadedPackages()

        assertEquals(listOf("a.zip", "b.zip").sorted(), packages.map { it.name }.sorted())
    }

    // ============================== 辅助 ==============================

    private fun servePackage(bytes: ByteArray) {
        server.createContext("/download") { exchange -> write(exchange, bytes) }
        server.createContext("/api/mods/TestMod/download") { exchange -> write(exchange, bytes) }
    }

    private fun write(exchange: HttpExchange, bytes: ByteArray) {
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun detail(id: String, latestVersion: String) = MarketModDetail(
        id = id,
        name = id,
        latestVersion = latestVersion,
    )

    private fun version(version: String, sha256: String) = MarketModVersion(
        version = version,
        downloadUrl = "/api/mods/TestMod/download",
        hosted = true,
        sha256 = sha256,
        sdkVersion = "2.8.6",
        sizeBytes = 1024,
    )

    private fun installMod(id: String, version: String) {
        val folder = File(layout.modsRoot, id).apply { mkdirs() }
        File(folder, "$id.dll").writeBytes(managedDll())
        File(folder, "$id.json").writeText("""{"id":"$id","version":"$version"}""")
    }

    private fun modZip(
        id: String,
        sidecarVersion: String,
        sdkVersion: String = "",
    ): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        ZipOutputStream(buffer).use { output ->
            fun put(name: String, bytes: ByteArray) {
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
            put("$id/$id.dll", managedDll())
            put(
                "$id/$id.json",
                """
                {"id":"$id","name":"$id","version":"$sidecarVersion","sdkVersion":"$sdkVersion"}
                """.trimIndent().toByteArray(),
            )
        }
        return buffer.toByteArray()
    }

    private fun managedDll(): ByteArray {
        val header = byteArrayOf('M'.code.toByte(), 'Z'.code.toByte())
        return header + ByteArray(64) + "BSJB".toByteArray() + ByteArray(32)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
