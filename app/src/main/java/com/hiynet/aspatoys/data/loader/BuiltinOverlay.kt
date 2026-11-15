package com.hiynet.aspatoys.data.loader

import android.content.Context
import com.hiynet.aspatoys.data.settings.ThemeMode

/** A normal in-game mod: no Android overlay service or additional permission. */
class BuiltinOverlay(
    private val fs: LoaderFs,
    private val listAssets: (String) -> List<String>,
    private val readAsset: (String) -> ByteArray,
) {
    constructor(context: Context, fs: LoaderFs = ShizukuLoaderFs()) : this(
        fs, { context.assets.list(it).orEmpty().toList() },
        { context.assets.open(it).use { input -> input.readBytes() } },
    )

    fun install(root: String, mode: ThemeMode, systemDark: Boolean, log: (String) -> Unit = {}) {
        check(fs.isAvailable()) { "请先在加载器页连接 Shizuku 或 Root" }
        check(fs.exists("$root/sdk/CesiumLoader.SDK.dll") || fs.exists("$root/CesiumLoader.SDK.dll")) {
            "当前目录没有加载器 SDK，请先安装加载器或在设置中选择正确目录"
        }
        for (id in listOf(ID, SPEED_ID, CAMERA_ID)) {
            require(listAssets("cesium/mods/$id").contains("$id.dll")) { "内置模组缺失：$id" }
        }
        // Keep a previously disabled mod disabled when updating it.
        fun hasDll(folder: String, id: String) = fs.exists("$root/$folder/$id/$id.dll") || fs.exists("$root/$folder/$id.dll")
        fun copyDirectory(asset: String, remote: String) {
            fs.makeDirectories(remote)
            for (name in listAssets(asset)) {
                val source = "$asset/$name"
                val target = "$remote/$name"
                if (listAssets(source).isNotEmpty()) copyDirectory(source, target)
                else writeAsset(source, target)
            }
        }
        writeAsset("cesium/CesiumAndroidApi.dll", "$root/CesiumAndroidApi.dll")
        for (id in listOf(ID, SPEED_ID, CAMERA_ID)) {
            val folder = if (hasDll("disabled_mods", id) && !hasDll("mods", id)) "disabled_mods" else "mods"
            val targetDll = "$root/$folder/$id/$id.dll"
            // Preserve the previous speed implementation once; player configs remain untouched.
            if ((id == SPEED_ID || id == CAMERA_ID) && fs.exists(targetDll) && !fs.exists("$targetDll.pre-panel")) {
                val previous = fs.readBytes(targetDll) ?: error("无法备份旧变速模组")
                writeBytes(previous, "$targetDll.pre-panel")
            }
            copyDirectory("cesium/mods/$id", "$root/$folder/$id")
            // The direct installer used flat DLLs. Keep their backups out of the loader's *.dll scan.
            for (legacyFolder in listOf("mods", "disabled_mods")) {
                val legacy = "$root/$legacyFolder/$id.dll"
                if (fs.exists(legacy)) fs.move(legacy, "$legacy.pre-toys")
            }
            log("$id 已${if (folder == "disabled_mods") "更新（保持停用）" else "安装"}")
        }
        writeAppearance(root, mode, systemDark)
        log("启动默认 1 倍，仅悬浮窗控制变速；完全重启游戏后生效")
    }

    fun syncAppearance(root: String, mode: ThemeMode, systemDark: Boolean) {
        if (!fs.isAvailable()) return
        if (listOf("mods/$ID/$ID.dll", "disabled_mods/$ID/$ID.dll", "mods/$ID.dll", "disabled_mods/$ID.dll")
                .none { fs.exists("$root/$it") }) return
        writeAppearance(root, mode, systemDark)
    }

    private fun writeAppearance(root: String, mode: ThemeMode, systemDark: Boolean) {
        val path = "$root/data/toys-appearance.txt"
        val text = "${mode.preferenceValue}\n${if (mode.resolveDark(systemDark)) "dark" else "light"}\n"
        if (fs.readText(path) == text) return
        fs.makeDirectories("$root/data")
        fs.writeText(path, text)
    }

    private fun writeAsset(asset: String, path: String) {
        val bytes = readAsset(asset)
        require(bytes.isNotEmpty()) { "内置文件为空：$asset" }
        writeBytes(bytes, path)
    }

    private fun writeBytes(bytes: ByteArray, path: String) {
        // LoaderFs.copyIn writes bytes through stdin followed by an atomic rename.
        val local = java.io.File.createTempFile("builtin-overlay-", ".tmp")
        try {
            local.writeBytes(bytes)
            fs.copyIn(local, path)
        } finally {
            local.delete()
        }
    }

    companion object {
        const val ID = "CesiumOverlayMod"
        const val SPEED_ID = "SpeedHackMod"
        const val CAMERA_ID = "FreeCameraMod"
    }
}
