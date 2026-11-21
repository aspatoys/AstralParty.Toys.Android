package com.hiynet.aspatoys.installer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Inspects an APK's ZIP entries without relying on an external `unzip` binary
 * (which is absent from Android toybox on many devices).
 */
internal object ApkZipScanner {
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CD_SIGNATURE = 0x02014b50
    private const val MAX_TAIL_BYTES = 65_557
    private const val MAX_CD_BYTES = 16 * 1024 * 1024

    val NativeLoaderPattern = Regex("""(?:^|\s)lib/[^/\s]+/libcesium\.so(?:\s|$)""")

    fun containsNativeLoader(
        path: String,
        statSize: (String) -> Long = {
            ShizukuShell.exec("stat -c %s " + ShizukuShell.quote(it)).trim().toLong()
        },
        readTail: (String, Int) -> ByteArray = { p, count ->
            ShizukuShell.execBytes("tail -c $count " + ShizukuShell.quote(p))
        },
        readSliceFromEnd: (String, Long, Int) -> ByteArray = { p, fromEnd, length ->
            ShizukuShell.execBytes("tail -c $fromEnd " + ShizukuShell.quote(p) + " | head -c $length")
        },
        checkExtractedLib: (String) -> Boolean = { p ->
            val parent = p.substringBeforeLast('/', "")
            parent.isNotEmpty() && ShizukuShell.exec(
                "find " + ShizukuShell.quote("$parent/lib") + " -name 'libcesium.so' 2>/dev/null; true",
            ).isNotBlank()
        },
    ): Boolean {
        val local = runCatching {
            ZipFile(path).use { apk -> apk.entries().asSequence().any { NativeLoaderPattern.containsMatchIn(it.name) } }
        }
        if (local.isSuccess) return local.getOrThrow()

        if (runCatching { checkExtractedLib(path) }.getOrDefault(false)) return true

        val size = statSize(path)
        require(size >= 22L) { "无法读取游戏安装包大小：$path" }
        val tailLen = minOf(size, MAX_TAIL_BYTES.toLong()).toInt()
        val tail = readTail(path, tailLen)
        val eocd = findEocd(tail) ?: error("无法解析游戏安装包目录：$path")
        require(eocd.cdSize in 0..MAX_CD_BYTES && eocd.cdOffset in 0..size - eocd.cdSize) {
            "游戏安装包目录范围无效：$path"
        }
        val tailStart = size - tail.size
        val cd = if (eocd.cdOffset >= tailStart && eocd.cdOffset + eocd.cdSize <= size) {
            val start = (eocd.cdOffset - tailStart).toInt()
            tail.copyOfRange(start, start + eocd.cdSize)
        } else {
            val fromEnd = size - eocd.cdOffset
            readSliceFromEnd(path, fromEnd, eocd.cdSize)
        }
        return centralDirectoryEntries(cd).any { NativeLoaderPattern.containsMatchIn(it) }
    }

    internal data class Eocd(val cdOffset: Long, val cdSize: Int)

    internal fun findEocd(tail: ByteArray): Eocd? {
        if (tail.size < 22) return null
        val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        for (pos in tail.size - 22 downTo 0) {
            if (buf.getInt(pos) != EOCD_SIGNATURE) continue
            val commentLen = buf.getShort(pos + 20).toInt() and 0xFFFF
            if (pos + 22 + commentLen != tail.size) continue
            val cdSize = buf.getInt(pos + 12).toLong() and 0xFFFFFFFFL
            val cdOffset = buf.getInt(pos + 16).toLong() and 0xFFFFFFFFL
            if (cdSize > Int.MAX_VALUE) return null
            return Eocd(cdOffset = cdOffset, cdSize = cdSize.toInt())
        }
        return null
    }

    internal fun centralDirectoryEntries(cd: ByteArray): List<String> {
        val entries = mutableListOf<String>()
        val buf = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 46 <= cd.size) {
            if (buf.getInt(pos) != CD_SIGNATURE) break
            val nameLen = buf.getShort(pos + 28).toInt() and 0xFFFF
            val extraLen = buf.getShort(pos + 30).toInt() and 0xFFFF
            val commentLen = buf.getShort(pos + 32).toInt() and 0xFFFF
            val nameStart = pos + 46
            val next = nameStart + nameLen + extraLen + commentLen
            if (next > cd.size) break
            entries += String(cd, nameStart, nameLen, Charsets.UTF_8)
            pos = next
        }
        return entries
    }
}
