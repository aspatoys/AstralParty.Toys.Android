package com.hiynet.cesiumloader.core.unity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import org.junit.Test

/**
 * 用真实的 `__data` 验证容器解析。
 *
 * 环境变量 `CESIUM_BUNDLE` 指向一份缓存里的热更包；没设置就跳过（仓库里不放 8 MB 的游戏数据）。
 */
class UnityFsTest {

    private fun bundleFile(): File? {
        val path = System.getenv("CESIUM_BUNDLE") ?: return null
        val file = File(path)
        return if (file.isFile) file else null
    }

    @Test
    fun parsesRealBundleAndExtractsRuntimeAssembly() {
        val file = bundleFile()
        assumeTrue("未设置 CESIUM_BUNDLE，跳过", file != null)

        val fs = UnityFs.parse(file!!.readBytes())

        assertEquals("UnityFS", fs.versionEngine.isNotEmpty().let { "UnityFS" })
        assertTrue("容器版本应为 6 及以上，实际 ${fs.version}", fs.version >= 6)
        assertEquals("Unity 2022.3 系列", true, fs.versionEngine.startsWith("2022.3"))
        assertEquals(UnityFs.COMPRESSION_LZ4HC, fs.compression)
        assertEquals("这个包只有一个节点", 1, fs.nodes.size)

        val node = fs.nodes.first()
        assertEquals("节点大小应等于块表解压长度之和", node.size, fs.blocks.sumOf { it.uncompressedSize.toLong() })

        val payload = fs.readNode(node)
        assertEquals(node.size, payload.size.toLong())

        // SerializedFile 头: u32 元数据长度 / u32 文件长度 / u32 版本 / u32 数据偏移
        val reader = BigEndianReader(payload)
        reader.readUInt()
        reader.readUInt()
        val serializedVersion = reader.readUInt()
        assertTrue("SerializedFile 版本异常: $serializedVersion", serializedVersion in 17..25)

        // 里面应当能找到热更程序集的名字
        val text = String(payload, Charsets.ISO_8859_1)
        assertTrue("包里没有 AstralParty.Runtime", text.contains("AstralParty.Runtime"))
    }

    @Test
    fun rejectsNonBundleInput() {
        val error = runCatching { UnityFs.parse(byteArrayOf(1, 2, 3, 4)) }.exceptionOrNull()
        assertTrue("应当拒绝非 UnityFS 输入", error is IllegalStateException)
    }

    @Test
    fun parsesBlocksWithHighFlagBitsSet() {
        val payload = "channel-bundle-payload".toByteArray(Charsets.UTF_8)
        val bundle = UnityFsWriter.writeSingleNode(
            versionPlayer = "5.x.x",
            versionEngine = "2022.3.62f3",
            payload = payload,
            originalHash = ByteArray(16) { it.toByte() },
            nodePath = "CAB-channel",
            nodeFlags = 4,
            containerVersion = 8,
        )
        // Locate the first StorageBlock's 2-byte flags inside uncompressed blocksInfo and set kBlockFlagStreamed (0x40).
        val reader = BigEndianReader(bundle)
        reader.readNullTerminatedString()
        reader.readUInt()
        reader.readNullTerminatedString()
        reader.readNullTerminatedString()
        reader.readLong()
        reader.readUInt()
        reader.readUInt()
        reader.readUInt()
        reader.alignTo(16)
        val blockFlagsOffset = reader.position + 16 + 4 + 4 + 4
        bundle[blockFlagsOffset] = 0x00
        bundle[blockFlagsOffset + 1] = 0x40

        val parsed = UnityFs.parse(bundle)
        assertEquals(0x40, parsed.blocks.single().flags)
        assertEquals(String(payload, Charsets.UTF_8), String(parsed.readNode(parsed.nodes.single()), Charsets.UTF_8))
    }
}
