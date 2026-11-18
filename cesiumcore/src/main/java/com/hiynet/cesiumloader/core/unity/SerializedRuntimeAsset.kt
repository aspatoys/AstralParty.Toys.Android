package com.hiynet.cesiumloader.core.unity

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Locates the actual TextAsset through Unity's object table and adjusts offsets when it grows. */
class SerializedRuntimeAsset private constructor(
    private val bytes: ByteArray,
    private val little: ByteOrder,
    private val version: Int,
    private val dataOffset: Int,
    private val objects: List<Entry>,
    private val target: Entry,
    private val lengthOffset: Int,
    private val scriptLength: Int,
) {
    private data class Entry(val offsetField: Int, val offset: Int, val size: Int, val type: Int)

    fun assembly(): ByteArray = bytes.copyOfRange(lengthOffset + 4, lengthOffset + 4 + scriptLength)

    fun replace(assembly: ByteArray): ByteArray {
        requirePe(assembly)
        val targetStart = dataOffset + target.offset
        val targetEnd = targetStart + target.size
        val scriptStart = lengthOffset + 4
        val prefix = scriptStart - targetStart
        val size = (prefix + assembly.size + 3) and 3.inv()
        // Shift all following objects by a multiple of Unity's 8-byte object alignment.
        val delta = (size - target.size + 7) and 7.inv()
        require(bytes.size.toLong() + delta in 1..LIMIT.toLong()) { "重建后的资源太大" }
        val output = ByteArray(bytes.size + delta)
        bytes.copyInto(output, 0, 0, scriptStart)
        assembly.copyInto(output, scriptStart)
        bytes.copyInto(output, targetEnd + delta, targetEnd)

        val table = ByteBuffer.wrap(output).order(little)
        table.putInt(lengthOffset, assembly.size)
        val offsetBytes = if (version >= 22) 8 else 4
        table.putInt(target.offsetField + offsetBytes, size)
        for (entry in objects) {
            if (entry.offset < target.offset + target.size || entry === target) continue
            if (offsetBytes == 8) table.putLong(entry.offsetField, (entry.offset + delta).toLong())
            else table.putInt(entry.offsetField, entry.offset + delta)
        }
        table.order(ByteOrder.BIG_ENDIAN)
        if (version >= 22) table.putLong(24, output.size.toLong()) else table.putInt(4, output.size)
        return output
    }

    companion object {
        private const val LIMIT = 64 * 1024 * 1024
        private const val TEXT_ASSET = 49
        private const val MONO_BEHAVIOUR = 114

        /** Returns null if a valid serialized file has no TextAsset of the requested name. */
        fun find(bytes: ByteArray, name: String): SerializedRuntimeAsset? {
            require(bytes.size in 48..LIMIT) { "Unity 资源大小无效" }
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val version = header.getInt(8)
            require(version in 17..22) { "不支持的 Unity SerializedFile 版本：$version" }
            val declaredSize = if (version >= 22) header.getLong(24) else header.getInt(4).toLong()
            require(declaredSize == bytes.size.toLong()) { "Unity 资源长度不符" }
            val headerSize = if (version >= 22) 48 else 20
            val dataOffset = if (version >= 22) header.getLong(32) else header.getInt(12).toLong()
            require(dataOffset in headerSize.toLong()..bytes.size.toLong()) { "Unity 资源数据区越界" }
            val order = when (bytes[16].toInt()) {
                0 -> ByteOrder.LITTLE_ENDIAN
                1 -> ByteOrder.BIG_ENDIAN
                else -> error("Unity 资源字节序无效")
            }
            val table = ByteBuffer.wrap(bytes).order(order)
            table.position(headerSize)
            table.limit(dataOffset.toInt())
            val versionStart = table.position()
            while (table.get().toInt() != 0) require(table.position() - versionStart < 1024)
            table.int // Target platform.
            val hasTree = table.get().toInt() != 0
            val classes = IntArray(table.int.also { require(it in 0..65_536) })
            for (i in classes.indices) {
                val cls = table.int
                classes[i] = cls
                table.skip(3)
                if (cls == MONO_BEHAVIOUR) table.skip(16)
                table.skip(16)
                if (hasTree) {
                    val nodes = table.int.also { require(it in 0..100_000) }
                    val strings = table.int.also { require(it >= 0) }
                    table.skip(nodes * if (version >= 19) 32 else 24)
                    table.skip(strings)
                    if (version >= 21) {
                        val deps = table.int.also { require(it in 0..65_536) }
                        table.skip(deps * 4)
                    }
                }
            }

            val objects = List(table.int.also { require(it in 0..100_000) }) {
                table.position((table.position() + 3) and 3.inv())
                table.long // Path ID.
                val offsetField = table.position()
                val offset = if (version >= 22) table.long else table.int.toLong()
                val size = table.int
                val type = table.int
                require(type in classes.indices && offset >= 0 && size >= 0 && offset <= bytes.size - dataOffset - size) {
                    "Unity 对象表越界"
                }
                Entry(offsetField, offset.toInt(), size, classes[type])
            }
            var result: SerializedRuntimeAsset? = null
            var previousEnd = 0
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            for (entry in objects.sortedBy(Entry::offset)) {
                require(previousEnd <= entry.offset) { "Unity 对象重叠" }
                previousEnd = entry.offset + entry.size
                if (entry.type != TEXT_ASSET || entry.size < 8) continue
                val start = dataOffset.toInt() + entry.offset
                val view = ByteBuffer.wrap(bytes, start, entry.size).slice().order(order)
                val nameSize = view.int
                if (nameSize != nameBytes.size) continue
                require(nameSize <= view.remaining() - 4) { "TextAsset 名称越界" }
                val actualName = ByteArray(nameSize).also(view::get)
                if (!actualName.contentEquals(nameBytes)) continue
                require(result == null) { "存在多个 $name 对象" }
                val lengthOffset = (view.position() + 3) and 3.inv()
                val scriptLength = view.getInt(lengthOffset)
                val padding = entry.size.toLong() - lengthOffset - 4 - scriptLength
                require(scriptLength >= 2 && padding in 0..3) { "TextAsset 程序集结构无法识别" }
                require(bytes[start + lengthOffset + 4] == 'M'.code.toByte() &&
                    bytes[start + lengthOffset + 5] == 'Z'.code.toByte()) { "TextAsset 不是托管程序集" }
                result = SerializedRuntimeAsset(bytes, order, version, dataOffset.toInt(), objects,
                    entry, start + lengthOffset, scriptLength)
            }
            return result
        }

        private fun ByteBuffer.skip(count: Int) {
            require(count in 0..remaining()) { "Unity 对象表不完整" }
            position(position() + count)
        }

        private fun requirePe(bytes: ByteArray) {
            require(bytes.size >= 2 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) {
                "编辑后的游戏程序集无效"
            }
        }
    }
}
