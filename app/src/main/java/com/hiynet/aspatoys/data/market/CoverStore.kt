package com.hiynet.aspatoys.data.market

import com.hiynet.aspatoys.core.HttpCancellation
import java.io.File
import java.security.MessageDigest

/**
 * 市场封面缓存（`cache/market/covers`）。
 *
 * 文件名里带封面版本标识（`/api/mods/{id}/cover?v=xxxx` 的 `v`），换封面后不会读到旧图，
 * 也不需要单独维护失效逻辑 —— 缓存目录可以随时整个删掉。
 */
class CoverStore(private val directory: File) {

    /** 已缓存则返回本地文件，否则 null（界面显示占位）。 */
    fun cached(modId: String, coverUrl: String?): File? {
        if (coverUrl.isNullOrBlank()) return null
        val file = fileFor(modId, coverUrl)
        return if (file.isFile && file.length() > 0) file else null
    }

    /** 确保封面已缓存；成功返回本地文件，失败返回 null。 */
    fun ensure(
        client: MarketClient,
        baseUrl: String,
        modId: String,
        coverUrl: String?,
        cancellation: HttpCancellation? = null,
    ): File? {
        if (coverUrl.isNullOrBlank()) return null
        val file = fileFor(modId, coverUrl)
        if (file.isFile && file.length() > 0) return file
        val absolute = MarketClient.resolveUrl(baseUrl, coverUrl)
        if (absolute.isBlank()) return null
        return if (client.downloadCover(absolute, file, cancellation)) file else null
    }

    fun fileFor(modId: String, coverUrl: String): File {
        val token = coverToken(coverUrl)
        val safeId = sanitize(modId)
        return File(directory, "$safeId-$token.webp")
    }

    /** 从 `/api/mods/{id}/cover?v=4a70efc4` 里取出缓存标识；没有 `v` 时退回 URL 的短哈希。 */
    private fun coverToken(coverUrl: String): String {
        val query = coverUrl.substringAfter('?', "")
        if (query.isNotEmpty()) {
            for (pair in query.split('&')) {
                val equals = pair.indexOf('=')
                if (equals <= 0) continue
                if (!pair.substring(0, equals).equals("v", ignoreCase = true)) continue
                val token = sanitize(pair.substring(equals + 1))
                if (token.isNotEmpty()) return token
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(coverUrl.toByteArray())
        return hash.take(6).joinToString("") { "%02x".format(it) }
    }

    private fun sanitize(value: String): String {
        val cleaned = value.trim().map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
        }.joinToString("")
        return cleaned.ifBlank { "cover" }.take(48)
    }
}
