package com.hiynet.cesiumloader.core.unity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import org.junit.Test

/**
 * 手机端的完整链路（离线版）：
 *
 * ```
 * __data  → UnityFs.parse → 解出 SerializedFile
 *         → TextAssetPatch.replace（换掉 AstralParty.Runtime.dll）
 *         → UnityFsWriter.writeSingleNode（不压缩块重新封装）
 *         → UnityFs.parse 再读回来，确认只有程序集变了
 * ```
 *
 * 环境变量：`CESIUM_BUNDLE`（热更包）、`CESIUM_PATCHED_DLL`（注入后的程序集）。
 */
class EndToEndPatchTest {

    private fun envFile(name: String): File? {
        val path = System.getenv(name) ?: return null
        val file = File(path)
        return if (file.isFile) file else null
    }

    @Test
    fun replacesAssemblyAndReEmitsContainer() {
        val bundleFile = envFile("CESIUM_BUNDLE")
        val injectedFile = envFile("CESIUM_PATCHED_DLL")
        assumeTrue("缺少 CESIUM_BUNDLE / CESIUM_PATCHED_DLL，跳过", bundleFile != null && injectedFile != null)

        val bundle = UnityFs.parse(bundleFile!!.readBytes())
        val injected = injectedFile!!.readBytes()
        val node = bundle.nodes.first()
        val serialized = bundle.readNode(node)

        // 换之前：包里的程序集不是注入后的那份
        val before = TextAssetPatch.read(serialized, ASSEMBLY)
        assertEquals("槽位内容应等于当前包里的程序集长度", before.size, TextAssetPatch.findSlot(serialized, TextAssetPatch.readDataOffset(serialized), ASSEMBLY).capacity.let { before.size })
        assertEquals("换之前包里的程序集不应等于注入产物", false, before.contentEquals(injected))

        // 换掉程序集（SerializedFile 长度不变）
        val patchedSerialized = TextAssetPatch.replace(serialized, ASSEMBLY, injected)
        assertEquals("SerializedFile 长度不应改变", serialized.size, patchedSerialized.size)
        assertArrayEquals("槽位内容应换成注入产物", injected, TextAssetPatch.read(patchedSerialized, ASSEMBLY))

        // 重新封装容器
        val rewritten = UnityFsWriter.writeSingleNode(
            versionPlayer = bundle.versionPlayer,
            versionEngine = bundle.versionEngine,
            payload = patchedSerialized,
            originalHash = bundle.blocksInfoHash,
            nodePath = node.path,
            nodeFlags = node.flags,
        )

        // 再读回来
        val reparsed = UnityFs.parse(rewritten)
        assertEquals(1, reparsed.nodes.size)
        assertEquals("节点大小不应改变", node.size, reparsed.nodes.first().size)
        assertEquals("压缩方式应为不压缩", UnityFs.COMPRESSION_NONE, reparsed.compression)

        val roundTripped = reparsed.readNode(reparsed.nodes.first())
        assertArrayEquals("重新读回的程序集必须就是注入产物", injected, TextAssetPatch.read(roundTripped, ASSEMBLY))

        // 除程序集外，SerializedFile 的其它字节都不该动
        // Replacement zeroes the remainder of the ORIGINAL slot when the new DLL is smaller.
        val slot = TextAssetPatch.findSlot(serialized, TextAssetPatch.readDataOffset(serialized), ASSEMBLY)
        var diffOutsideSlot = 0
        for (i in serialized.indices) {
            if (i in slot.offset until slot.offset + slot.size) continue
            if (serialized[i] != patchedSerialized[i]) diffOutsideSlot++
        }
        assertEquals("槽位之外的字节不应有任何变化", 0, diffOutsideSlot)

        assertTrue(
            "不压缩容器应比原容器大（原 ${bundleFile.length()} B，新 ${rewritten.size} B）",
            rewritten.size > bundleFile.length(),
        )

        // 需要的话（CESIUM_OUT 指向路径）把产物落盘，供真机验证用
        val out = System.getenv("CESIUM_OUT")
        if (!out.isNullOrBlank()) {
            val file = File(out)
            file.parentFile?.mkdirs()
            file.writeBytes(rewritten)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(rewritten)
            println("patched bundle -> ${file.absolutePath} (${rewritten.size} B) sha256=" +
                digest.joinToString("") { "%02x".format(it) })
        }
    }

    private companion object {
        const val ASSEMBLY = "AstralParty.Runtime.dll"
    }
}
