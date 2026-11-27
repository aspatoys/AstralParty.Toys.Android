package com.hiynet.aspatoys.ui.customize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hiynet.aspatoys.data.AppContainer
import com.hiynet.aspatoys.data.background.HomeBackgroundRepository
import com.hiynet.aspatoys.data.background.HomeBackgroundVideoTranscoder
import com.hiynet.aspatoys.ui.components.Operations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

class HomeBackgroundController(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    context: Context,
) {
    private val app = context.applicationContext
    private val workDir = File(app.filesDir, "customization/homebg")
    private val repo = HomeBackgroundRepository(
        loader = container.loader,
        previewCacheDir = File(app.cacheDir, "homebg-preview"),
    )

    var status by mutableStateOf(HomeBackgroundRepository.Status())
        private set

    var busy by mutableStateOf(false)
        private set

    var notice by mutableStateOf<String?>(null)
        private set

    var failed by mutableStateOf(false)
        private set

    var videoMaxHeight by mutableIntStateOf(720)
        private set

    private var lastRoot = ""
    private var lastLoaderVersion = -1

    fun enter() {
        val root = container.settings.loaderRootPath
        val version = container.loader.version
        if (lastRoot != root || lastLoaderVersion != version) {
            refresh(quiet = lastRoot == "")
        }
    }

    fun selectVideoResolution(maxHeight: Int) {
        videoMaxHeight = if (maxHeight >= 1080) 1080 else 720
    }

    fun clearNotice() {
        notice = null
    }

    fun refresh(quiet: Boolean = false) {
        if (busy) return
        busy = true
        if (!quiet) {
            notice = null
            failed = false
        }
        scope.launch {
            try {
                status = withContext(Dispatchers.IO) { repo.inspect() }
                lastRoot = container.settings.loaderRootPath
                lastLoaderVersion = container.loader.version
            } catch (error: Exception) {
                if (!quiet) {
                    failed = true
                    notice = error.message ?: "读取主页背景状态失败。"
                }
            } finally {
                busy = false
            }
        }
    }

    fun setImage(uri: Uri) {
        if (busy) return
        val op = Operations.start("设置主页背景图片") ?: return
        busy = true
        notice = null
        failed = false
        scope.launch {
            workDir.mkdirs()
            val rawTemp = File(workDir, "img-src-${UUID.randomUUID()}.tmp")
            val pngTemp = File(workDir, "img-out-${UUID.randomUUID()}.png")
            var bitmap: Bitmap? = null
            try {
                withContext(Dispatchers.IO) {
                    op.status("正在读取图片…")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        rawTemp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取所选图片。")

                    op.status("正在调整尺寸并编码 PNG…")
                    bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(rawTemp)) { decoder, info, _ ->
                        val w = info.size.width
                        val h = info.size.height
                        require(w > 0 && h > 0) { "图片尺寸无效。" }
                        val scale = minOf(1f, 1920f / w.toFloat(), 1080f / h.toFloat())
                        decoder.setTargetSize(
                            (w * scale).roundToInt().coerceAtLeast(1),
                            (h * scale).roundToInt().coerceAtLeast(1),
                        )
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    pngTemp.outputStream().use { output ->
                        check(bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法编码 PNG 图片。" }
                    }

                    op.status("正在写入加载器目录…")
                    repo.setImage(pngTemp)
                    status = repo.inspect()
                    lastRoot = container.settings.loaderRootPath
                    lastLoaderVersion = container.loader.version
                }
                notice = "已更换主页背景图片（回到游戏主页或重启游戏后生效）。"
                op.succeed("已更换主页背景图片，回到游戏主页或重启游戏后生效。")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed = true
                notice = error.message ?: "设置图片失败。"
                op.fail(notice!!)
            } finally {
                bitmap?.recycle()
                rawTemp.delete()
                pngTemp.delete()
                busy = false
            }
        }
    }

    fun setVideo(uri: Uri) {
        if (busy) return
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val targetHeight = videoMaxHeight
        val op = Operations.start("导入主页背景视频（${targetHeight}p）", onCancel = { cancelled.set(true) }) ?: return
        busy = true
        notice = null
        failed = false
        scope.launch {
            workDir.mkdirs()
            val srcTemp = File(workDir, "vid-src-${UUID.randomUUID()}.tmp")
            val mp4Temp = File(workDir, "vid-out-${UUID.randomUUID()}.mp4")
            try {
                withContext(Dispatchers.IO) {
                    op.status("正在读取视频文件…")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        srcTemp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取所选视频。")

                    op.status("正在转码为流畅 H.264（${targetHeight}p · 最高 60fps）…")
                    HomeBackgroundVideoTranscoder.transcode(
                        inputFile = srcTemp,
                        outputFile = mp4Temp,
                        maxHeight = targetHeight,
                        onProgress = { fraction ->
                            val pct = (fraction * 100f).toInt().coerceIn(0, 100)
                            op.progress(fraction, "$pct%")
                        },
                        isCancelled = { cancelled.get() },
                    )
                    if (cancelled.get()) error("已取消导入视频。")

                    op.allowCancel(false)
                    op.status("正在推送到游戏加载器目录…")
                    op.progress(null, null)
                    repo.setVideo(mp4Temp)
                    status = repo.inspect()
                    lastRoot = container.settings.loaderRootPath
                    lastLoaderVersion = container.loader.version
                }
                notice = "背景视频已导入（${targetHeight}p H.264），完全重启游戏后生效。"
                op.succeed("背景视频已导入，完全重启游戏后生效。")
            } catch (cancelledEx: CancellationException) {
                throw cancelledEx
            } catch (error: Exception) {
                if (cancelled.get()) {
                    op.cancelled("已取消导入视频。")
                } else {
                    failed = true
                    notice = error.message ?: "导入视频失败。"
                    op.fail(notice!!)
                }
            } finally {
                srcTemp.delete()
                mp4Temp.delete()
                busy = false
            }
        }
    }

    fun setHideThemeVideo(hide: Boolean) = runSimple("正在保存设置…") {
        repo.setHideThemeVideo(hide)
        notice = if (hide) "已设置：隐藏官方主题背景动画。" else "已设置：保留官方主题背景动画。"
    }

    fun setScaleMode(mode: HomeBackgroundRepository.ScaleMode) = runSimple("正在保存显示模式…") {
        repo.setScaleMode(mode)
        notice = "已切换显示模式为「${mode.label}」（回到游戏主页或重启游戏后生效）。"
    }

    fun enableMod() = runSimple("正在启用自定义主页背景模组…") {
        repo.enableMod()
        notice = "已启用自定义主页背景模组，重启游戏后生效。"
    }

    fun restoreDefault() = runSimple("正在恢复游戏默认主页背景…") {
        repo.restoreDefault()
        notice = "已停用自定义主页背景模组并恢复游戏默认背景（原背景文件仍保留）。"
    }

    private fun runSimple(progressLabel: String, action: () -> Unit) {
        if (busy) return
        busy = true
        notice = progressLabel
        failed = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    action()
                    status = repo.inspect()
                    lastRoot = container.settings.loaderRootPath
                    lastLoaderVersion = container.loader.version
                }
            } catch (error: Exception) {
                failed = true
                notice = error.message ?: "操作失败。"
            } finally {
                busy = false
            }
        }
    }
}
