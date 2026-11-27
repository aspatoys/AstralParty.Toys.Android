package com.hiynet.aspatoys.data.background

import com.hiynet.aspatoys.core.booleanOrDefault
import com.hiynet.aspatoys.core.parseJsonObjectOrNull
import com.hiynet.aspatoys.core.string
import com.hiynet.aspatoys.data.loader.LoaderFs
import com.hiynet.aspatoys.data.loader.LoaderRepo
import com.hiynet.aspatoys.data.loader.LoaderSnapshot
import com.hiynet.aspatoys.data.mods.ModConfigText
import com.hiynet.aspatoys.data.mods.ModEntry
import com.hiynet.aspatoys.data.mods.ModLayout
import com.hiynet.aspatoys.data.mods.SdkVersions
import java.io.File

/**
 * 读写「自定义主页背景」模组（HomeBackgroundMod）的状态、背景图片/视频及配置。
 *
 * 所有加载器目录 I/O 一律经过 [LoaderFs]（Shizuku/Root），与游戏真实读写路径一致；
 * 图像和视频转码由调用方预先落到应用本地文件，再由本仓库原子推入 `mods/HomeBackgroundMod/`。
 */
class HomeBackgroundRepository(
    private val fs: LoaderFs,
    private val layoutProvider: () -> ModLayout,
    private val previewCacheDir: File,
    private val snapshotProvider: () -> LoaderSnapshot,
    private val setEnabledAction: (ModEntry, Boolean) -> Unit,
    private val unavailableReasonProvider: () -> String = { "提权通道不可用" },
) {
    constructor(loader: LoaderRepo, previewCacheDir: File) : this(
        fs = loader.fs,
        layoutProvider = { loader.layout() },
        previewCacheDir = previewCacheDir,
        snapshotProvider = { loader.snapshot() },
        setEnabledAction = { entry, enabled -> loader.setEnabled(entry, enabled) },
        unavailableReasonProvider = { loader.unavailableReason() },
    )

    data class Config(
        val enable: Boolean = true,
        val hideThemeVideo: Boolean = true,
        val video: String = DefaultVideo,
        val image: String = DefaultImage,
    )

    data class Status(
        val loaderReady: Boolean = false,
        val unavailableReason: String = "",
        val modInstalled: Boolean = false,
        val modEnabled: Boolean = false,
        val installedVersion: String = "",
        val needsVideoUpgrade: Boolean = false,
        /** off（未启用）/ none（无背景文件）/ image / video */
        val mode: String = "off",
        val configEnabled: Boolean = true,
        val hideThemeVideo: Boolean = true,
        val videoFile: String = "",
        val videoSize: Long = 0L,
        val imageFile: String = "",
        val imagePreviewFile: File? = null,
    )

    fun inspect(): Status {
        val snapshot = snapshotProvider()
        val ready = fs.isAvailable() || snapshot.viaShizuku
        if (!ready) {
            return Status(
                loaderReady = false,
                unavailableReason = snapshot.error ?: unavailableReasonProvider(),
            )
        }

        val layout = layoutProvider()
        val entry = findModEntry(snapshot.entries) ?: return Status(loaderReady = true, modInstalled = false)
        val modDir = resolveModDir(layout, entry)
        val cfg = readConfig(modDir)
        val videoPath = resolveVideoPath(modDir, cfg.video)
        val imagePath = resolveImagePath(modDir, cfg.image)
        val videoSize = videoPath?.let { fs.fileSize(it) } ?: 0L
        val needsUpgrade = entry.version.isBlank() || SdkVersions.compare(entry.version, MinSmoothVideoVersion) < 0

        val previewFile = if (imagePath != null && videoPath == null) cachePreview(imagePath) else null
        val mode = when {
            !entry.enabled || !cfg.enable -> "off"
            videoPath != null -> "video"
            imagePath != null -> "image"
            else -> "none"
        }

        return Status(
            loaderReady = true,
            modInstalled = true,
            modEnabled = entry.enabled,
            installedVersion = entry.version,
            needsVideoUpgrade = needsUpgrade,
            mode = mode,
            configEnabled = cfg.enable,
            hideThemeVideo = cfg.hideThemeVideo,
            videoFile = videoPath?.substringAfterLast('/') ?: "",
            videoSize = videoSize,
            imageFile = imagePath?.substringAfterLast('/') ?: "",
            imagePreviewFile = previewFile,
        )
    }

    fun setImage(pngFile: File) {
        require(pngFile.isFile && pngFile.length() > 0L) { "图片文件不存在或为空。" }
        val (entry, modDir) = requireActiveModDir()
        val cfg = readConfig(modDir)

        // 删除已有视频，否则 Mod 会优先播放视频而不是图片
        deleteExistingVideos(modDir, cfg.video)

        val assetsDir = "$modDir/assets"
        fs.makeDirectories(assetsDir)
        val newName = "$ManagedImagePrefix${System.currentTimeMillis()}.png"
        fs.copyIn(pngFile, "$assetsDir/$newName")
        cleanupManagedImages(assetsDir, keep = newName)

        writeConfig(modDir, cfg.copy(enable = true, image = "assets/$newName"))
        if (!entry.enabled) {
            setEnabledAction(entry, true)
        }
    }

    fun setVideo(mp4File: File) {
        require(mp4File.isFile && mp4File.length() > 0L) { "视频文件不存在或为空。" }
        val (entry, modDir) = requireActiveModDir()
        val cfg = readConfig(modDir)

        for (other in listOf("bg.webm")) {
            val oldPath = "$modDir/$other"
            if (fs.exists(oldPath)) fs.delete(oldPath)
        }
        val targetVideo = "$modDir/$DefaultVideo"
        fs.copyIn(mp4File, targetVideo)
        writeConfig(modDir, cfg.copy(enable = true, video = DefaultVideo))
        if (!entry.enabled) {
            setEnabledAction(entry, true)
        }
    }

    fun setHideThemeVideo(hide: Boolean) {
        val (_, modDir) = requireInstalledModDir()
        val cfg = readConfig(modDir)
        writeConfig(modDir, cfg.copy(hideThemeVideo = hide))
    }

    fun enableMod() {
        val snapshot = snapshotProvider()
        val entry = findModEntry(snapshot.entries) ?: error("还没有安装自定义主页背景模组。")
        if (!entry.enabled) {
            setEnabledAction(entry, true)
        }
    }

    fun restoreDefault() {
        val snapshot = snapshotProvider()
        val entry = findModEntry(snapshot.entries) ?: return
        if (entry.enabled) {
            setEnabledAction(entry, false)
        }
    }

    private fun requireInstalledModDir(): Pair<ModEntry, String> {
        check(fs.isAvailable()) { unavailableReasonProvider() }
        val entry = findModEntry(snapshotProvider().entries) ?: error("还没有安装自定义主页背景模组。")
        return entry to resolveModDir(layoutProvider(), entry)
    }

    private fun requireActiveModDir(): Pair<ModEntry, String> {
        check(fs.isAvailable()) { unavailableReasonProvider() }
        var entry = findModEntry(snapshotProvider().entries) ?: error("还没有安装自定义主页背景模组。")
        if (!entry.enabled) {
            setEnabledAction(entry, true)
            entry = findModEntry(snapshotProvider().entries) ?: entry.copy(disabledFolder = false, enabled = true)
        }
        return entry to resolveModDir(layoutProvider(), entry)
    }

    private fun findModEntry(entries: List<ModEntry>): ModEntry? =
        entries.firstOrNull { it.id.equals(ModId, ignoreCase = true) || it.name.equals(ModId, ignoreCase = true) }

    private fun resolveModDir(layout: ModLayout, entry: ModEntry): String {
        val root = if (entry.disabledFolder) layout.disabledModsRoot.absolutePath else layout.modsRoot.absolutePath
        val folder = entry.directoryName ?: entry.name
        return "$root/$folder"
    }

    private fun readConfig(modDir: String): Config {
        val raw = fs.readText("$modDir/${ModLayout.MOD_CONFIG_FILE_NAME}")
        val obj = parseJsonObjectOrNull(raw) ?: return Config()
        return Config(
            enable = obj.booleanOrDefault("enable", true),
            hideThemeVideo = obj.booleanOrDefault("hideThemeVideo", true),
            video = obj.string("video").ifBlank { DefaultVideo },
            image = obj.string("image").ifBlank { DefaultImage },
        )
    }

    private fun writeConfig(modDir: String, cfg: Config) {
        val path = "$modDir/${ModLayout.MOD_CONFIG_FILE_NAME}"
        val existing = fs.readText(path)
        val patched = if (!existing.isNullOrBlank()) {
            var text: String? = existing
            text = text?.let { ModConfigText.patchValue(it, "enable", cfg.enable.toString()) }
            text = text?.let { ModConfigText.patchValue(it, "hideThemeVideo", cfg.hideThemeVideo.toString()) }
            text = text?.let { ModConfigText.patchValue(it, "video", ModConfigText.quote(cfg.video)) }
            text = text?.let { ModConfigText.patchValue(it, "image", ModConfigText.quote(cfg.image)) }
            text
        } else {
            null
        }
        val finalJson = patched ?: buildConfigJson(cfg)
        fs.writeText(path, finalJson)
    }

    private fun buildConfigJson(cfg: Config): String =
        """
        {
          "enable": ${cfg.enable},
          "hideThemeVideo": ${cfg.hideThemeVideo},
          "video": ${ModConfigText.quote(cfg.video)},
          "image": ${ModConfigText.quote(cfg.image)}
        }
        """.trimIndent() + "\n"

    private fun resolveVideoPath(modDir: String, videoRel: String): String? {
        val clean = videoRel.replace('\\', '/').trimStart('/')
        val candidates = listOf("$modDir/$clean", "$modDir/assets/$clean", "$modDir/$DefaultVideo")
        return candidates.firstOrNull { fs.exists(it) }
    }

    private fun resolveImagePath(modDir: String, imageRel: String): String? {
        val clean = imageRel.replace('\\', '/')
        val path = if (clean.startsWith("/")) clean else "$modDir/$clean"
        return if (fs.exists(path)) path else null
    }

    private fun deleteExistingVideos(modDir: String, configuredVideo: String) {
        val clean = configuredVideo.replace('\\', '/').trimStart('/')
        val candidates = linkedSetOf(
            "$modDir/$clean",
            "$modDir/assets/$clean",
            "$modDir/$DefaultVideo",
            "$modDir/bg.webm",
        )
        for (path in candidates) {
            if (fs.exists(path)) fs.delete(path)
        }
    }

    private fun cleanupManagedImages(assetsDir: String, keep: String) {
        for (name in fs.listFiles(assetsDir)) {
            if (name.startsWith(ManagedImagePrefix) && name.endsWith(".png", ignoreCase = true) && !name.equals(keep, ignoreCase = true)) {
                fs.delete("$assetsDir/$name")
            }
        }
    }

    private fun cachePreview(remoteImagePath: String): File? {
        val size = fs.fileSize(remoteImagePath)
        if (size <= 0L || size > 12L * 1024 * 1024) return null
        val bytes = fs.readBytes(remoteImagePath) ?: return null
        previewCacheDir.mkdirs()
        val file = File(previewCacheDir, "homebg-preview.png")
        val tmp = File(previewCacheDir, "homebg-preview.png.tmp")
        return try {
            tmp.writeBytes(bytes)
            if (file.exists()) file.delete()
            if (tmp.renameTo(file)) file else null
        } catch (_: Exception) {
            tmp.delete()
            null
        }
    }

    companion object {
        const val ModId = "HomeBackgroundMod"
        const val MinSmoothVideoVersion = "1.2.0"
        const val DefaultVideo = "bg.mp4"
        const val DefaultImage = "assets/bg.png"
        private const val ManagedImagePrefix = "bg-toys-"
    }
}
