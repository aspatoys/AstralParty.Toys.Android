package com.hiynet.aspatoys.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** HTTP 状态码不是 2xx 时抛出，调用方据此给出用户可读的说明。 */
class HttpStatusException(val statusCode: Int, message: String) : IOException(message)

/**
 * 极小的 HTTP 客户端：只做 GET + 手动跟随重定向 + 流式读取。
 *
 * 为什么不用 OkHttp：市场只有一个客户端的少量 GET，自己写可以零依赖、且同一份代码能在
 * JVM 单元测试里直接跑（测试用本地 ServerSocket 起一个假市场）。
 */
object Http {
    /** 市场请求的 User-Agent：Forge 用它统计客户端版本，格式与桌面版一致。 */
    fun userAgent(versionName: String): String = "AstralParty Toys/$versionName"

    /** 打开一个 GET 请求；跟随最多 [maxRedirects] 次重定向（市场包会 302 到作者托管的地址）。 */
    fun open(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 20_000,
        maxRedirects: Int = 8,
        cancellation: HttpCancellation? = null,
    ): HttpResource {
        var current = url
        repeat(maxRedirects + 1) { attempt ->
            cancellation?.throwIfCancelled()
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                connection.connect()
            } catch (error: IOException) {
                connection.disconnect()
                throw HttpRequestException("连接失败：${error.message ?: error.javaClass.simpleName}", error)
            }
            cancellation?.bind(connection)
            val code = try {
                connection.responseCode
            } catch (timeout: SocketTimeoutException) {
                connection.disconnect()
                throw HttpRequestException("连接超时：请检查网络或市场地址。", timeout)
            }

            if (code in 300..399 && attempt < maxRedirects) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw HttpStatusException(code, "服务端返回了 $code 但没有给出跳转地址。")
                }
                current = URL(URL(current), location).toString()
                return@repeat
            }

            if (code !in 200..299) {
                connection.disconnect()
                throw HttpStatusException(code, statusMessage(code))
            }

            val stream = try {
                connection.inputStream
            } catch (error: IOException) {
                connection.disconnect()
                throw HttpRequestException("读取响应失败：${error.message}", error)
            }
            val length = connection.contentLengthLong.takeIf { it > 0 }
            return HttpResource(code, length, connection.contentType, stream) {
                cancellation?.unbind(connection)
                connection.disconnect()
            }
        }

        throw HttpRequestException("重定向次数过多（超过 $maxRedirects 次）。")
    }

    /** 一次性读取整个响应体（用于 JSON 与封面，条数少、体积有上限）。 */
    fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 20_000,
        cancellation: HttpCancellation? = null,
    ): ByteArray = open(url, headers, connectTimeoutMs, readTimeoutMs, cancellation = cancellation).use { resource ->
        val buffer = ByteArray(64 * 1024)
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            cancellation?.throwIfCancelled()
            val count = resource.input.read(buffer)
            if (count <= 0) break
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    /** 读取文本响应（UTF-8；市场接口都是 UTF-8 JSON）。 */
    fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 20_000,
        cancellation: HttpCancellation? = null,
    ): String = String(getBytes(url, headers, connectTimeoutMs, readTimeoutMs, cancellation), Charsets.UTF_8)

    internal fun statusMessage(code: Int): String = when (code) {
        404 -> "服务器上找不到这个资源（HTTP 404）。"
        410 -> "该资源已被移除（HTTP 410）。"
        503 -> "服务暂时不可用（HTTP 503），请稍后再试。"
        else -> "请求失败：HTTP $code"
    }
}

/** 一个已建立连接的响应；[close] 会断开连接（取消下载就是靠它立刻停止阻塞的 read）。 */
class HttpResource(
    val statusCode: Int,
    val contentLength: Long?,
    val contentType: String?,
    val input: InputStream,
    private val onClose: () -> Unit,
) : Closeable {
    override fun close() {
        try {
            input.close()
        } catch (_: IOException) {
            // 关闭失败不影响调用方
        }
        onClose()
    }
}

/** 网络层错误（连接失败、读取中断等），文案已经是用户可读的中文。 */
class HttpRequestException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 下载取消把手：设置取消标志并断开当前连接，让阻塞中的 [InputStream.read] 立刻返回。
 *
 * 协程取消之外还需要它：`Dispatchers.IO` 不会中断线程，Socket 读取会一直等到超时。
 */
class HttpCancellation {
    @Volatile
    var isCancelled: Boolean = false
        private set

    @Volatile
    private var connection: HttpURLConnection? = null

    fun cancel() {
        isCancelled = true
        connection?.disconnect()
    }

    fun throwIfCancelled() {
        if (isCancelled) throw HttpCancelledException()
    }

    internal fun bind(current: HttpURLConnection) {
        connection = current
        if (isCancelled) current.disconnect()
    }

    internal fun unbind(current: HttpURLConnection) {
        if (connection === current) connection = null
    }
}

/** 用户主动取消下载；上层把它显示成「已取消」而不是错误。 */
class HttpCancelledException : IOException("已取消。")
