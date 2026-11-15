package com.hiynet.aspatoys.data.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Copies a selected document into private storage; external URI access is never needed again. */
class BackgroundImages(context: Context, private val settings: AppSettings) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "backgrounds")

    suspend fun importImage(uri: Uri) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val source = File.createTempFile("import-", ".tmp", directory)
        val target = File(directory, "background-${UUID.randomUUID()}.png")
        var bitmap: Bitmap? = null
        var committed = false
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 30L * 1024 * 1024) { "图片不能超过 30 MB。" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("无法读取所选图片，请重新选择。")
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                require(width > 0 && height > 0) { "图片尺寸无效。" }
                val scale = minOf(1f, 2048f / maxOf(width, height))
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            target.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法保存背景图片。" }
            }
            ensureActive()
            val previous = settings.backgroundImage
            settings.backgroundImage = target.name
            committed = true
            imageFile(previous)?.delete()
        } finally {
            bitmap?.recycle()
            source.delete()
            if (!committed) target.delete()
        }
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        val previous = settings.backgroundImage
        settings.backgroundImage = ""
        imageFile(previous)?.delete()
    }

    suspend fun load(name: String): Bitmap? = withContext(Dispatchers.IO) {
        imageFile(name)?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
    }

    private fun imageFile(name: String): File? =
        name.takeIf { it.startsWith("background-") && it.endsWith(".png") && File(it).name == it }
            ?.let { File(directory, it) }
}
