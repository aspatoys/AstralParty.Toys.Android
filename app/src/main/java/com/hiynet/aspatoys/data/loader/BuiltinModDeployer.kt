package com.hiynet.aspatoys.data.loader

import java.io.File

/**
 * 内置模组的真实投放实现（安装器与悬浮窗共用）。
 *
 * 历史问题：这里以前无条件 `mkdir -p mods/<Id>` 再写文件，玩家停用过的内置模组重装后会在 `mods`
 * 里多出一份副本，加载器扫描到两份、切换启停时 `mv` 又把目录塞进同名目录，模组从此消失。
 * 现在位置由 [ModStateStore] 决定：有停用副本就继续停用、旧平铺 DLL 先退役，绝不新造重复目录。
 */
class BuiltinModDeployer(
    private val fs: LoaderFs,
    private val listAssets: (String) -> List<String>,
    private val readAsset: (String) -> ByteArray,
) {
    /** [modIds] 本次投放的模组；[files] 写入文件数；[disabled] 是保持停用的那些。 */
    data class Outcome(val modIds: List<String>, val files: Int, val disabled: List<String>)

    private val store = ModStateStore(fs)

    /**
     * 把 [assetDir] 下的内置模组逐个投放进加载器根目录 [root]。
     *
     * 只写自己的文件，不清目录、不动 `config.json`、`mod.cfg` 与玩家独有的
     * 皮肤/预设资源，所以覆盖安装不会重置本地配置。
     */
    fun deploy(
        root: String,
        assetDir: String = MODS_ASSET_DIR,
        skip: Set<String> = DEFAULT_SKIP,
        log: (String) -> Unit = {},
    ): Outcome {
        val modIds = listAssets(assetDir).filterNot { it in skip }.sorted()
        var files = 0
        val disabled = mutableListOf<String>()
        for (modId in modIds) {
            if (listAssets("$assetDir/$modId").isEmpty()) continue
            val placement = store.place(
                root = root,
                modId = modId,
                folderName = modId,
                preferDisabledOnConflict = true,
                migrateFlatToNested = true,
                log = log,
            )
            if (placement.disabled) disabled += modId
            files += copyDir("$assetDir/$modId", placement.modDir)
            log("$modId ${if (placement.disabled) "已更新（保持停用）" else "已安装"}")
        }
        log("内置模组 ${modIds.size} 个 / $files 个文件")
        return Outcome(modIds, files, disabled)
    }

    /** 递归拷贝 assets 目录到远端目录，返回写入的文件数；同路径覆盖成包内版本，其余文件保留。 */
    private fun copyDir(assetDir: String, remoteDir: String): Int {
        var files = 0
        fs.makeDirectories(remoteDir)
        for (name in listAssets(assetDir).sorted()) {
            val asset = "$assetDir/$name"
            val remote = "$remoteDir/$name"
            if (listAssets(asset).isNotEmpty()) {
                files += copyDir(asset, remote)
            } else {
                if (name.lowercase() in setOf("config.json", "mod.cfg") && fs.exists(remote)) continue
                writeBytes(readAsset(asset), remote)
                files++
            }
        }
        return files
    }

    private fun writeBytes(bytes: ByteArray, path: String) {
        require(bytes.isNotEmpty()) { "内置文件为空：$path" }
        val local = File.createTempFile("builtin-mod-", ".tmp")
        try {
            local.writeBytes(bytes)
            fs.copyIn(local, path)
        } finally {
            local.delete()
        }
    }

    companion object {
        /** 与发行版 assets 下的内置模组目录一致。 */
        const val MODS_ASSET_DIR = "cesium/mods"

        /** 这几个由 [BuiltinOverlay] 单独投放（悬浮窗需要额外处理外观与旧变速备份）。 */
        val DEFAULT_SKIP = setOf(
            "LanguageUnlockMod",
            BuiltinOverlay.ID,
            BuiltinOverlay.SPEED_ID,
            BuiltinOverlay.CAMERA_ID,
        )
    }
}
