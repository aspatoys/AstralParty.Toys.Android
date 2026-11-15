package com.hiynet.aspatoys.data.market

import com.hiynet.aspatoys.core.Http
import com.hiynet.aspatoys.core.HttpCancellation
import com.hiynet.aspatoys.core.HttpCancelledException
import com.hiynet.aspatoys.core.HttpRequestException
import com.hiynet.aspatoys.core.HttpStatusException
import com.hiynet.aspatoys.core.boolean
import com.hiynet.aspatoys.core.int
import com.hiynet.aspatoys.core.long
import com.hiynet.aspatoys.core.nullableString
import com.hiynet.aspatoys.core.objectArray
import com.hiynet.aspatoys.core.parseJsonObjectOrNull
import com.hiynet.aspatoys.core.string
import com.hiynet.aspatoys.core.stringArray
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 市场客户端：拉目录、拉详情、下载模组包（边下边算 SHA-256）、缓存封面。
 *
 * 所有网络访问都在这里，界面只拿到整理好的模型。刻意做成阻塞调用：上层用
 * `Dispatchers.IO` 包起来，JVM 单元测试可以直接对着本地假市场跑。
 */
class MarketClient(private val userAgent: String) {

    /** 拉取模组目录。 */
    fun list(
        baseUrl: String,
        language: String,
        query: String?,
        tag: String?,
        sort: String,
        page: Int,
        pageSize: Int,
        cancellation: HttpCancellation? = null,
    ): MarketPage {
        val url = buildString {
            append(normalizeBaseUrl(baseUrl))
            append("/api/mods?lang=").append(encode(language))
            append("&sort=").append(encode(sort))
            append("&page=").append(page.coerceAtLeast(1))
            append("&pageSize=").append(pageSize.coerceIn(1, 60))
            if (!query.isNullOrBlank()) append("&query=").append(encode(query.trim()))
            if (!tag.isNullOrBlank()) append("&tag=").append(encode(tag.trim()))
        }
        val root = requestJson(url, "读取模组目录", cancellation)
        return MarketPage(
            apiVersion = root.int("apiVersion"),
            generatedAt = root.string("generatedAt"),
            total = root.int("total"),
            page = root.int("page").takeIf { it > 0 } ?: page,
            pageSize = root.int("pageSize").takeIf { it > 0 } ?: pageSize,
            tags = root.stringArray("tags"),
            items = root.objectArray("items").map(::readSummary),
        )
    }

    /** 拉取单个模组的详情（含版本列表、README、许可证）。 */
    fun detail(
        baseUrl: String,
        id: String,
        language: String,
        cancellation: HttpCancellation? = null,
    ): MarketModDetail {
        if (id.isBlank()) throw MarketException("缺少模组 ID。")
        val url = "${normalizeBaseUrl(baseUrl)}/api/mods/${encode(id)}?lang=${encode(language)}"
        val root = requestJson(url, "读取模组详情", cancellation)
        return MarketModDetail(
            id = root.string("id"),
            name = root.string("name"),
            summary = root.string("summary"),
            description = root.string("description"),
            author = root.string("author"),
            tags = root.stringArray("tags"),
            repositoryUrl = root.string("repositoryUrl"),
            license = root.string("license"),
            licenseText = root.string("licenseText"),
            readme = root.string("readme"),
            coverUrl = root.nullableString("coverUrl")?.takeIf { it.isNotBlank() },
            featured = root.boolean("featured"),
            createdAt = root.string("createdAt"),
            updatedAt = root.string("updatedAt"),
            latestVersion = root.string("latestVersion"),
            downloads = root.long("downloads"),
            versions = root.objectArray("versions").map { version ->
                MarketModVersion(
                    version = version.string("version"),
                    downloadUrl = version.string("downloadUrl"),
                    sourceUrl = version.string("sourceUrl"),
                    hosted = version.boolean("hosted"),
                    sha256 = version.string("sha256"),
                    sizeBytes = version.long("sizeBytes"),
                    sdkVersion = version.string("sdkVersion"),
                    permissions = version.int("permissions"),
                    dependencies = version.objectArray("dependencies").map { dependency ->
                        MarketDependency(
                            id = dependency.string("id"),
                            minVersion = dependency.nullableString("minVersion") ?: "",
                        )
                    },
                    changelog = version.string("changelog"),
                    publishedAt = version.string("publishedAt"),
                    verifiedAt = version.string("verifiedAt"),
                    downloads = version.long("downloads"),
                    platforms = com.hiynet.aspatoys.data.mods.ModPlatforms.normalize(version.stringArray("platforms")),
                )
            },
        )
    }

    /**
     * 下载模组包到 [target]：边下边算 SHA-256，超过 [MaxPackageBytes] 立刻中止。
     *
     * @param expectedSha256 市场登记的哈希；为空表示不校验（结果里的 sha256Matches 为 null）。
     * @return 落盘结果；哈希不一致不会抛异常，由调用方决定是否拒绝安装。
     */
    fun downloadPackage(
        url: String,
        target: File,
        expectedSha256: String?,
        onProgress: (DownloadProgress) -> Unit = {},
        cancellation: HttpCancellation? = null,
    ): PackageDownloadResult {
        val absolute = url.trim()
        if (absolute.isEmpty()) throw MarketException("缺少下载地址。")
        target.parentFile?.mkdirs()

        val resource = try {
            Http.open(absolute, headers(), connectTimeoutMs = 20_000, readTimeoutMs = 60_000, cancellation = cancellation)
        } catch (error: HttpStatusException) {
            throw MarketException(
                when (error.statusCode) {
                    404 -> "下载地址返回 404：作者可能已删除这个版本。"
                    503 -> "市场暂时关闭了接口，请稍后再试。"
                    else -> "下载失败：${Http.statusMessage(error.statusCode)}"
                },
                error,
            )
        } catch (cancelled: HttpCancelledException) {
            throw cancelled
        } catch (error: HttpRequestException) {
            throw MarketException(error.message ?: "无法连接下载地址。", error)
        }

        val total = resource.contentLength
        if (total != null && total > MaxPackageBytes) {
            resource.close()
            throw MarketException(
                "模组包有 ${"%.1f".format(total / 1024.0 / 1024.0)} MB，超过 ${MaxPackageBytes / 1024 / 1024} MB 上限。",
            )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        var written = false
        try {
            resource.input.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    onProgress(DownloadProgress(0, total))
                    while (true) {
                        cancellation?.throwIfCancelled()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        received += read
                        if (received > MaxPackageBytes) {
                            throw MarketException("下载超过 ${MaxPackageBytes / 1024 / 1024} MB 上限，已中止。")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(DownloadProgress(received, total))
                    }
                    output.flush()
                    written = true
                }
            }
        } finally {
            resource.close()
            if (!written) target.delete()
        }

        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val expected = expectedSha256?.trim().orEmpty()
        return PackageDownloadResult(
            filePath = target.path,
            sha256 = sha,
            sizeBytes = received,
            sha256Matches = if (expected.isEmpty()) null else sha.equals(expected, ignoreCase = true),
            expectedSha256 = expected,
        )
    }

    /**
     * 缓存封面到 [target]；只接受真正的 WebP（反向代理的错误页也可能返回 200）。
     *
     * @return 成功返回 true，失败（网络、格式、大小）返回 false —— 封面只是装饰，不打断列表。
     */
    fun downloadCover(url: String, target: File, cancellation: HttpCancellation? = null): Boolean {
        return try {
            val bytes = Http.getBytes(url, headers(), cancellation = cancellation)
            if (bytes.isEmpty() || bytes.size > MaxCoverBytes) return false
            if (!isWebp(bytes)) return false
            target.parentFile?.mkdirs()
            val part = File(target.path + ".part")
            part.writeBytes(bytes)
            if (target.exists()) target.delete()
            part.renameTo(target)
        } catch (_: Exception) {
            false
        }
    }

    // ============================== 辅助 ==============================

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "application/json",
    )

    private fun requestJson(url: String, action: String, cancellation: HttpCancellation?): kotlinx.serialization.json.JsonObject {
        val text = try {
            Http.getText(url, headers(), connectTimeoutMs = 20_000, readTimeoutMs = 20_000, cancellation = cancellation)
        } catch (cancelled: HttpCancelledException) {
            throw cancelled
        } catch (error: HttpStatusException) {
            throw MarketException(
                when (error.statusCode) {
                    404 -> "$action 失败：市场里没有这个模组。"
                    503 -> "$action 失败：市场暂时关闭了接口，请稍后再试。"
                    else -> "$action 失败：${Http.statusMessage(error.statusCode)}"
                },
                error,
            )
        } catch (error: HttpRequestException) {
            throw MarketException(error.message ?: "$action 失败：无法连接市场。", error)
        }
        return parseJsonObjectOrNull(text) ?: throw MarketException("$action 返回了无法解析的数据。")
    }

    private fun readSummary(root: kotlinx.serialization.json.JsonObject): MarketModSummary = MarketModSummary(
        id = root.string("id"),
        name = root.string("name"),
        summary = root.string("summary"),
        author = root.string("author"),
        tags = root.stringArray("tags"),
        coverUrl = root.nullableString("coverUrl")?.takeIf { it.isNotBlank() },
        latestVersion = root.string("latestVersion"),
        sdkVersion = root.string("sdkVersion"),
        sizeBytes = root.long("sizeBytes"),
        sha256 = root.string("sha256"),
        downloadUrl = root.string("downloadUrl"),
        updatedAt = root.string("updatedAt"),
        downloads = root.long("downloads"),
        downloads7Days = root.long("downloads7Days"),
        downloads30Days = root.long("downloads30Days"),
        featured = root.boolean("featured"),
        platforms = com.hiynet.aspatoys.data.mods.ModPlatforms.normalize(root.stringArray("platforms")),
    )

    companion object {
        /** 默认市场地址；可在设置里改成别的部署地址。 */
        const val DefaultBaseUrl = "https://forge.hiynet.com"

        /** 单张封面上限，与 Forge 的管理端限制一致。 */
        const val MaxCoverBytes = 8 * 1024 * 1024

        /** 单个模组包上限：与桌面版一致，256 MB。 */
        const val MaxPackageBytes = 256L * 1024 * 1024

        /** 规范化用户填写的市场地址；非法输入回退到默认地址。 */
        fun normalizeBaseUrl(value: String?): String {
            val text = (value ?: "").trim().trimEnd('/')
            if (text.isEmpty()) return DefaultBaseUrl
            return try {
                val uri = java.net.URI(text)
                if (uri.scheme != "http" && uri.scheme != "https") DefaultBaseUrl
                else if (uri.host.isNullOrBlank()) DefaultBaseUrl
                else text
            } catch (_: Exception) {
                DefaultBaseUrl
            }
        }

        /** 把目录里的相对下载/封面地址拼成绝对地址；已经是绝对地址就原样返回。 */
        fun resolveUrl(baseUrl: String, url: String?): String {
            if (url.isNullOrBlank()) return ""
            if (url.startsWith("http", ignoreCase = true)) return url
            return "${normalizeBaseUrl(baseUrl)}/${url.trimStart('/')}"
        }

        /** SemVer 风格比较（最多三段数字）；无法解析的段按 0 处理。 */
        fun compareVersions(left: String?, right: String?): Int =
            com.hiynet.aspatoys.data.mods.SdkVersions.compare(left, right)

        fun isWebp(bytes: ByteArray): Boolean =
            bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
