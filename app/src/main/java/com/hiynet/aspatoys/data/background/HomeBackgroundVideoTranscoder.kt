package com.hiynet.aspatoys.data.background

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 使用 Android 原生 MediaCodec + EGL/GLES 将任意视频标准化为：
 * - H.264 (video/avc) MP4，无音轨（主页背景本身静音）
 * - 分辨率按比例缩放至 <= maxHeight（720p 或 1080p），宽高 16 像素对齐，旋转已烘焙进像素
 * - 完整保留 60fps 及以下原帧率（超 60fps 自动抽帧），I 帧间隔 1 秒（循环 seek 秒切无黑帧）
 *
 * 对已符合规格的 H.264 视频优先走无损抽轨/直拷；若设备 EGL 不支持 Surface 编码，
 * 针对 <= 50MB 的 H.264 源视频自动回退为无损去音轨封装（直拷/直抽上限 40MB）。
 */
object HomeBackgroundVideoTranscoder {

    private const val TimeoutUs = 10_000L
    private const val MinFrameIntervalUs = 14_000L // ~71fps 阈值，完整保留 60fps 及以下原帧率，仅对 120fps 等超高帧率抽帧
    private const val MaxFastCopyBytes = 40L * 1024 * 1024
    private const val MaxRemuxFallbackBytes = 50L * 1024 * 1024
    private const val EglRecordableAndroid = 0x3142

    data class Spec(
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val rotation: Int,
        val mime: String,
        val hasAudio: Boolean,
        val frameRate: Int,
    )

    fun transcode(
        inputFile: File,
        outputFile: File,
        maxHeight: Int = 720,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        require(inputFile.isFile && inputFile.length() > 0L) { "视频文件不存在或为空。" }
        val spec = probe(inputFile)
        val (targetW, targetH) = computeTargetSize(spec.width, spec.height, spec.rotation, maxHeight)
        val fileBytes = inputFile.length()

        if (canFastCopy(spec, targetW, targetH, fileBytes)) {
            onProgress(0.5f)
            inputFile.copyTo(outputFile, overwrite = true)
            onProgress(1f)
            return
        }

        if (canFastRemux(spec, maxHeight, fileBytes)) {
            remuxVideoOnly(inputFile, outputFile, spec, onProgress, isCancelled)
            return
        }

        val temp = File(outputFile.parentFile, outputFile.name + ".transcode.tmp")
        temp.delete()
        try {
            runTranscode(inputFile, temp, spec, targetW, targetH, onProgress, isCancelled)
            if (isCancelled()) error("已取消视频处理。")
            if (outputFile.exists()) outputFile.delete()
            check(temp.renameTo(outputFile)) { "保存转码视频失败。" }
            onProgress(1f)
        } catch (error: Throwable) {
            temp.delete()
            val canFallbackRemux = !isCancelled() &&
                spec.mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) &&
                fileBytes <= MaxRemuxFallbackBytes
            if (canFallbackRemux) {
                remuxVideoOnly(inputFile, outputFile, spec, onProgress, isCancelled)
                return
            }
            throw error
        } finally {
            temp.delete()
        }
    }

    internal fun computeTargetSize(srcW: Int, srcH: Int, rotation: Int, maxHeight: Int): Pair<Int, Int> {
        val (orientedW, orientedH) = if (rotation == 90 || rotation == 270) srcH to srcW else srcW to srcH
        val maxW = (maxHeight * 16) / 9
        val scale = min(1f, min(maxW.toFloat() / orientedW.coerceAtLeast(1), maxHeight.toFloat() / orientedH.coerceAtLeast(1)))
        val w = align16((orientedW * scale).roundToInt())
        val h = align16((orientedH * scale).roundToInt())
        return w to h
    }

    private fun align16(value: Int): Int = (((value.coerceAtLeast(16) + 8) / 16) * 16).coerceAtLeast(16)

    private fun canFastCopy(spec: Spec, targetW: Int, targetH: Int, fileBytes: Long): Boolean =
        spec.mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) &&
            spec.rotation == 0 &&
            !spec.hasAudio &&
            spec.width == targetW &&
            spec.height == targetH &&
            spec.width % 16 == 0 &&
            spec.height % 16 == 0 &&
            spec.frameRate in 1..62 &&
            fileBytes <= MaxFastCopyBytes

    private fun canFastRemux(spec: Spec, maxHeight: Int, fileBytes: Long): Boolean {
        if (!spec.mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) return false
        if (spec.rotation != 0) return false
        if (fileBytes > MaxFastCopyBytes) return false
        val maxW = (maxHeight * 16) / 9
        val sizeOk = spec.width <= maxW && spec.height <= maxHeight && spec.width % 2 == 0 && spec.height % 2 == 0
        val fpsOk = spec.frameRate <= 62 // 包含 0（未知）或 <= 60fps
        return sizeOk && fpsOk
    }

    private fun remuxVideoOnly(
        inputFile: File,
        outputFile: File,
        spec: Spec,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val temp = File(outputFile.parentFile, outputFile.name + ".remux.tmp")
        temp.delete()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(inputFile.absolutePath)
            var videoTrack = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    videoFormat = f
                    break
                }
            }
            require(videoTrack >= 0 && videoFormat != null) { "所选文件没有视频轨。" }
            extractor.selectTrack(videoTrack)

            muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (spec.rotation != 0) {
                muxer.setOrientationHint(spec.rotation)
            }
            val dstTrack = muxer.addTrack(videoFormat)
            muxer.start()
            muxerStarted = true

            val maxBuf = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
            } else {
                1024 * 1024
            }
            val buffer = ByteBuffer.allocateDirect(maxBuf)
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (isCancelled()) error("已取消视频处理。")
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                @Suppress("DEPRECATION")
                info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(dstTrack, buffer, info)
                if (spec.durationUs > 0L) {
                    onProgress((info.presentationTimeUs.toFloat() / spec.durationUs).coerceIn(0f, 0.99f))
                }
                extractor.advance()
            }

            muxer.stop()
            muxerStarted = false
            if (outputFile.exists()) outputFile.delete()
            check(temp.renameTo(outputFile)) { "保存封装视频失败。" }
            onProgress(1f)
        } finally {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            temp.delete()
        }
    }

    private fun probe(file: File): Spec {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videoFormat: MediaFormat? = null
            var hasAudio = false
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (videoFormat == null && mime.startsWith("video/")) {
                    videoFormat = f
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                }
            }
            val vf = videoFormat ?: error("所选文件没有视频轨。")
            val w = vf.getInteger(MediaFormat.KEY_WIDTH)
            val h = vf.getInteger(MediaFormat.KEY_HEIGHT)
            require(w > 0 && h > 0) { "视频尺寸无效。" }
            val durationUs = if (vf.containsKey(MediaFormat.KEY_DURATION)) vf.getLong(MediaFormat.KEY_DURATION) else 0L
            val fps = if (vf.containsKey(MediaFormat.KEY_FRAME_RATE)) vf.getInteger(MediaFormat.KEY_FRAME_RATE) else 0
            var rotation = if (vf.containsKey(MediaFormat.KEY_ROTATION)) vf.getInteger(MediaFormat.KEY_ROTATION) else 0
            if (rotation == 0) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                } catch (_: Exception) {
                } finally {
                    runCatching { retriever.release() }
                }
            }
            return Spec(
                width = w,
                height = h,
                durationUs = durationUs,
                rotation = ((rotation % 360) + 360) % 360,
                mime = vf.getString(MediaFormat.KEY_MIME).orEmpty(),
                hasAudio = hasAudio,
                frameRate = fps,
            )
        } finally {
            extractor.release()
        }
    }

    private fun runTranscode(
        inputFile: File,
        outputFile: File,
        spec: Spec,
        targetW: Int,
        targetH: Int,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var eglHelper: EglSurfaceRenderer? = null
        var decoderSurface: Surface? = null
        var surfaceTexture: SurfaceTexture? = null
        val frameThread = HandlerThread("HomeBgTranscodeFrame").apply { start() }

        try {
            extractor.setDataSource(inputFile.absolutePath)
            var trackIndex = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    trackIndex = i
                    inFormat = f
                    break
                }
            }
            require(trackIndex >= 0 && inFormat != null) { "所选文件没有视频轨。" }
            extractor.selectTrack(trackIndex)

            val targetFps = if (spec.frameRate > 0) spec.frameRate.coerceIn(24, 60) else 60
            val bitrate = if (targetH >= 1080) {
                if (targetFps > 40) 7_500_000 else 5_000_000
            } else {
                if (targetFps > 40) 4_500_000 else 3_000_000
            }
            val outFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetW, targetH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            eglHelper = EglSurfaceRenderer(inputSurface, targetW, targetH, spec.rotation)
            eglHelper.makeCurrent()
            val texId = eglHelper.createOesTexture()

            val frameSync = Object()
            var frameAvailable = false
            surfaceTexture = SurfaceTexture(texId).apply {
                setOnFrameAvailableListener(
                    {
                        synchronized(frameSync) {
                            frameAvailable = true
                            frameSync.notifyAll()
                        }
                    },
                    Handler(frameThread.looper),
                )
            }
            decoderSurface = Surface(surfaceTexture)

            val inMime = inFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(inMime)
            decoder.configure(inFormat, decoderSurface, null, 0)
            decoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrack = -1

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            var lastEncodedPtsUs = Long.MIN_VALUE

            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    encoder.signalEndOfInputStream()
                }
                while (true) {
                    val outIdx = encoder.dequeueOutputBuffer(encInfo, TimeoutUs)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "编码器格式重复变更" }
                            muxerTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIdx >= 0 -> {
                            val encoded = encoder.getOutputBuffer(outIdx)
                            if ((encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                encInfo.size = 0
                            }
                            if (encInfo.size > 0 && encoded != null && muxerStarted) {
                                encoded.position(encInfo.offset)
                                encoded.limit(encInfo.offset + encInfo.size)
                                muxer.writeSampleData(muxerTrack, encoded, encInfo)
                                if (spec.durationUs > 0L) {
                                    onProgress((encInfo.presentationTimeUs.toFloat() / spec.durationUs).coerceIn(0f, 0.99f))
                                }
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderDone = true
                                break
                            }
                        }
                    }
                }
            }

            while (!encoderDone) {
                if (isCancelled()) error("已取消视频处理。")

                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(TimeoutUs)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, TimeoutUs)
                    if (outIdx >= 0) {
                        val eos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val ptsUs = decInfo.presentationTimeUs
                        val shouldRender = decInfo.size > 0 &&
                            (lastEncodedPtsUs == Long.MIN_VALUE || ptsUs - lastEncodedPtsUs >= MinFrameIntervalUs)

                        if (shouldRender) {
                            synchronized(frameSync) { frameAvailable = false }
                            decoder.releaseOutputBuffer(outIdx, true)
                            val deadline = System.currentTimeMillis() + 2000L
                            synchronized(frameSync) {
                                while (!frameAvailable && System.currentTimeMillis() < deadline) {
                                    frameSync.wait(200L)
                                }
                            }
                            if (frameAvailable) {
                                surfaceTexture.updateTexImage()
                                eglHelper.drawFrame(surfaceTexture, texId, ptsUs)
                                lastEncodedPtsUs = ptsUs
                            }
                        } else {
                            decoder.releaseOutputBuffer(outIdx, false)
                        }

                        if (eos) {
                            decoderDone = true
                            drainEncoder(endOfStream = true)
                        } else {
                            drainEncoder(endOfStream = false)
                        }
                    } else {
                        drainEncoder(endOfStream = false)
                    }
                } else {
                    drainEncoder(endOfStream = false)
                }
            }
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { decoderSurface?.release() }
            runCatching { surfaceTexture?.release() }
            runCatching { eglHelper?.release() }
            runCatching { inputSurface?.release() }
            runCatching { extractor.release() }
            runCatching { frameThread.quitSafely() }
        }
    }

    private class EglSurfaceRenderer(
        private val surface: Surface,
        private val width: Int,
        private val height: Int,
        rotation: Int,
    ) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var posHandle = 0
        private var texHandle = 0
        private var matrixHandle = 0
        private val texMatrix = FloatArray(16)
        private val quadVertices: FloatBuffer

        init {
            val coords = when (rotation) {
                90 -> floatArrayOf(
                    -1f, 1f, 0f, 0f,
                    -1f, -1f, 1f, 0f,
                    1f, 1f, 0f, 1f,
                    1f, -1f, 1f, 1f,
                )
                180 -> floatArrayOf(
                    1f, 1f, 0f, 0f,
                    -1f, 1f, 1f, 0f,
                    1f, -1f, 0f, 1f,
                    -1f, -1f, 1f, 1f,
                )
                270 -> floatArrayOf(
                    1f, -1f, 0f, 0f,
                    1f, 1f, 1f, 0f,
                    -1f, -1f, 0f, 1f,
                    -1f, 1f, 1f, 1f,
                )
                else -> floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f,
                )
            }
            quadVertices = ByteBuffer.allocateDirect(coords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(coords); position(0) }

            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "EGL 获取默认显示设备失败 (${eglErrHex()})" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL 初始化失败 (${eglErrHex()})" }

            val config = chooseEglConfig(display)

            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "EGL 创建上下文失败 (${eglErrHex()})" }

            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL 创建窗口表面失败 (${eglErrHex()})" }
        }

        private fun chooseEglConfig(dpy: EGLDisplay): EGLConfig {
            val candidates = listOf(
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EglRecordableAndroid, 1,
                    EGL14.EGL_NONE,
                ),
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE,
                ),
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE,
                ),
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE,
                ),
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            var lastErr = ""
            for (attribs in candidates) {
                configs[0] = null
                numConfigs[0] = 0
                if (EGL14.eglChooseConfig(dpy, attribs, 0, configs, 0, 1, numConfigs, 0) &&
                    numConfigs[0] > 0 && configs[0] != null
                ) {
                    return configs[0]!!
                }
                lastErr = eglErrHex()
            }
            error("EGL 配置失败 ($lastErr)")
        }

        private fun eglErrHex(): String = "0x" + Integer.toHexString(EGL14.eglGetError())

        fun makeCurrent() {
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent 失败 (${eglErrHex()})" }
            program = buildProgram()
            posHandle = GLES20.glGetAttribLocation(program, "aPos")
            texHandle = GLES20.glGetAttribLocation(program, "aTex")
            matrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
            GLES20.glViewport(0, 0, width, height)
        }

        fun createOesTexture(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return ids[0]
        }

        fun drawFrame(st: SurfaceTexture, texId: Int, ptsUs: Long) {
            st.getTransformMatrix(texMatrix)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, texMatrix, 0)

            quadVertices.position(0)
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
            GLES20.glEnableVertexAttribArray(posHandle)

            quadVertices.position(2)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
            GLES20.glEnableVertexAttribArray(texHandle)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            runCatching { EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsUs * 1000L) }
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        private fun buildProgram(): Int {
            val vs = compileShader(
                GLES20.GL_VERTEX_SHADER,
                """
                attribute vec4 aPos;
                attribute vec4 aTex;
                uniform mat4 uTexMatrix;
                varying vec2 vTex;
                void main() {
                    gl_Position = aPos;
                    vTex = (uTexMatrix * aTex).xy;
                }
                """.trimIndent(),
            )
            val fs = compileShader(
                GLES20.GL_FRAGMENT_SHADER,
                """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTex;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTex);
                }
                """.trimIndent(),
            )
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            check(linkStatus[0] == GLES20.GL_TRUE) {
                val log = GLES20.glGetProgramInfoLog(prog)
                GLES20.glDeleteProgram(prog)
                "GL 着色器链接失败：$log"
            }
            return prog
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] == GLES20.GL_TRUE) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                "GL 着色器编译失败：$log"
            }
            return shader
        }
    }
}
