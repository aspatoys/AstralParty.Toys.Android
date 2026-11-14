package com.hiynet.aspatoys.data.market

import com.hiynet.aspatoys.core.HttpCancellation
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 市场客户端测试：对着本地假市场（JDK 自带 HttpServer）跑目录、详情、下载与哈希校验，
 * 不依赖真实网络。
 */
class MarketClientTest {
    private val client = MarketClient("AstralParty Toys/test")
    private lateinit var server: HttpServer
    private lateinit var workDir: File

    private val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("aspatoys-market").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        workDir.deleteRecursively()
    }

    @Test
    fun `list parses catalog payload`() {
        route("/api/mods") { exchange ->
            respond(exchange, 200, CatalogJson.toByteArray())
        }

        val page = client.list(baseUrl, "zh-Hans", null, null, "downloads", 1, 24)

        assertEquals(2, page.total)
        assertEquals(listOf("工具", "凭据"), page.tags)
        assertEquals(2, page.items.size)
        val first = page.items.first()
        assertEquals("HandViewerMod", first.id)
        assertEquals("队友队友我怎么能看到你的牌", first.name)
        assertEquals("1.2.1", first.latestVersion)
        assertEquals("2.8.6", first.sdkVersion)
        assertEquals(33218L, first.sizeBytes)
        assertEquals(567L, first.downloads)
        assertTrue(first.featured)
        assertEquals("/api/mods/HandViewerMod/cover?v=4a70efc4", first.coverUrl)
    }

    @Test
    fun `detail parses versions`() {
        route("/api/mods/HandViewerMod") { exchange ->
            respond(exchange, 200, DetailJson.toByteArray())
        }

        val detail = client.detail(baseUrl, "HandViewerMod", "zh-Hans")

        assertEquals("HandViewerMod", detail.id)
        assertEquals("1.2.1", detail.latestVersion)
        assertEquals(1, detail.versions.size)
        val version = detail.versions.single()
        assertEquals("1.2.1", version.version)
        assertEquals("2.8.6", version.sdkVersion)
        assertTrue(version.hosted)
        assertEquals(listOf("BaseMod"), version.dependencies.map { it.id })
    }

    @Test
    fun `download writes file and verifies sha256`() {
        val payload = "PK\u0003\u0004 fake zip payload".toByteArray()
        val sha = sha256(payload)
        route("/api/mods/HandViewerMod/download") { exchange -> respond(exchange, 200, payload) }
        val target = File(workDir, "package.zip")

        val progress = mutableListOf<DownloadProgress>()
        val result = client.downloadPackage(
            url = "$baseUrl/api/mods/HandViewerMod/download",
            target = target,
            expectedSha256 = sha,
            onProgress = { progress += it },
        )

        assertTrue(target.isFile)
        assertEquals(payload.size.toLong(), result.sizeBytes)
        assertEquals(sha, result.sha256)
        assertEquals(true, result.sha256Matches)
        assertTrue(progress.isNotEmpty())
        assertEquals(payload.size, target.readBytes().size)
    }

    @Test
    fun `download reports hash mismatch without throwing`() {
        val payload = "some bytes".toByteArray()
        route("/api/mods/HandViewerMod/download") { exchange -> respond(exchange, 200, payload) }
        val target = File(workDir, "mismatch.zip")

        val result = client.downloadPackage(
            url = "$baseUrl/api/mods/HandViewerMod/download",
            target = target,
            expectedSha256 = "deadbeef",
        )

        assertEquals(false, result.sha256Matches)
        assertTrue(target.isFile)
    }

    @Test
    fun `download reports null match when market has no hash`() {
        route("/api/mods/HandViewerMod/download") { exchange -> respond(exchange, 200, "x".toByteArray()) }
        val target = File(workDir, "nohash.zip")

        val result = client.downloadPackage("$baseUrl/api/mods/HandViewerMod/download", target, null)

        assertNull(result.sha256Matches)
    }

    @Test
    fun `download follows redirect to author host`() {
        route("/api/mods/HandViewerMod/download") { exchange ->
            exchange.responseHeaders.add("Location", "/cdn/package.zip")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        route("/cdn/package.zip") { exchange -> respond(exchange, 200, "redirected".toByteArray()) }
        val target = File(workDir, "redirect.zip")

        val result = client.downloadPackage("$baseUrl/api/mods/HandViewerMod/download", target, null)

        assertEquals("redirected", target.readText())
        assertEquals("redirected".length.toLong(), result.sizeBytes)
    }

    @Test
    fun `missing mod maps to user readable error`() {
        route("/api/mods/NopeMod") { exchange -> respond(exchange, 404, "{}".toByteArray()) }
        try {
            client.detail(baseUrl, "NopeMod", "zh-Hans")
            fail("应当抛出 MarketException")
        } catch (expected: MarketException) {
            assertTrue(expected.message!!.contains("没有这个模组"))
        }
    }

    @Test
    fun `download 404 mentions removed version`() {
        route("/api/mods/HandViewerMod/download") { exchange -> respond(exchange, 404, "".toByteArray()) }
        try {
            client.downloadPackage("$baseUrl/api/mods/HandViewerMod/download", File(workDir, "x.zip"), null)
            fail("应当抛出 MarketException")
        } catch (expected: MarketException) {
            assertTrue(expected.message!!.contains("404"))
        }
    }

    @Test
    fun `download respects cancellation`() {
        route("/api/mods/HandViewerMod/download") { exchange ->
            respond(exchange, 200, ByteArray(1024 * 1024) { 7 })
        }
        val cancellation = HttpCancellation()
        cancellation.cancel()

        try {
            client.downloadPackage(
                url = "$baseUrl/api/mods/HandViewerMod/download",
                target = File(workDir, "cancelled.zip"),
                expectedSha256 = null,
                cancellation = cancellation,
            )
            fail("应当抛出取消异常")
        } catch (expected: com.hiynet.aspatoys.core.HttpCancelledException) {
            assertFalse(File(workDir, "cancelled.zip").exists())
        }
    }

    @Test
    fun `cover download accepts only webp`() {
        val webp = ByteArray(32).also {
            listOf('R', 'I', 'F', 'F').forEachIndexed { index, char -> it[index] = char.code.toByte() }
            listOf('W', 'E', 'B', 'P').forEachIndexed { index, char -> it[8 + index] = char.code.toByte() }
        }
        route("/cover.webp") { exchange -> respond(exchange, 200, webp) }
        route("/bad.webp") { exchange -> respond(exchange, 200, "not webp".toByteArray()) }

        assertTrue(client.downloadCover("$baseUrl/cover.webp", File(workDir, "cover.webp")))
        assertFalse(client.downloadCover("$baseUrl/bad.webp", File(workDir, "bad.webp")))
        assertFalse(File(workDir, "bad.webp").exists())
    }

    @Test
    fun `url helpers normalize and resolve`() {
        assertEquals("https://forge.hiynet.com", MarketClient.normalizeBaseUrl(" https://forge.hiynet.com/ "))
        assertEquals(MarketClient.DefaultBaseUrl, MarketClient.normalizeBaseUrl("ftp://x"))
        assertEquals(MarketClient.DefaultBaseUrl, MarketClient.normalizeBaseUrl(""))
        assertEquals("https://a.test/x", MarketClient.resolveUrl("https://a.test", "/x"))
        assertEquals("https://b.test/y", MarketClient.resolveUrl("https://a.test", "https://b.test/y"))
    }

    // ============================== 假市场 ==============================

    private fun route(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.write(body)
        exchange.responseBody.flush()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val CatalogJson = """
            {
              "apiVersion": 1,
              "generatedAt": "2026-11-14T10:05:16Z",
              "total": 2,
              "page": 1,
              "pageSize": 24,
              "tags": ["工具", "凭据"],
              "items": [
                {
                  "id": "HandViewerMod",
                  "name": "队友队友我怎么能看到你的牌",
                  "summary": "神秘",
                  "author": "hpenguins",
                  "tags": [],
                  "coverUrl": "/api/mods/HandViewerMod/cover?v=4a70efc4",
                  "latestVersion": "1.2.1",
                  "sdkVersion": "2.8.6",
                  "sizeBytes": 33218,
                  "sha256": "00aaa56b",
                  "downloadUrl": "/api/mods/HandViewerMod/download",
                  "updatedAt": "2026-11-12T14:42:21Z",
                  "downloads": 567,
                  "downloads7Days": 375,
                  "downloads30Days": 567,
                  "featured": true
                },
                {
                  "id": "AttackNancyLuMod",
                  "name": "集火南希露",
                  "summary": "一键集火",
                  "author": "hpenguins",
                  "tags": ["工具"],
                  "latestVersion": "1.0.1",
                  "sdkVersion": "2.8.6",
                  "downloads": 536
                }
              ]
            }
        """.trimIndent()

        val DetailJson = """
            {
              "id": "HandViewerMod",
              "name": "队友队友我怎么能看到你的牌",
              "summary": "神秘",
              "description": "说明",
              "author": "hpenguins",
              "tags": [],
              "repositoryUrl": "https://github.com/hpenguins/HandViewerMod",
              "license": "AGPL-3.0",
              "licenseText": "",
              "readme": "# README",
              "coverUrl": "/api/mods/HandViewerMod/cover?v=4a70efc4",
              "featured": true,
              "updatedAt": "2026-11-12T14:42:21Z",
              "latestVersion": "1.2.1",
              "downloads": 567,
              "versions": [
                {
                  "version": "1.2.1",
                  "downloadUrl": "/api/mods/HandViewerMod/download",
                  "sourceUrl": "",
                  "hosted": true,
                  "sha256": "00aaa56b",
                  "sizeBytes": 33218,
                  "sdkVersion": "2.8.6",
                  "permissions": 1,
                  "dependencies": [{"id": "BaseMod", "minVersion": "1.0.0"}],
                  "changelog": "修 bug",
                  "publishedAt": "2026-11-12T14:42:21Z",
                  "downloads": 567
                }
              ]
            }
        """.trimIndent()
    }
}
