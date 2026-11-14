package com.hiynet.cesiumloader.core.unity

/**
 * 直接改 SerializedFile 里的 TextAsset 负载（热更程序集就是用这个装的）。
 *
 * TextAsset 的负载排布（v22、小端实测）：
 *
 * ```
 * u32LE  nameLen
 * bytes  name + 0x00        （对齐到 4）
 * u32LE  scriptLen
 * bytes  script             （PE 镜像）
 * ```
 *
 * 所以替换 = 改 `scriptLen` + 写脚本字节 + 尾部清零，**不需要动对象表**：
 * 只要新内容不超过原槽位容量，整个 SerializedFile 的长度与所有对象偏移都不变。
 *
 * 超出容量时抛异常：那条路要重建对象偏移（尚未实现），不能静默截断。
 */
object TextAssetPatch {

    /** u32 nameLen + 名称 + 0x00（对齐到 4）+ u32 scriptLen。 */
    const val SLOT_HEADER_SIZE = 32

    /** `scriptLen` 相对槽位起点的偏移。 */
    private const val LENGTH_FIELD_OFFSET = 28

    data class Slot(val offset: Int, val size: Int) {
        val capacity: Int get() = size - SLOT_HEADER_SIZE
    }

    /**
     * 把 [serialized] 里名为 [assetName] 的 TextAsset 负载换成 [content]。
     *
     * 返回新的 SerializedFile 字节（长度与入参相同）。
     */
    fun replace(serialized: ByteArray, assetName: String, content: ByteArray): ByteArray {
        val dataOffset = readDataOffset(serialized)
        val slot = findSlot(serialized, dataOffset, assetName)
        require(content.size <= slot.capacity) {
            "内容 ${content.size} B 超过槽位容量 ${slot.capacity} B，需要走对象偏移重建"
        }

        val out = serialized.copyOf()
        writeUIntLe(out, slot.offset + LENGTH_FIELD_OFFSET, content.size)
        System.arraycopy(content, 0, out, slot.offset + SLOT_HEADER_SIZE, content.size)
        java.util.Arrays.fill(
            out,
            slot.offset + SLOT_HEADER_SIZE + content.size,
            slot.offset + slot.size,
            0,
        )
        return out
    }

    /** 只读：返回指定 TextAsset 当前的负载内容。 */
    fun read(serialized: ByteArray, assetName: String): ByteArray {
        val slot = findSlot(serialized, readDataOffset(serialized), assetName)
        val length = readUIntLe(serialized, slot.offset + LENGTH_FIELD_OFFSET)
        val start = slot.offset + SLOT_HEADER_SIZE
        require(length in 1..serialized.size - start) { "负载长度异常: $length" }
        return serialized.copyOfRange(start, start + length)
    }

    /** v22 的尺寸字段是 64 位扩展字段，前四个 u32 在 v22 里被置 0。 */
    fun readDataOffset(serialized: ByteArray): Int {
        val reader = BigEndianReader(serialized)
        reader.readUInt()
        reader.readUInt()
        val version = reader.readUInt()
        reader.readUInt()
        reader.seek(20)
        reader.readUInt()
        reader.readLong()
        val dataOffset = reader.readLong().toInt()
        require(version == 22) { "只处理 SerializedFile v22，实际 $version" }
        require(dataOffset in 48 until serialized.size) { "dataOffset 异常: $dataOffset" }
        return dataOffset
    }

    fun findSlot(serialized: ByteArray, dataOffset: Int, assetName: String): Slot {
        val nameBytes = assetName.toByteArray(Charsets.US_ASCII)
        val needle = ByteArray(4 + nameBytes.size + 1).also { buffer ->
            writeUIntLe(buffer, 0, nameBytes.size)
            System.arraycopy(nameBytes, 0, buffer, 4, nameBytes.size)
        }

        var position = dataOffset
        while (position + needle.size + 4 <= serialized.size) {
            var matched = true
            for (i in needle.indices) {
                if (serialized[position + i] != needle[i]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                val lengthOffset = position + LENGTH_FIELD_OFFSET
                if (lengthOffset + 4 <= serialized.size) {
                    val scriptLength = readUIntLe(serialized, lengthOffset)
                    if (scriptLength in 1 until serialized.size - dataOffset) {
                        val size = align4(SLOT_HEADER_SIZE + scriptLength)
                        if (position + size <= serialized.size) return Slot(position, size)
                    }
                }
            }
            position++
        }
        throw IllegalStateException("数据区里找不到 TextAsset: $assetName")
    }

    private fun align4(value: Int): Int = (value + 3) and 3.inv()

    private fun readUIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeUIntLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
