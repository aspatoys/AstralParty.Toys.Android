package com.hiynet.cesiumloader.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 工具：下载校验、备份校验、安装前比对都用同一份实现。 */
object Hashing {

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    fun sha256Hex(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    fun matches(file: File, expectedHex: String?): Boolean {
        if (expectedHex.isNullOrBlank() || !file.isFile) return false
        return sha256Hex(file).equals(expectedHex.trim(), ignoreCase = true)
    }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 0xFF
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0F]
        }
        return String(chars)
    }

    private const val HEX = "0123456789abcdef"
}
