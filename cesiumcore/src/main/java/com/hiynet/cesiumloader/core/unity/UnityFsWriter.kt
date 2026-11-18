package com.hiynet.cesiumloader.core.unity

/**
 * UnityFS 容器写出。
 *
 * 只产出**不压缩块**（`compression = 0`）：手机上不需要 LZ4 编码器，代价是包变大
 * （这个游戏的 `__data` 从 8.17 MB 变成约 19 MB）。Unity 与 Addressables 都接受这种布局，
 * 缓存包不做 CRC 校验。
 *
 * 布局与 [UnityFs] 的读取一一对应，写完后可以直接用 [UnityFs.parse] 读回来比对。
 */
object UnityFsWriter {

    /** 单块上限：128 KiB，和 Unity 自己的分块量级一致。 */
    private const val BLOCK_SIZE = 0x20000

    private const val FLAG_BLOCKS_AND_DIRECTORY_COMBINED = 0x40
    private const val FLAG_BLOCK_INFO_NEED_PADDING_AT_START = 0x200

    /** 非压缩容器的 flags：块信息与目录合并 + 块信息前对齐，压缩方式 0。 */
    const val FLAGS_UNCOMPRESSED = FLAG_BLOCKS_AND_DIRECTORY_COMBINED or FLAG_BLOCK_INFO_NEED_PADDING_AT_START

    /**
     * 把一个节点的负载重新封装成容器。
     *
     * @param payload 解压后的节点内容（本游戏只有一个节点，即整个 SerializedFile）
     * @param originalHash 原容器块信息里的 16 字节哈希；运行期不校验，原样透传以免引入差异
     * @param nodePath 节点路径，例如 `CAB-a514b31ef572f8bae6a5014ba73001f9`
     * @param nodeFlags 原节点的 flags
     */
    fun writeSingleNode(
        versionPlayer: String,
        versionEngine: String,
        payload: ByteArray,
        originalHash: ByteArray,
        nodePath: String,
        nodeFlags: Int,
        containerVersion: Int = 8,
    ): ByteArray = writeNodes(versionPlayer, versionEngine, originalHash, containerVersion,
        listOf(UnityFs.Node(0, payload.size.toLong(), nodeFlags, nodePath) to payload))

    /** Retains all other node payloads and directory entries verbatim. */
    fun replaceNode(container: UnityFs, index: Int, replacement: ByteArray): ByteArray {
        require(index in container.nodes.indices)
        val entries = container.nodes.mapIndexed { position, node ->
            node to if (position == index) replacement else container.readNode(node)
        }
        return writeNodes(container.versionPlayer, container.versionEngine, container.blocksInfoHash, container.version, entries)
    }

    private fun writeNodes(
        versionPlayer: String,
        versionEngine: String,
        originalHash: ByteArray,
        containerVersion: Int,
        entries: List<Pair<UnityFs.Node, ByteArray>>,
    ): ByteArray {
        require(originalHash.size == 16) { "块信息哈希必须是 16 字节，实际 ${originalHash.size}" }

        val total = entries.sumOf { it.second.size.toLong() }
        require(total in 1..UnityFs.MAX_CONTENT_BYTES.toLong()) { "重建后的缓存内容太大" }
        val payload = ByteArray(total.toInt())
        var nodeOffset = 0
        for ((_, bytes) in entries) {
            bytes.copyInto(payload, nodeOffset)
            nodeOffset += bytes.size
        }
        val blockCount = if (payload.isEmpty()) 0 else (payload.size + BLOCK_SIZE - 1) / BLOCK_SIZE

        // ---- 块信息 ----
        val info = ByteBufferWriter()
        info.writeBytes(originalHash)
        info.writeUInt(blockCount)
        for (i in 0 until blockCount) {
            val start = i * BLOCK_SIZE
            val size = minOf(BLOCK_SIZE, payload.size - start)
            info.writeUInt(size)   // uncompressedSize
            info.writeUInt(size)   // compressedSize（不压缩，两者相等）
            info.writeUShort(UnityFs.COMPRESSION_NONE)
        }
        info.writeUInt(entries.size)
        nodeOffset = 0
        for ((node, bytes) in entries) {
            info.writeLong(nodeOffset.toLong())
            info.writeLong(bytes.size.toLong())
            info.writeUInt(node.flags)
            info.writeNullTerminatedString(node.path)
            nodeOffset += bytes.size
        }
        val infoBytes = info.toByteArray()

        // ---- 容器头 ----
        val header = ByteBufferWriter()
        header.writeNullTerminatedString("UnityFS")
        header.writeUInt(containerVersion)
        header.writeNullTerminatedString(versionPlayer)
        header.writeNullTerminatedString(versionEngine)
        header.writeLong(0)                    // 总长度占位，最后回填
        header.writeUInt(infoBytes.size)       // compressedBlocksInfoSize
        header.writeUInt(infoBytes.size)       // uncompressedBlocksInfoSize
        header.writeUInt(FLAGS_UNCOMPRESSED)
        var bytes = header.toByteArray()
        if (containerVersion >= 7) bytes = padTo(bytes, 16)

        bytes = concat(bytes, infoBytes)
        bytes = padTo(bytes, 16)
        bytes = concat(bytes, payload)

        // 回填总长度：位置在 "UnityFS\0" + u32 + 两个 cstr 之后
        val sizeOffset = 8 + 4 + versionPlayer.toByteArray(Charsets.UTF_8).size + 1 +
            versionEngine.toByteArray(Charsets.UTF_8).size + 1
        writeLongBe(bytes, sizeOffset, bytes.size.toLong())
        return bytes
    }

    private fun padTo(bytes: ByteArray, alignment: Int): ByteArray {
        val remainder = bytes.size % alignment
        if (remainder == 0) return bytes
        return bytes.copyOf(bytes.size + (alignment - remainder))
    }

    private fun concat(left: ByteArray, right: ByteArray): ByteArray {
        val out = ByteArray(left.size + right.size)
        System.arraycopy(left, 0, out, 0, left.size)
        System.arraycopy(right, 0, out, left.size, right.size)
        return out
    }

    private fun writeLongBe(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            bytes[offset + i] = ((value ushr (56 - i * 8)) and 0xFF).toByte()
        }
    }
}

/** 大端写出，与 [BigEndianReader] 对称。 */
internal class ByteBufferWriter {
    private var buffer = ByteArray(4096)
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        var capacity = buffer.size * 2
        while (capacity < size + extra) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    fun writeByte(value: Int) {
        ensure(1)
        buffer[size++] = (value and 0xFF).toByte()
    }

    fun writeUShort(value: Int) {
        ensure(2)
        buffer[size++] = ((value ushr 8) and 0xFF).toByte()
        buffer[size++] = (value and 0xFF).toByte()
    }

    fun writeUInt(value: Int) {
        ensure(4)
        for (i in 0 until 4) buffer[size++] = ((value ushr (24 - i * 8)) and 0xFF).toByte()
    }

    fun writeLong(value: Long) {
        ensure(8)
        for (i in 0 until 8) buffer[size++] = ((value ushr (56 - i * 8)) and 0xFF).toByte()
    }

    fun writeBytes(value: ByteArray) {
        ensure(value.size)
        System.arraycopy(value, 0, buffer, size, value.size)
        size += value.size
    }

    fun writeNullTerminatedString(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        writeBytes(encoded)
        writeByte(0)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}
