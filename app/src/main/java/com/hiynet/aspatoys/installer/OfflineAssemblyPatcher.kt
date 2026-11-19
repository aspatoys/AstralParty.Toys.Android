package com.hiynet.aspatoys.installer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Runs the bundled Mono.Cecil editor in a local WebAssembly renderer, without executing game IL. */
internal class OfflineAssemblyPatcher(private val context: Context, private val gamePackage: String) {
    fun patch(input: ByteArray): ByteArray = edit(input, reference = false)
    fun patchReference(input: ByteArray): ByteArray = edit(input, reference = true)

    private fun edit(input: ByteArray, reference: Boolean): ByteArray {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(input.size in 1..MAX_ASSEMBLY_SIZE) { "游戏程序集大小超出编辑范围" }
        val job = Job(context, input, gamePackage, reference)
        job.handler.post { job.start() }
        try {
            return job.result.get(120, TimeUnit.SECONDS)
        } catch (error: java.util.concurrent.TimeoutException) {
            throw IllegalStateException("本地程序集编辑超时。请检查系统 Android System WebView 是否可用。", error)
        } catch (error: java.util.concurrent.ExecutionException) {
            val cause = error.cause
            if (cause is UnsupportedRuntimeEdit) throw cause
            throw IllegalStateException(cause?.message ?: "本地程序集编辑失败", cause)
        } finally {
            job.result.cancel(false)
            job.handler.post { job.close() }
        }
    }

    private class Job(private val context: Context, private val input: ByteArray, gamePackage: String, reference: Boolean) {
        private val options = JSONObject().put("package", gamePackage).put("reference", reference).toString().toByteArray()
        val result = CompletableFuture<ByteArray>()
        val handler = Handler(Looper.getMainLooper())
        private var web: WebView? = null

        @SuppressLint("SetJavaScriptEnabled")
        fun start() {
            if (result.isDone) return
            try {
                web = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    // Every request is fulfilled from the APK or the single input buffer.
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val url = request.url
                            val name = url.path?.removePrefix("/").orEmpty()
                            if (url.scheme != "https" || url.host != HOST || url.port != -1 ||
                                request.method != "GET" || !ASSET_NAME.matches(name) || name == "." || name == ".."
                            ) return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), null)
                            val stream = when (name) {
                                "runtime-input" -> ByteArrayInputStream(input)
                                "runtime-options.json" -> ByteArrayInputStream(options)
                                else -> runCatching { context.assets.open("cesium/patcher/$name") }.getOrNull()
                            }
                            if (stream == null)
                                return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), null)
                            val mime = when (name.substringAfterLast('.')) {
                                "html" -> "text/html"
                                "js", "mjs" -> "text/javascript"
                                "json" -> "application/json"
                                "wasm" -> "application/wasm"
                                else -> "application/octet-stream"
                            }
                            return WebResourceResponse(mime, if (mime.startsWith("text/")) "UTF-8" else null, stream)
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) fail("本地缓存编辑器无法启动：${error.description}")
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            fail("本地缓存编辑器的 WebView 进程退出")
                            close()
                            return true
                        }
                    }
                }
                web!!.loadUrl("https://$HOST/index.html")
                poll()
            } catch (error: Exception) {
                fail("无法启动本地程序集编辑器：${error.message}")
            }
        }

        private fun poll() {
            if (result.isDone) return
            web?.evaluateJavascript("globalThis.patchStatus ?? null") { value ->
                if (result.isDone) return@evaluateJavascript
                try {
                    if (value == "null" || value == null) {
                        handler.postDelayed(::poll, 200)
                    } else {
                        val status = JSONObject(value)
                        if (status.has("error")) {
                            val message = status.getString("error")
                            if (status.optBoolean("unsupported")) result.completeExceptionally(UnsupportedRuntimeEdit(message))
                            else fail(message)
                        } else {
                            val size = status.getInt("size")
                            require(size in 1..MAX_ASSEMBLY_SIZE) { "编辑后的程序集大小无效" }
                            collect(size, ByteArrayOutputStream(size))
                        }
                    }
                } catch (error: Exception) {
                    fail(error.message ?: "无法读取程序集编辑结果")
                }
            }
        }

        private fun collect(size: Int, output: ByteArrayOutputStream) {
            if (result.isDone) return
            val offset = output.size()
            val length = minOf(size - offset, 192 * 1024)
            web?.evaluateJavascript("globalThis.patchChunk($offset,$length)") { value ->
                if (result.isDone) return@evaluateJavascript
                try {
                    val bytes = Base64.getDecoder().decode(JSONTokener(value).nextValue() as String)
                    require(bytes.size == length) { "程序集回读长度不符" }
                    output.write(bytes)
                    if (output.size() == size) result.complete(output.toByteArray()) else collect(size, output)
                } catch (error: Exception) {
                    fail(error.message ?: "读取编辑后程序集失败")
                }
            }
        }

        private fun fail(message: String) = result.completeExceptionally(IllegalStateException(message))

        fun close() {
            web?.destroy()
            web = null
        }
    }

    private companion object {
        const val MAX_ASSEMBLY_SIZE = 32 * 1024 * 1024
        const val HOST = "cesium-patcher.invalid"
        val ASSET_NAME = Regex("[A-Za-z0-9_.-]{1,128}")
    }
}
