package com.hiynet.cesiumloader.core.unity

/**
 * LZ4 块格式解码（不是帧格式）。
 *
 * UnityFS 的每个数据块都是这样压的：Unity 用的是 LZ4 / LZ4HC 的 raw block，
 * 没有帧头、没有内容校验，长度由块表里的 uncompressedSize 给出。
 * 只用得到解码，编码走另一条路（先写不压缩块）。
 */
internal object Lz4Block {

    fun decompress(src: ByteArray, sourceOffset: Int, sourceLength: Int, targetSize: Int): ByteArray {
        if (targetSize < 0) throw IllegalArgumentException("targetSize < 0: $targetSize")
        val dst = ByteArray(targetSize)
        var s = sourceOffset
        val sourceEnd = sourceOffset + sourceLength
        var d = 0

        while (s < sourceEnd) {
            val token = src[s++].toInt() and 0xFF

            var literals = token ushr 4
            if (literals == 15) {
                while (true) {
                    if (s >= sourceEnd) throw IllegalStateException("LZ4 字面量长度越界")
                    val b = src[s++].toInt() and 0xFF
                    literals += b
                    if (b != 255) break
                }
            }
            if (literals > 0) {
                if (s + literals > sourceEnd || d + literals > targetSize) {
                    throw IllegalStateException("LZ4 字面量越界: literals=$literals")
                }
                System.arraycopy(src, s, dst, d, literals)
                s += literals
                d += literals
            }

            if (s >= sourceEnd) break

            if (s + 1 >= sourceEnd) throw IllegalStateException("LZ4 偏移越界")
            val offset = (src[s].toInt() and 0xFF) or ((src[s + 1].toInt() and 0xFF) shl 8)
            s += 2
            if (offset == 0 || offset > d) throw IllegalStateException("LZ4 偏移非法: offset=$offset d=$d")

            var matchLength = token and 0x0F
            if (matchLength == 15) {
                while (true) {
                    if (s >= sourceEnd) throw IllegalStateException("LZ4 匹配长度越界")
                    val b = src[s++].toInt() and 0xFF
                    matchLength += b
                    if (b != 255) break
                }
            }
            matchLength += 4

            var m = d - offset
            if (d + matchLength > targetSize) throw IllegalStateException("LZ4 匹配越界")
            for (i in 0 until matchLength) dst[d++] = dst[m++]
        }

        if (d != targetSize) throw IllegalStateException("LZ4 解压长度不符: 得到 $d, 期望 $targetSize")
        return dst
    }
}
