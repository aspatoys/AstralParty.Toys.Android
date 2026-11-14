package com.hiynet.cesiumloader.core

import java.io.File

/**
 * 安卓侧与加载器无关的固定路径。
 *
 * 这里刻意写死 `/sdcard`：加载器（原生与托管）用的就是这条路径，
 * 它等价于 `Environment.getExternalStorageDirectory()`（`/storage/emulated/0`），
 * 写死的好处是这些常量能在纯 JVM 单测里使用。
 */
object AndroidPaths {

    const val ExternalStorage = "/sdcard"
    const val LoaderFolderName = "CesiumLoader"

    /** 已知的游戏包名根：官方、共存版、渠道服都在这两个之下列出。 */
    val GamePackageRoots = listOf("com.feimo.astralparty", "com.feimo.jxpd")

    /** 热更 bundle 缓存目录（相对游戏 files 目录）。 */
    const val BundleCachePath = "com.unity.addressables/AssetBundles"

    fun loaderRoot(): File = File(ExternalStorage, LoaderFolderName)

    fun loaderLayout(): CesiumLayout = CesiumLayout(loaderRoot())

    fun gameFilesDir(packageName: String): File =
        File(ExternalStorage, "Android/data/$packageName/files")

    fun bundleCacheDir(packageName: String): File =
        File(gameFilesDir(packageName), BundleCachePath)

    fun isGamePackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return GamePackageRoots.any { root ->
            packageName == root || packageName.startsWith("$root.")
        }
    }
}
