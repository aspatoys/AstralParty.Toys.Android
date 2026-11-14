package com.hiynet.cesiumloader.core

import java.io.File

/**
 * CesiumLoader 在设备上的固定布局，所有路径都由 [root] 派生。
 *
 * 事实来源是目录本身：模组在 [mods] 里就是启用、在 [disabledMods] 里就是停用；
 * `enabled.json` 只作为给其他实现（含 CesiumLoader-Android-ModPack）看的提示。
 */
class CesiumLayout(val root: File) {

    val mods: File get() = File(root, MODS)
    val disabledMods: File get() = File(root, DISABLED_MODS)
    val configs: File get() = File(root, CONFIGS)
    val logs: File get() = File(root, LOGS)
    val dataDir: File get() = File(root, DATA)
    val skins: File get() = File(root, SKINS)
    val audio: File get() = File(root, AUDIO)
    val audioSchemes: File get() = File(root, AUDIO_SCHEMES)
    val bootstrapDir: File get() = File(root, BOOTSTRAP)
    val speed: File get() = File(root, SPEED)
    val window: File get() = File(root, WINDOW)

    val sdkDir: File get() = File(root, SDK)
    val sdkDll: File get() = File(sdkDir, SDK_DLL)

    /** 旧布局把 SDK / 引导 DLL 平铺在根目录，只在迁移时用到。 */
    val legacySdkDll: File get() = File(root, SDK_DLL)
    val legacyAndroidBootstrapDll: File get() = File(root, ANDROID_BOOTSTRAP_DLL)

    val androidBootstrapDll: File get() = File(bootstrapDir, ANDROID_BOOTSTRAP_DLL)
    val legacyBootstrapDll: File get() = File(bootstrapDir, LEGACY_BOOTSTRAP_DLL)
    val androidApiDll: File get() = File(root, ANDROID_API_DLL)
    val overlayPng: File get() = File(root, OVERLAY_PNG)

    val bootstrapLog: File get() = File(root, BOOTSTRAP_LOG)
    val developerLog: File get() = File(root, DEVELOPER_LOG)
    val enabledIndex: File get() = File(root, ENABLED_INDEX)
    val installedIndex: File get() = File(root, INSTALLED_INDEX)

    /** 加载器启动时会创建的目录全集，安装器/还原逻辑按同一份清单处理。 */
    val managedDirectories: List<File>
        get() = listOf(
            root, mods, disabledMods, configs, logs, dataDir,
            skins, audio, audioSchemes, sdkDir, bootstrapDir, speed, window,
        )

    fun modDir(modId: String): File = File(mods, modId)
    fun modDll(modId: String): File = File(modDir(modId), "$modId.dll")
    fun modSidecar(modId: String): File = File(modDir(modId), "$modId.json")
    fun disabledDir(modId: String): File = File(disabledMods, modId)
    fun disabledDll(modId: String): File = File(disabledDir(modId), "$modId.dll")
    fun configFile(modId: String): File = File(configs, "$modId.json")
    fun modDataDir(modId: String): File = File(dataDir, modId)

    override fun toString(): String = "CesiumLayout(root=$root)"

    companion object {
        const val MODS = "mods"
        const val DISABLED_MODS = "disabled_mods"
        const val CONFIGS = "configs"
        const val LOGS = "logs"
        const val DATA = "data"
        const val SKINS = "skins"
        const val AUDIO = "audio"
        const val AUDIO_SCHEMES = "audio-schemes"
        const val SDK = "sdk"
        const val BOOTSTRAP = "bootstrap"
        const val SPEED = "speed"
        const val WINDOW = "window"

        const val SDK_DLL = "CesiumLoader.SDK.dll"
        const val ANDROID_BOOTSTRAP_DLL = "CesiumLoader.Android.Bootstrap.dll"
        const val LEGACY_BOOTSTRAP_DLL = "CesiumBootstrap.dll"
        const val ANDROID_API_DLL = "CesiumAndroidApi.dll"
        const val OVERLAY_PNG = "overlay.png"

        const val BOOTSTRAP_LOG = "bootstrap.log"
        const val DEVELOPER_LOG = "developer.log"
        const val ENABLED_INDEX = "enabled.json"
        const val INSTALLED_INDEX = "installed.json"
    }
}
