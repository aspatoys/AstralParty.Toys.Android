package com.hiynet.aspatoys.data.updates

import com.hiynet.aspatoys.core.HttpCancellation
import com.hiynet.aspatoys.core.HttpCancelledException
import com.hiynet.aspatoys.core.HttpResource
import com.hiynet.aspatoys.core.HttpStatusException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/** Exercises production [GitHubUpdates] parsing, mirror mapping and download with an injected transport. */
class GitHubUpdatesTest {
    private val sha = "a".repeat(64)
    private val loaderUrl = "https://github.com/higashitaniyume/CesiumLoader/releases/download/modloader-3.8.7/cesium-loader-3.8.7.zip"
    private val apkUrl = "https://github.com/higashitaniyume/AstralParty.Toys.Android/releases/download/v1.3.0/aspatoys-1.3.0.apk"

    private fun resource(body: ByteArray) = HttpResource(200, body.size.toLong(), "application/json", ByteArrayInputStream(body)) {}

    private inner class Server(private val respond: (String) -> Any) {
        val requested = mutableListOf<String>()
        fun open(url: String, headers: Map<String, String>, cancel: HttpCancellation?): HttpResource {
            requested += url
            return when (val value = respond(url)) {
                is Throwable -> throw value
                is String -> resource(value.toByteArray())
                is ByteArray -> resource(value)
                else -> throw IllegalStateException("no route for $url")
            }
        }
    }

    private fun updates(server: Server, mirror: () -> Pair<String, String> = { "direct" to "" }) =
        GitHubUpdates(mirror, "1.3.0", server::open)

    private fun manifest(version: String = "3.8.7", key: String = "loaderZip", url: String = loaderUrl,
                         hash: String = sha, size: Long = 10L, file: String = "cesium-loader-3.8.7.zip") = """
        {"version":"$version","releaseTag":"modloader-$version","releaseNotes":"n",
         "packages":{"$key":{"fileName":"$file","url":"$url","sha256":"$hash","size":$size}}}
    """.trimIndent()

    private fun api(tag: String = "modloader-3.8.7", digest: String? = "sha256:$sha", prerelease: Boolean = false,
                    extraAssets: String = "") = """
        {"tag_name":"$tag","html_url":"https://github.com/higashitaniyume/CesiumLoader/releases/tag/$tag","body":"b",
         "draft":false,"prerelease":$prerelease,
         "assets":[{"name":"cesium-loader.zip","browser_download_url":"$loaderUrl","size":10${digest?.let { ",\"digest\":\"$it\"" } ?: ""}}$extraAssets]}
    """.trimIndent()

    @Test fun `manifest is parsed with page notes and size`() {
        val release = GitHubUpdates.parseManifest(manifest(), GitHubUpdates.LOADER_REPO, true)
        assertEquals("3.8.7", release.version)
        assertEquals(loaderUrl, release.url)
        assertEquals(sha, release.sha256)
        assertEquals("cesium-loader-3.8.7.zip", release.name)
        assertEquals(10L, release.size)
        assertEquals("https://github.com/higashitaniyume/CesiumLoader/releases/tag/modloader-3.8.7", release.page)
    }

    @Test fun `apk manifest uses the android package and rejects foreign hosts`() {
        val apk = GitHubUpdates.parseManifest(manifest(key = "androidApk", url = apkUrl, file = "aspatoys-1.3.0.apk"),
            GitHubUpdates.APP_REPO, false)
        assertEquals(apkUrl, apk.url)
        for (bad in listOf(
            manifest(key = "androidApk", url = apkUrl),
            manifest(url = "http://github.com/higashitaniyume/CesiumLoader/releases/download/x/y.zip"),
            manifest(url = "https://evil.example/CesiumLoader/releases/download/x/y.zip"),
            manifest(hash = "zz"),
            manifest(version = "not-a-version"),
            """{"version":"3.8.7"}""",
            "not json",
        )) {
            try { GitHubUpdates.parseManifest(bad, GitHubUpdates.LOADER_REPO, true); fail("accepted: $bad") }
            catch (_: Exception) { }
        }
    }

    @Test fun `newer compares semver and refuses unparseable latest`() {
        assertTrue(GitHubUpdates.newer("3.8.7", "3.8.6"))
        assertTrue(GitHubUpdates.newer("3.10.0", "3.9.9"))
        assertFalse(GitHubUpdates.newer("3.8.7", "3.8.7"))
        assertFalse(GitHubUpdates.newer("garbage", "3.8.6"))
    }

    @Test fun `mirror mapping matches the desktop loader options`() {
        assertEquals(loaderUrl, GitHubUpdates.mirrorUrl(loaderUrl, "direct", ""))
        assertEquals("https://gh-proxy.com/$loaderUrl", GitHubUpdates.mirrorUrl(loaderUrl, "gh-proxy", ""))
        assertEquals("https://gh-proxy.com/$loaderUrl", GitHubUpdates.mirrorUrl(loaderUrl, "unknown", ""))
        assertEquals("https://ghfast.top/$loaderUrl", GitHubUpdates.mirrorUrl(loaderUrl, "ghfast", ""))
        assertEquals("https://mirror.example/p/$loaderUrl", GitHubUpdates.mirrorUrl(loaderUrl, "custom", "https://mirror.example/p/"))
        assertEquals("https://mirror.example/$loaderUrl", GitHubUpdates.mirrorUrl(loaderUrl, "custom", " mirror.example "))
        for (bad in listOf("", "   ", "http://plain.example", "https://x.example/?a=1", "https://x.example/#f", "https://user:pw@x.example")) {
            try { GitHubUpdates.mirrorUrl(loaderUrl, "custom", bad); fail("accepted mirror: $bad") } catch (_: Exception) { }
        }
    }

    @Test fun `static manifest is preferred and mirror prefix is applied`() {
        val server = Server { manifest() }
        val release = updates(server) { "gh-proxy" to "" }.check(true)
        assertEquals("3.8.7", release.version)
        assertEquals("https://gh-proxy.com/https://github.com/higashitaniyume/CesiumLoader/releases/latest/download/version.json", server.requested.single())
    }

    @Test fun `api fallback feeds download url hash and version`() {
        val server = Server { url -> if (url.contains("releases/latest/download/version.json")) HttpStatusException(404, "x") else api() }
        val release = updates(server).check(true)
        assertEquals("3.8.7", release.version)
        assertEquals(loaderUrl, release.url)
        assertEquals(sha, release.sha256)
        assertTrue(server.requested[1].endsWith("api.github.com/repos/higashitaniyume/CesiumLoader/releases/latest"))
    }

    @Test fun `api fallback reads the sha256 sidecar when the digest is absent`() {
        val checksum = "https://github.com/higashitaniyume/CesiumLoader/releases/download/modloader-3.8.7/cesium-loader.zip.sha256"
        val server = Server { url ->
            when {
                url.contains("version.json") -> HttpStatusException(500, "x")
                url.contains("api.github.com") -> api(digest = null,
                    extraAssets = """,
                        {"name":"cesium-loader.zip.sha256","browser_download_url":"$checksum","size":80}""")
                url == checksum -> "  $sha  cesium-loader.zip\n"
                else -> HttpStatusException(404, "x")
            }
        }
        assertEquals(sha, updates(server).check(true).sha256)
    }

    @Test fun `api fallback rejects prerelease and reports both failures`() {
        val server = Server { url -> if (url.contains("version.json")) HttpStatusException(404, "x") else api(prerelease = true) }
        try { updates(server).check(true); fail("prerelease accepted") }
        catch (error: Exception) {
            assertTrue(error.message!!.contains("正式版"))
            assertEquals(1, error.suppressed.size)
        }
    }

    @Test fun `download writes only a verified file`() = withTemp { dir ->
        val body = "loader-bytes".toByteArray()
        val release = UpdateRelease("3.8.7", "p", "n", "cesium-loader.zip", loaderUrl, sha256(body), body.size.toLong())
        val destination = File(dir, "loader.zip")
        updates(Server { body }).download(release, destination, HttpCancellation()) { _, _ -> }
        assertTrue(destination.readBytes().contentEquals(body))
        assertFalse(File(dir, "loader.zip.part").exists())
    }

    @Test fun `hash or length failure never publishes a partial file`() = withTemp { dir ->
        val body = "loader-bytes".toByteArray()
        val destination = File(dir, "loader.zip")
        val wrongHash = UpdateRelease("3.8.7", "p", "n", "cesium-loader.zip", loaderUrl, sha256("other".toByteArray()), body.size.toLong())
        try { updates(Server { body }).download(wrongHash, destination, HttpCancellation()) { _, _ -> }; fail("hash accepted") } catch (_: Exception) { }
        assertFalse(destination.exists()); assertFalse(File(dir, "loader.zip.part").exists())

        val shortRelease = UpdateRelease("3.8.7", "p", "n", "cesium-loader.zip", loaderUrl, sha256(body), body.size + 1L)
        try { updates(Server { body }).download(shortRelease, destination, HttpCancellation()) { _, _ -> }; fail("short accepted") } catch (_: Exception) { }
        assertFalse(destination.exists())

        val huge = UpdateRelease("3.8.7", "p", "n", "cesium-loader.zip", loaderUrl, sha256(body), 600L * 1024 * 1024)
        try { updates(Server { body }).download(huge, destination, HttpCancellation()) { _, _ -> }; fail("oversize accepted") } catch (_: Exception) { }
        assertFalse(destination.exists())
    }

    @Test fun `cancel aborts download without a destination`() = withTemp { dir ->
        val body = "loader-bytes".toByteArray()
        val release = UpdateRelease("3.8.7", "p", "n", "cesium-loader.zip", loaderUrl, sha256(body), body.size.toLong())
        val destination = File(dir, "loader.zip")
        val cancel = HttpCancellation().apply { cancel() }
        try { updates(Server { body }).download(release, destination, cancel) { _, _ -> }; fail("cancel ignored") }
        catch (_: HttpCancelledException) { }
        assertFalse(destination.exists())
    }

    @Test fun `download refuses an asset outside the official repository`() = withTemp { dir ->
        val release = UpdateRelease("3.8.7", "p", "n", "cesium-loader.zip",
            "https://github.com/someone/else/releases/download/x/y.zip", sha, 1)
        try { updates(Server { "x".toByteArray() }).download(release, File(dir, "l.zip"), HttpCancellation()) { _, _ -> }; fail("foreign url accepted") }
        catch (_: Exception) { }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun withTemp(block: (File) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("ghu").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
