package com.hiynet.cesiumloader.core.unity

/**
 * UnityFS 容器的只读解析。
 *
 * 布局（v6 及以上，本文对应实测的 v8）：
 *
 * ```
 * "UnityFS\0"
 * u32  容器版本            (8)
 * cstr 引擎版本串          ("5.x.x")
 * cstr Unity 版本串        ("2022.3.62f3")
 * i64  总长度
 * u32  blocksInfo 压缩后长度
 * u32  blocksInfo 解压后长度
 * u32  flags               (低 6 位 = 压缩方式, 0x40/0x80/0x200 = 目录合并 / 块信息在尾部 / 块信息前对齐)
 * [若 flags 带 0x200: 16 字节块信息前填充 —— 见 alignTo(16)]
 *
 * 版本 >= 7 时对当前位置做 16 字节对齐
 * blocksInfo: 16 字节哈希 + u32 块数 + 块表 + u32 节点数 + 节点表
 * [若 BlocksInfoAtTheEnd: blocksInfo 在文件尾部读取]
 * 其后（按需再对齐一次 16）是各数据块，按块表顺序拼接
 * ```
 *
 * 只实现读取与「按节点取文件」；写回见 [UnityFsWriter]。
 */
class UnityFs private constructor(
    val version: Int,
    val versionPlayer: String,
    val versionEngine: String,
    val flags: Int,
    val blocks: List<Block>,
    val nodes: List<Node>,
    /** 块信息开头的 16 字节哈希；运行期不校验，写回时原样带上。 */
    val blocksInfoHash: ByteArray,
    /** 数据块在容器文件里的绝对起点（块表之后，含对齐填充）。 */
    val dataStart: Int,
    private val payload: ByteArray,
) {
    data class Block(val uncompressedSize: Int, val compressedSize: Int, val flags: Int)

    data class Node(val offset: Long, val size: Long, val flags: Int, val path: String)

    val compression: Int get() = flags and 0x3F

    fun readNode(node: Node): ByteArray {
        val start = node.offset.toInt()
        val end = start + node.size.toInt()
        if (start < 0 || end > payload.size) {
            throw IllegalStateException("节点越界: ${node.path} [$start, $end) 超出 ${payload.size}")
        }
        return payload.copyOfRange(start, end)
    }

    fun readFileByName(name: String): ByteArray {
        val node = nodes.firstOrNull { it.path.substringAfterLast('/') == name }
            ?: throw NoSuchElementException("容器里没有 $name（共 ${nodes.size} 个节点）")
        return readNode(node)
    }

    companion object {
        private const val MAGIC = "UnityFS"
        private const val FLAG_BLOCKS_AND_DIRECTORY_COMBINED = 0x40
        private const val FLAG_BLOCKS_INFO_AT_THE_END = 0x80
        private const val FLAG_BLOCK_INFO_NEED_PADDING_AT_START = 0x200

        const val COMPRESSION_NONE = 0
        const val COMPRESSION_LZMA = 1
        const val COMPRESSION_LZ4 = 2
        const val COMPRESSION_LZ4HC = 3

        fun parse(bytes: ByteArray): UnityFs {
            val reader = BigEndianReader(bytes)

            val signature = reader.readNullTerminatedString()
            if (signature != MAGIC) throw IllegalStateException("不是 UnityFS 容器: $signature")

            val version = reader.readUInt()
            val versionPlayer = reader.readNullTerminatedString()
            val versionEngine = reader.readNullTerminatedString()

            reader.readLong() // 容器声明的总长度，与实际文件长度偶有不一致，不作为校验依据
            val compressedInfoSize = reader.readUInt()
            val uncompressedInfoSize = reader.readUInt()
            val flags = reader.readUInt()

            if (version >= 7) reader.alignTo(16)

            val infoStart = reader.position
            val infoBytes = if (flags and FLAG_BLOCKS_INFO_AT_THE_END != 0) {
                val end = bytes.size - compressedInfoSize
                reader.seek(end)
                reader.readBytes(compressedInfoSize).also { reader.seek(infoStart) }
            } else {
                reader.readBytes(compressedInfoSize)
            }

            val info = decompress(infoBytes, uncompressedInfoSize, flags and 0x3F, "blocksInfo")
            val infoReader = BigEndianReader(info)
            val blocksInfoHash = infoReader.readBytes(16) // uncompressedDataHash

            val blockCount = infoReader.readUInt()
            val blocks = ArrayList<Block>(blockCount)
            for (i in 0 until blockCount) {
                val uncompressedSize = infoReader.readUInt()
                val compressedSize = infoReader.readUInt()
                val blockFlags = infoReader.readUShort()
                blocks.add(Block(uncompressedSize, compressedSize, blockFlags))
            }

            val nodeCount = infoReader.readUInt()
            val nodes = ArrayList<Node>(nodeCount)
            for (i in 0 until nodeCount) {
                val offset = infoReader.readLong()
                val size = infoReader.readLong()
                val nodeFlags = infoReader.readUInt()
                val path = infoReader.readNullTerminatedString()
                nodes.add(Node(offset, size, nodeFlags, path))
            }

            if (flags and FLAG_BLOCK_INFO_NEED_PADDING_AT_START != 0) reader.alignTo(16)
            val dataStart = reader.position

            var total = 0
            for (block in blocks) total += block.uncompressedSize
            val payload = ByteArray(total)
            var written = 0
            for ((index, block) in blocks.withIndex()) {
                val raw = reader.readBytes(block.compressedSize)
                val plain = decompress(raw, block.uncompressedSize, block.flags, "block#$index")
                System.arraycopy(plain, 0, payload, written, plain.size)
                written += plain.size
            }

            return UnityFs(version, versionPlayer, versionEngine, flags, blocks, nodes, blocksInfoHash, dataStart, payload)
        }

        private fun decompress(src: ByteArray, uncompressedSize: Int, compression: Int, what: String): ByteArray {
            return when (compression) {
                COMPRESSION_NONE -> src
                COMPRESSION_LZ4, COMPRESSION_LZ4HC -> Lz4Block.decompress(src, 0, src.size, uncompressedSize)
                COMPRESSION_LZMA -> throw UnsupportedOperationException("$what 是 LZMA 压缩，暂未实现")
                else -> throw UnsupportedOperationException("$what 的压缩方式未知: $compression")
            }
        }
    }
}

/** 大端读取器；Unity 的这些容器全是网络字节序。 */
internal class BigEndianReader(private val data: ByteArray) {
    var position: Int = 0
        private set

    fun seek(offset: Int) {
        if (offset < 0 || offset > data.size) throw IllegalStateException("seek 越界: $offset / ${data.size}")
        position = offset
    }

    fun skip(count: Int) {
        seek(position + count)
    }

    fun alignTo(alignment: Int) {
        val remainder = position % alignment
        if (remainder != 0) seek(position + (alignment - remainder))
    }

    fun readByte(): Int {
        if (position + 1 > data.size) throw IllegalStateException("读取越界: byte @ $position")
        return data[position++].toInt() and 0xFF
    }

    fun readUShort(): Int {
        if (position + 2 > data.size) throw IllegalStateException("读取越界: ushort @ $position")
        val value = ((data[position].toInt() and 0xFF) shl 8) or (data[position + 1].toInt() and 0xFF)
        position += 2
        return value
    }

    fun readUInt(): Int {
        if (position + 4 > data.size) throw IllegalStateException("读取越界: uint @ $position")
        val value = ((data[position].toInt() and 0xFF) shl 24) or
            ((data[position + 1].toInt() and 0xFF) shl 16) or
            ((data[position + 2].toInt() and 0xFF) shl 8) or
            (data[position + 3].toInt() and 0xFF)
        position += 4
        return value
    }

    fun readLong(): Long {
        val high = readUInt().toLong() and 0xFFFFFFFFL
        val low = readUInt().toLong() and 0xFFFFFFFFL
        return (high shl 32) or low
    }

    fun readBytes(count: Int): ByteArray {
        if (count < 0 || position + count > data.size) throw IllegalStateException("读取越界: bytes($count) @ $position / ${data.size}")
        val value = data.copyOfRange(position, position + count)
        position += count
        return value
    }

    fun readNullTerminatedString(): String {
        val start = position
        while (position < data.size && data[position].toInt() != 0) position++
        val value = String(data, start, position - start, Charsets.UTF_8)
        if (position < data.size) position++
        return value
    }
}
