package com.hiynet.cesiumloader.core.unity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import org.junit.Test

/**
 * 容器往返：读真实 `__data` → 用不压缩块重新封装 → 再读回来，节点内容必须逐字节一致。
 *
 * 这是手机端写回层的验收前提：只要能原样重封装，就能把改过的 SerializedFile 装回去。
 */
class UnityFsWriterTest {

    private fun envFile(name: String): File? {
        val path = System.getenv(name) ?: return null
        val file = File(path)
        return if (file.isFile) file else null
    }

    @Test
    fun roundTripsRealBundleWithUncompressedBlocks() {
        val bundleFile = envFile("CESIUM_BUNDLE")
        assumeTrue("未设置 CESIUM_BUNDLE，跳过", bundleFile != null)

        val original = UnityFs.parse(bundleFile!!.readBytes())
        assertEquals(1, original.nodes.size)
        val node = original.nodes.first()
        val payload = original.readNode(node)

        val rewritten = UnityFsWriter.writeSingleNode(
            versionPlayer = original.versionPlayer,
            versionEngine = original.versionEngine,
            payload = payload,
            originalHash = original.blocksInfoHash,
            nodePath = node.path,
            nodeFlags = node.flags,
            containerVersion = original.version,
        )

        val reparsed = UnityFs.parse(rewritten)
        assertEquals("节点数", original.nodes.size, reparsed.nodes.size)
        assertEquals("节点路径", node.path, reparsed.nodes.first().path)
        assertEquals("节点大小", node.size, reparsed.nodes.first().size)
        assertEquals("压缩方式应为不压缩", UnityFs.COMPRESSION_NONE, reparsed.compression)
        assertArrayEquals("块信息哈希应原样保留", original.blocksInfoHash, reparsed.blocksInfoHash)
        assertArrayEquals("节点内容必须逐字节一致", payload, reparsed.readNode(reparsed.nodes.first()))

        assertTrue(
            "不压缩容器应比原容器大（原 ${bundleFile.length()} B，新 ${rewritten.size} B）",
            rewritten.size > bundleFile.length(),
        )
        assertEquals("块表应覆盖整个负载", payload.size, reparsed.blocks.sumOf { it.uncompressedSize })
    }
}
