package com.hiynet.aspatoys.installer

import com.hiynet.aspatoys.data.loader.LoaderFs
import com.hiynet.cesiumloader.core.AndroidPaths

/** Destructive cleanup is restricted to the directory actually used by the Android injector. */
internal object LoaderRemoval {
    fun remove(fs: LoaderFs, root: String, restoreOriginal: () -> Unit, cleanupLocal: () -> Unit,
               gamePackage: String = LoaderInstaller.GAME_PACKAGE) {
        require(root == AndroidPaths.gameLoaderDir(gamePackage).invariantSeparatorsPath) { "拒绝删除非 Android 加载器目录" }
        check(fs.isAvailable()) { "请先连接 Shizuku / Root" }
        restoreOriginal()
        fs.delete(root)
        val remains = fs.exists(root)
        check(fs.lastError == null && !remains) { "原包已恢复，但加载器文件未完全删除；保留安装备份，请重试" }
        cleanupLocal()
    }
}
