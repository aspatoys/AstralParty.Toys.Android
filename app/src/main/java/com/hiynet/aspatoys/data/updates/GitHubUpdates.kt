package com.hiynet.aspatoys.data.updates

import com.hiynet.aspatoys.core.Http
import com.hiynet.aspatoys.core.HttpCancellation
import com.hiynet.aspatoys.core.HttpResource
import com.hiynet.aspatoys.core.parseJsonObjectOrNull
import com.hiynet.aspatoys.core.string
import com.hiynet.cesiumloader.core.SemVer
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.security.MessageDigest

data class UpdateRelease(val version: String, val page: String, val notes: String, val name: String,
    val url: String, val sha256: String, val size: Long)

/** Same loader endpoints and mirror choices as the desktop client; APK updates use the Android repo. */
class GitHubUpdates(
    private val mirror: () -> Pair<String, String>,
    private val version: String,
    private val openResource: (String, Map<String, String>, HttpCancellation?) -> HttpResource = { url, headers, cancel ->
        Http.open(url, headers, cancellation = cancel, requireHttps = true)
    },
) {
    companion object {
        const val LOADER_REPO = "higashitaniyume/CesiumLoader"
        const val APP_REPO = "higashitaniyume/AstralParty.Toys.Android"
        const val LOADER_PACKAGE = "https://github.com/$LOADER_REPO/releases/latest/download/cesium-loader.zip"
        const val LOADER_MANIFEST = "https://github.com/$LOADER_REPO/releases/latest/download/version.json"
        const val LOADER_API = "https://api.github.com/repos/$LOADER_REPO/releases/latest"
        fun newer(latest: String, installed: String): Boolean {
            val a = SemVer.parse(latest) ?: return false
            val b = SemVer.parse(installed) ?: return true
            return a > b
        }
        fun mirrorUrl(url: String, type: String, custom: String): String {
            val prefix = when (type) {
                "direct" -> ""
                "ghfast" -> "https://ghfast.top/"
                "custom" -> custom.trim().let { raw -> if (raw.isBlank()) error("请输入 GitHub 镜像地址。") else if ("://" in raw) { require(raw.startsWith("https://")) { "镜像地址须为 HTTPS 前缀。" }; raw } else "https://$raw" }.trimEnd('/').let { it + "/" }
                else -> "https://gh-proxy.com/"
            }
            if (prefix.isNotEmpty()) {
                val uri = URI(prefix)
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "镜像地址须为 HTTPS 前缀。" }
            }
            return prefix + url
        }
        fun verifyAssetUrl(url: String, repo: String) {
            require(url.startsWith("https://github.com/$repo/releases/download/")) { "更新文件不属于官方仓库。" }
        }
        fun hash(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun parseManifest(raw: String, repo: String, loader: Boolean): UpdateRelease {
            val root = parseJsonObjectOrNull(raw) ?: error("更新清单不是有效 JSON。")
            val ver = root.string("version")
            require(SemVer.parse(ver) != null) { "更新版本号无效。" }
            val pkg = (root["packages"] as? JsonObject)?.get(if (loader) "loaderZip" else "androidApk") as? JsonObject
                ?: error("清单没有对应平台的更新包。")
            val url = pkg.string("url"); verifyAssetUrl(url, repo)
            val sha = pkg.string("sha256").lowercase()
            require(sha.matches(Regex("[0-9a-f]{64}"))) { "更新包缺少 SHA-256。" }
            return UpdateRelease(ver, "https://github.com/$repo/releases/tag/" + root.string("releaseTag"),
                root.string("releaseNotes"), pkg.string("fileName"), url, sha, (pkg["size"] as? JsonPrimitive)?.longOrNull ?: 0)
        }
    }

    private fun mapped(url: String) = mirror().let { mirrorUrl(url, it.first, it.second) }
    private val headers get() = mapOf("User-Agent" to Http.userAgent(version), "Accept" to "application/json")
    fun text(url: String, cancel: HttpCancellation? = null): String = openResource(mapped(url), headers, cancel).use { response ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            cancel?.throwIfCancelled()
            val n = response.input.read(buffer); if (n < 0) break
            require(output.size() + n <= 2 * 1024 * 1024) { "更新清单超过大小限制。" }
            output.write(buffer, 0, n)
        }
        output.toByteArray().toString(Charsets.UTF_8)
    }
    fun check(loader: Boolean, cancel: HttpCancellation? = null): UpdateRelease {
        val repo = if (loader) LOADER_REPO else APP_REPO
        try { return parseManifest(text("https://github.com/$repo/releases/latest/download/version.json", cancel), repo, loader) }
        catch (first: Exception) {
            cancel?.throwIfCancelled()
            try {
                val root = parseJsonObjectOrNull(text("https://api.github.com/repos/$repo/releases/latest", cancel)) ?: error("GitHub 响应无效。")
                require((root["draft"] as? JsonPrimitive)?.booleanOrNull != true && (root["prerelease"] as? JsonPrimitive)?.booleanOrNull != true) { "最新版本不是正式版。" }
                val ver = root.string("tag_name").removePrefix("modloader-").removePrefix("v")
                require(SemVer.parse(ver) != null) { "GitHub 版本号无效。" }
                val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val name = if (loader) "cesium-loader.zip" else "aspatoys-$ver.apk"
                val asset = assets.firstOrNull { it.string("name") == name } ?: error("最新发行版没有 $name。")
                val url = asset.string("browser_download_url"); verifyAssetUrl(url, repo)
                var sha = asset.string("digest").removePrefix("sha256:").lowercase()
                if (!sha.matches(Regex("[0-9a-f]{64}"))) {
                    val checksum = assets.firstOrNull { it.string("name") == "$name.sha256" } ?: error("发行版缺少更新包校验值。")
                    val checksumUrl = checksum.string("browser_download_url"); verifyAssetUrl(checksumUrl, repo)
                    sha = text(checksumUrl, cancel).trim().substringBefore(' ').substringBefore('\n').lowercase()
                }
                require(sha.matches(Regex("[0-9a-f]{64}"))) { "SHA-256 格式无效。" }
                return UpdateRelease(ver, root.string("html_url"), root.string("body"), name, url, sha,
                    (asset["size"] as? JsonPrimitive)?.longOrNull ?: 0)
            } catch (second: Exception) { second.addSuppressed(first); throw second }
        }
    }
    fun download(release: UpdateRelease, destination: File, cancel: HttpCancellation, progress: (Long, Long) -> Unit) {
        verifyAssetUrl(release.url, if (release.name.endsWith(".apk")) APP_REPO else LOADER_REPO)
        val max = if (release.name.endsWith(".apk")) 256L * 1024 * 1024 else 512L * 1024 * 1024
        require(release.size in 0..max) { "更新包超过大小限制。" }
        destination.parentFile?.mkdirs()
        val temp = File(destination.path + ".part")
        try {
            openResource(mapped(release.url), headers, cancel).use { response ->
                var count = 0L
                response.input.use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        cancel.throwIfCancelled()
                        val n = input.read(buffer); if (n < 0) break
                        count += n
                        require(count <= max && (release.size == 0L || count <= release.size)) { "更新包长度异常。" }
                        output.write(buffer, 0, n); progress(count, release.size)
                    }
                } }
                require(count > 0 && (release.size == 0L || count == release.size)) { "更新包下载不完整。" }
            }
            check(hash(temp).equals(release.sha256, true)) { "更新包 SHA-256 不符，已拒绝使用。" }
            java.nio.file.Files.move(temp.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
}
