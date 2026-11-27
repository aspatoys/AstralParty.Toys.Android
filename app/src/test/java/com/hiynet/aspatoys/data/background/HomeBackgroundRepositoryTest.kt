package com.hiynet.aspatoys.data.background

import com.hiynet.aspatoys.data.loader.InMemoryLoaderFs
import com.hiynet.aspatoys.data.loader.LoaderSnapshot
import com.hiynet.aspatoys.data.mods.ModEntry
import com.hiynet.aspatoys.data.mods.ModLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HomeBackgroundRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val loaderRoot = File("/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader")
    private val layout = ModLayout(loaderRoot)

    private fun entry(version: String, disabled: Boolean = false) = ModEntry(
        name = HomeBackgroundRepository.ModId,
        fileName = "${HomeBackgroundRepository.ModId}.dll",
        directoryName = HomeBackgroundRepository.ModId,
        sizeBytes = 1024L,
        modifiedEpochMs = 0L,
        id = HomeBackgroundRepository.ModId,
        displayName = "自定义主页背景",
        version = version,
        author = "TenkinMori",
        description = "",
        sdkVersion = "3.1.1",
        enabled = !disabled,
        disabledFolder = disabled,
    )

    private fun makeRepo(
        fs: InMemoryLoaderFs,
        entries: MutableList<ModEntry>,
    ): HomeBackgroundRepository = HomeBackgroundRepository(
        fs = fs,
        layoutProvider = { layout },
        previewCacheDir = temp.newFolder("preview"),
        snapshotProvider = {
            LoaderSnapshot(
                entries = entries.toList(),
                sdkVersion = "4.1.1",
                issues = emptyList(),
                viaShizuku = true,
                error = null,
            )
        },
        setEnabledAction = { target, enabled ->
            val idx = entries.indexOfFirst { it.name == target.name }
            if (idx >= 0) {
                val fromDir = if (target.disabledFolder) "${layout.disabledModsRoot.absolutePath}/${target.name}" else "${layout.modsRoot.absolutePath}/${target.name}"
                val toDir = if (!enabled) "${layout.disabledModsRoot.absolutePath}/${target.name}" else "${layout.modsRoot.absolutePath}/${target.name}"
                if (fromDir != toDir && fs.exists(fromDir)) {
                    fs.move(fromDir, toDir)
                }
                entries[idx] = target.copy(enabled = enabled, disabledFolder = !enabled)
            }
        },
    )

    @Test
    fun missingModReportsNotInstalled() {
        val fs = InMemoryLoaderFs()
        val repo = makeRepo(fs, mutableListOf())
        val status = repo.inspect()
        assertTrue(status.loaderReady)
        assertFalse(status.modInstalled)
        assertEquals("off", status.mode)
    }

    @Test
    fun oldVersionFlagsVideoUpgradeWhileNewVersionDoesNot() {
        val fs = InMemoryLoaderFs()
        val modDir = "${layout.modsRoot.absolutePath}/HomeBackgroundMod"
        fs.writeText("$modDir/HomeBackgroundMod.dll", "dll")
        fs.writeText("$modDir/assets/bg.png", "png")

        val entries = mutableListOf(entry("1.1.0"))
        val repo = makeRepo(fs, entries)

        val oldStatus = repo.inspect()
        assertTrue(oldStatus.modInstalled)
        assertTrue(oldStatus.modEnabled)
        assertTrue(oldStatus.needsVideoUpgrade)
        assertEquals("image", oldStatus.mode)

        entries[0] = entry("1.2.0")
        val newStatus = repo.inspect()
        assertFalse(newStatus.needsVideoUpgrade)
    }

    @Test
    fun setImageDeletesVideoAndUpdatesConfig() {
        val fs = InMemoryLoaderFs()
        val modDir = "${layout.modsRoot.absolutePath}/HomeBackgroundMod"
        fs.writeText("$modDir/HomeBackgroundMod.dll", "dll")
        fs.writeText("$modDir/bg.mp4", "old-video")
        fs.writeText(
            "$modDir/config.json",
            """
            {
              "enable": true,
              "hideThemeVideo": true,
              "video": "bg.mp4",
              "image": "assets/bg.png"
            }
            """.trimIndent(),
        )
        val entries = mutableListOf(entry("1.2.0"))
        val repo = makeRepo(fs, entries)

        val png = temp.newFile("sample.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        repo.setImage(png)

        assertFalse(fs.exists("$modDir/bg.mp4"))
        val status = repo.inspect()
        assertEquals("image", status.mode)
        assertTrue(status.imageFile.startsWith("bg-toys-"))
        assertEquals("", status.videoFile)
    }

    @Test
    fun setVideoWritesMp4AndRestoreDefaultDisablesMod() {
        val fs = InMemoryLoaderFs()
        val modDir = "${layout.modsRoot.absolutePath}/HomeBackgroundMod"
        fs.writeText("$modDir/HomeBackgroundMod.dll", "dll")
        val entries = mutableListOf(entry("1.2.0", disabled = true))
        val repo = makeRepo(fs, entries)

        assertEquals("off", repo.inspect().mode)

        val mp4 = temp.newFile("bg.mp4").apply { writeBytes(byteArrayOf(0, 0, 0, 24)) }
        repo.setVideo(mp4)

        val activeStatus = repo.inspect()
        assertTrue(activeStatus.modEnabled)
        assertEquals("video", activeStatus.mode)
        assertEquals("bg.mp4", activeStatus.videoFile)
        assertNull(activeStatus.imagePreviewFile)

        repo.restoreDefault()
        val disabledStatus = repo.inspect()
        assertFalse(disabledStatus.modEnabled)
        assertEquals("off", disabledStatus.mode)
    }
}
