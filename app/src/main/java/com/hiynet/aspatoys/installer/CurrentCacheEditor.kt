package com.hiynet.aspatoys.installer

import com.hiynet.cesiumloader.core.unity.SerializedRuntimeAsset
import com.hiynet.cesiumloader.core.unity.UnityFs
import com.hiynet.cesiumloader.core.unity.UnityFsWriter

/** Rebuilds the current Unity cache with just its own Runtime assembly patched in place. */
internal class CurrentCacheEditor(private val patchAssembly: (ByteArray, (String) -> Unit) -> ByteArray) {
    constructor(patchAssembly: (ByteArray) -> ByteArray) : this({ bytes, _ -> patchAssembly(bytes) })

    fun patch(original: ByteArray, log: (String) -> Unit): ByteArray {
        val container = UnityFs.parse(original)
        val matches = container.nodes.mapIndexedNotNull { index, node ->
            if (node.flags and 4 == 0) null else
                SerializedRuntimeAsset.find(container.readNode(node), LoaderInstaller.ASSEMBLY)?.let { index to it }
        }
        require(matches.size == 1) { "当前缓存包应有一个 ${LoaderInstaller.ASSEMBLY} TextAsset，实际 ${matches.size} 个" }
        val (index, asset) = matches.single()
        val current = asset.assembly()
        val patched = patchAssembly(current, log)
        require(!current.contentEquals(patched)) { "本地编辑器未生成新的加载器入口" }
        val written = UnityFsWriter.replaceNode(container, index, asset.replace(patched))
        val check = UnityFs.parse(written)
        val readback = SerializedRuntimeAsset.find(check.readNode(check.nodes[index]), LoaderInstaller.ASSEMBLY)
        require(readback?.assembly()?.contentEquals(patched) == true) { "本地重建缓存回读校验失败" }
        log("已编辑当前热更程序集：${current.size} B → ${patched.size} B")
        return written
    }
}
